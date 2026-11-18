package com.sherwood.wta.core.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * The integrity gate.
 *
 * What is being pinned here is the exact failure an archive can hide: an entry that
 * is still *present* and still the *same length*, but not the bytes that were
 * exported. Length alone would accept it — the digest will not.
 */
@RunWith(RobolectricTestRunner::class)
class BackupIntegrityTest {

    private fun fingerprint(path: String, content: String) = BackupEntryFingerprint(
        path = path,
        size = content.toByteArray().size.toLong(),
        sha256 = BackupIntegrity.hex(content.toByteArray())
    )

    @Test
    fun `manifest round trips every entry`() {
        val manifest = BackupManifest(
            backupVersion = 6,
            createdAt = 1_700_000_000_000L,
            entries = listOf(
                fingerprint("apps.json", """{"version":6}"""),
                fingerprint("local/files/aicoding/x.txt", "hello")
            )
        )

        val parsed = BackupManifest.fromJson(manifest.toJson())

        assertThat(parsed.backupVersion).isEqualTo(6)
        assertThat(parsed.createdAt).isEqualTo(1_700_000_000_000L)
        assertThat(parsed.entries).hasSize(2)
        assertThat(parsed.entries.map { it.path })
            .containsExactly("apps.json", "local/files/aicoding/x.txt")
        assertThat(parsed.entries[1].sha256).isEqualTo(manifest.entries[1].sha256)
        assertThat(parsed.entries[1].size).isEqualTo(5L)
    }

    @Test
    fun `an archive whose entries match its manifest verifies`() {
        val observed = listOf(fingerprint("apps.json", "{}"), fingerprint("local/files/x", "abc"))
        val manifest = BackupManifest(6, 0L, observed)
        BackupIntegrity.verify(manifest, observed, manifestRequired = true)
    }

    @Test
    fun `an entry listed in the manifest but missing from the archive is rejected`() {
        val present = listOf(fingerprint("apps.json", "{}"))
        // A truncated download: the manifest survived, the file it names did not.
        val manifest = BackupManifest(
            6, 0L, present + fingerprint("resources/icons/1_icon.png", "PNGDATA")
        )

        val failure = runCatching {
            BackupIntegrity.verify(manifest, present, manifestRequired = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupIntegrityException.Incomplete::class.java)
        assertThat(failure!!.message).contains("resources/icons/1_icon.png")
    }

    @Test
    fun `an entry with the same length but different bytes is rejected`() {
        val manifest = BackupManifest(6, 0L, listOf(fingerprint("local/files/x", "aaaa")))
        // Same size, different content: the case a length check cannot catch.
        val observed = listOf(fingerprint("local/files/x", "bbbb"))

        val failure = runCatching {
            BackupIntegrity.verify(manifest, observed, manifestRequired = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupIntegrityException.Mismatch::class.java)
    }

    @Test
    fun `an entry the manifest does not vouch for is rejected`() {
        val manifest = BackupManifest(6, 0L, listOf(fingerprint("apps.json", "{}")))
        val observed = listOf(
            fingerprint("apps.json", "{}"),
            fingerprint("local/files/surprise.txt", "extra")
        )

        val failure = runCatching {
            BackupIntegrity.verify(manifest, observed, manifestRequired = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupIntegrityException.Undeclared::class.java)
        assertThat(failure!!.message).contains("surprise.txt")
    }

    @Test
    fun `a v6 archive without a manifest is rejected`() {
        val failure = runCatching {
            BackupIntegrity.verify(null, emptyList(), manifestRequired = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupIntegrityException.Missing::class.java)
    }

    @Test
    fun `a pre-v6 archive without a manifest still verifies`() {
        // Backups written before manifests existed must stay restorable; the
        // manifest becomes mandatory only for archives that claim v6.
        BackupIntegrity.verify(null, listOf(fingerprint("apps.json", "{}")), manifestRequired = false)
    }

    @Test
    fun `a malformed manifest is rejected instead of treated as empty`() {
        val failure = runCatching {
            BackupManifest.fromJson("""{"format": 1, "entries": "not-an-array"}""")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupIntegrityException.Malformed::class.java)
    }

    @Test
    fun `an unknown manifest format is rejected`() {
        val failure = runCatching {
            BackupManifest.fromJson("""{"format": 99, "entries": []}""")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupIntegrityException.Malformed::class.java)
    }

    @Test
    fun `the digesting sink measures and hashes exactly what it forwards`() {
        val forwarded = ByteArrayOutputStream()
        val sink = Sha256Sink(forwarded)
        val payload = "the quick brown fox".toByteArray()

        sink.write(payload, 0, payload.size)
        sink.write('!'.code)
        sink.flush()

        assertThat(forwarded.toByteArray().toString(Charsets.UTF_8))
            .isEqualTo("the quick brown fox!")
        assertThat(sink.size).isEqualTo(payload.size + 1L)
        assertThat(sink.sha256()).isEqualTo(
            BackupIntegrity.hex("the quick brown fox!".toByteArray())
        )
    }
}
