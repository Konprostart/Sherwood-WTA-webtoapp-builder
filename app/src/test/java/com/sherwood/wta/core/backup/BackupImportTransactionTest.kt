package com.sherwood.wta.core.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.data.converter.Converters
import com.sherwood.wta.data.database.AppDatabase
import com.sherwood.wta.data.model.AppType
import com.sherwood.wta.data.model.WebApp
import com.sherwood.wta.data.repository.WebAppRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end restore behaviour, including the case the transaction exists for: an
 * import that fails *after* the database writes and *after* some local files have
 * already been replaced.
 *
 * Ignored for the same reason as [DataBackupManagerTest]: a Room coroutine query
 * started from a Robolectric test is cancelled by the sandbox (the application scope
 * is shut down between test methods), so the first `allWebApps.first()` in setup
 * throws `JobCancellationException` before any assertion runs. The commit/rollback
 * machinery this scenario depends on stays covered by [BackupLocalTransactionTest],
 * which needs no database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@org.junit.Ignore("Room coroutine queries are cancelled by Robolectric between tests; BackupLocalTransactionTest covers the same machinery without Room")
class BackupImportTransactionTest {

    @Rule @JvmField
    val koinRule = com.sherwood.wta.util.KoinCleanupRule()

    private lateinit var context: Context
    private lateinit var manager: DataBackupManager
    private lateinit var repository: WebAppRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = DataBackupManager(context)
        repository = WebAppRepository(AppDatabase.getInstance(context).webAppDao())
        runBlocking { wipe() }
        File(context.filesDir, "aicoding").deleteRecursively()
        File(context.filesDir, "rollback_probe").deleteRecursively()
        LocalFileTransaction.defaultRollbackDir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { wipe() } }
        File(context.filesDir, "aicoding").deleteRecursively()
        File(context.filesDir, "rollback_probe").deleteRecursively()
        LocalFileTransaction.defaultRollbackDir(context).deleteRecursively()
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("wta_backup_") }
            ?.forEach { it.delete() }
    }

    private suspend fun wipe() {
        repository.allWebApps.first().forEach { repository.deleteWebApp(it) }
    }

    private fun archive(name: String): File =
        File(context.cacheDir, "${name}_${System.nanoTime()}.zip")

    @Test
    fun `a successful import restores apps and local files`() = runBlocking {
        val localFile = File(context.filesDir, "aicoding/probe.txt")
        localFile.parentFile?.mkdirs()
        localFile.writeText("state-at-backup-time")
        repository.createWebApp(
            WebApp(name = "Restored App", url = "https://example.com", appType = AppType.WEB)
        )

        val file = archive("success")
        assertThat(manager.exportAllData(repository, Uri.fromFile(file)).isSuccess).isTrue()

        // Everything the backup would put back changes before the restore.
        wipe()
        localFile.writeText("changed-after-backup")

        val result = manager.importAllData(repository, Uri.fromFile(file))

        assertThat(result.isSuccess).isTrue()
        val imported = result.getOrThrow()
        assertThat(imported.importedCount).isEqualTo(1)
        assertThat(imported.localFilesRestored).isTrue()
        assertThat(repository.allWebApps.first().single().name).isEqualTo("Restored App")
        assertThat(localFile.readText()).isEqualTo("state-at-backup-time")
    }

    @Test
    fun `an import that fails on a local file rolls back the database and the files`() = runBlocking {
        val kept = File(context.filesDir, "rollback_probe/keep.txt")
        kept.parentFile?.mkdirs()
        kept.writeText("keep-original")

        // A regular file where the archive expects a directory: the staged entry for
        // "rollback_probe/blocked/x.txt" cannot be published, and it is ordered after
        // the entry that *can* be, so the rollback has real work to do.
        val blocker = File(context.filesDir, "rollback_probe/blocked").apply { writeText("x") }

        val payload = mapOf(
            "local/files/rollback_probe/keep.txt" to "keep-restored".toByteArray(),
            "local/files/rollback_probe/blocked/x.txt" to "never-lands".toByteArray()
        )
        val appsJson = Converters.gson.toJson(
            DataBackupManager.BackupData(
                version = 6,
                appCount = 1,
                apps = listOf(WebApp(name = "Should Vanish", url = "https://vanish.example", appType = AppType.WEB))
            )
        ).toByteArray()

        val entries = payload.toMutableMap()
        entries["apps.json"] = appsJson
        val file = archive("rollback")
        writeArchiveWithManifest(file, entries)

        val result = manager.importAllData(repository, Uri.fromFile(file))

        assertThat(result.isFailure).isTrue()
        val failure = result.exceptionOrNull()
        assertThat(failure).isInstanceOf(BackupImportException::class.java)
        assertThat((failure as BackupImportException).rolledBack).isTrue()

        // Database compensation: the app the import created is gone again.
        assertThat(repository.allWebApps.first()).isEmpty()
        // File compensation: the entry that did land went back to its original bytes.
        assertThat(kept.readText()).isEqualTo("keep-original")
        assertThat(blocker.isFile).isTrue()
        assertThat(LocalFileTransaction.defaultRollbackDir(context).exists()).isFalse()
    }

    @Test
    fun `a tampered entry is rejected before anything is written`() = runBlocking {
        val kept = File(context.filesDir, "rollback_probe/keep.txt")
        kept.parentFile?.mkdirs()
        kept.writeText("keep-original")

        val appsJson = Converters.gson.toJson(
            DataBackupManager.BackupData(
                version = 6,
                appCount = 1,
                apps = listOf(WebApp(name = "Should Not Import", url = "https://nope.example", appType = AppType.WEB))
            )
        ).toByteArray()
        val file = archive("tampered")
        writeArchiveWithManifest(
            file,
            mapOf(
                "apps.json" to appsJson,
                "local/files/rollback_probe/keep.txt" to "keep-restored".toByteArray()
            ),
            // Manifest vouches for different bytes than the archive carries.
            tamperEntry = "local/files/rollback_probe/keep.txt"
        )

        val result = manager.importAllData(repository, Uri.fromFile(file))

        assertThat(result.isFailure).isTrue()
        val failure = result.exceptionOrNull()
        assertThat(failure).isInstanceOf(BackupImportException::class.java)
        // The digest, not the length, is what rejected it.
        assertThat(failure!!.cause).isInstanceOf(BackupIntegrityException.Mismatch::class.java)
        // Nothing live was touched: not the database, not the file.
        assertThat(repository.allWebApps.first()).isEmpty()
        assertThat(kept.readText()).isEqualTo("keep-original")
    }

    /**
     * Writes an archive whose manifest matches [entries]. When [tamperEntry] is set,
     * the manifest records the *original* bytes of that entry while the archive
     * carries altered ones — the exact case a size check alone would accept.
     */
    private fun writeArchiveWithManifest(
        file: File,
        entries: Map<String, ByteArray>,
        tamperEntry: String? = null
    ) {
        val fingerprints = entries.map { (path, bytes) ->
            // Same length, different bytes: exactly what a size check cannot catch.
            val recorded = if (path == tamperEntry) ByteArray(bytes.size) { 'X'.code.toByte() } else bytes
            BackupEntryFingerprint(path, recorded.size.toLong(), BackupIntegrity.hex(recorded))
        }
        val manifest = BackupManifest(6, System.currentTimeMillis(), fingerprints)

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toJson().toByteArray())
            zip.closeEntry()
        }
    }
}
