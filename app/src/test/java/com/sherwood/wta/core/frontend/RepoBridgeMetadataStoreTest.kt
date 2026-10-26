package com.sherwood.wta.core.frontend

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepoBridgeMetadataStoreTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    private val store = RepoBridgeMetadataStore()

    private fun metadata(
        owner: String = "octocat",
        repo: String = "hello-world",
        ref: String = "main",
        baseSha: String? = SHA,
        subPath: String? = null
    ) = RepoBridgeMetadata(
        host = RepoBridgeMetadata.HOST_GITHUB,
        owner = owner,
        repo = repo,
        ref = ref,
        baseSha = baseSha,
        subPath = subPath
    )

    @Test
    fun `round trips metadata through the project folder`() {
        val project = folder.newFolder("project")

        val file = store.write(project, metadata(subPath = "web"))

        assertThat(file.name).isEqualTo("repo_bridge.json")
        assertThat(file.parentFile).isEqualTo(project)

        val restored = store.read(project)
        assertThat(restored).isNotNull()
        assertThat(restored!!.host).isEqualTo(RepoBridgeMetadata.HOST_GITHUB)
        assertThat(restored.owner).isEqualTo("octocat")
        assertThat(restored.repo).isEqualTo("hello-world")
        assertThat(restored.ref).isEqualTo("main")
        assertThat(restored.baseSha).isEqualTo(SHA)
        assertThat(restored.subPath).isEqualTo("web")
        assertThat(restored.importedAtEpochMs).isGreaterThan(0L)
    }

    @Test
    fun `second write replaces the first without leaving a temp file`() {
        val project = folder.newFolder("project")

        store.write(project, metadata(owner = "first"))
        store.write(project, metadata(owner = "second"))

        assertThat(store.read(project)!!.owner).isEqualTo("second")
        assertThat(File(project, "repo_bridge.json.tmp").exists()).isFalse()
    }

    @Test
    fun `missing metadata returns null`() {
        val project = folder.newFolder("empty")

        assertThat(store.exists(project)).isFalse()
        assertThat(store.read(project)).isNull()
    }

    @Test
    fun `corrupt or incomplete json returns null instead of throwing`() {
        val project = folder.newFolder("project")
        val file = File(project, RepoBridgeMetadataStore.FILE_NAME)

        file.writeText("{not json", Charsets.UTF_8)
        assertThat(store.read(project)).isNull()

        file.writeText("""{"host":"github.com"}""", Charsets.UTF_8)
        assertThat(store.read(project)).isNull()
    }

    @Test
    fun `metadata without a resolved commit is valid`() {
        val project = folder.newFolder("project")

        store.write(project, metadata(baseSha = null))

        assertThat(store.read(project)!!.baseSha).isNull()
    }

    @Test
    fun `invalid metadata is rejected before touching the disk`() {
        val project = folder.newFolder("project")

        assertThrows(IllegalArgumentException::class.java) {
            store.write(project, metadata(owner = ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(project, metadata(baseSha = "not-a-sha"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(project, metadata(subPath = "../escape"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(project, metadata(ref = "main\ninjected"))
        }
        assertThat(store.exists(project)).isFalse()
    }

    @Test
    fun `write requires an existing project folder`() {
        assertThrows(IllegalArgumentException::class.java) {
            store.write(File(folder.root, "missing"), metadata())
        }
    }

    @Test
    fun `sha validation accepts only 40 lowercase hex characters`() {
        assertThat(RepoBridgeMetadata.isSha(SHA)).isTrue()
        assertThat(RepoBridgeMetadata.isSha(SHA.uppercase())).isFalse()
        assertThat(RepoBridgeMetadata.isSha(SHA.take(39))).isFalse()
        assertThat(RepoBridgeMetadata.isSha("g".repeat(40))).isFalse()
    }

    companion object {
        private const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
