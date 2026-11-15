package com.sherwood.wta.core.frontend

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.studio.git.GitProviderId
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

    // ------------------------------------------------- provider provenance (v2)

    @Test
    fun `records the provider and host of the copy`() {
        val project = folder.newFolder("gitea")

        store.write(
            project,
            metadata().copy(provider = "gitea", host = "git.example.com")
        )

        val restored = store.read(project)!!
        assertThat(restored.provider).isEqualTo("gitea")
        assertThat(restored.providerId).isEqualTo(GitProviderId.GITEA)
        assertThat(restored.host).isEqualTo("git.example.com")
        assertThat(restored.serverUrl).isEqualTo("https://git.example.com")
    }

    @Test
    fun `an unknown provider is rejected instead of being read as github`() {
        val project = folder.newFolder("bogus")
        File(project, RepoBridgeMetadataStore.FILE_NAME).writeText(
            """{"schemaVersion":2,"provider":"bitbucket","host":"bitbucket.org",
               "owner":"o","repo":"r","ref":"main"}""",
            Charsets.UTF_8
        )

        assertThat(store.read(project)).isNull()
    }

    @Test
    fun `a schema 1 record is read as github and upgraded on disk`() {
        val project = folder.newFolder("legacy")
        val file = File(project, RepoBridgeMetadataStore.FILE_NAME)
        file.writeText(
            """{"schemaVersion":1,"host":"github.com","owner":"octocat",
               "repo":"hello-world","ref":"main","baseSha":"$SHA"}""",
            Charsets.UTF_8
        )

        val restored = store.read(project)

        requireNotNull(restored)
        assertThat(restored.schemaVersion).isEqualTo(RepoBridgeMetadata.SCHEMA_VERSION)
        // Schema 1 could only be written by the GitHub importer.
        assertThat(restored.provider).isEqualTo(RepoBridgeMetadata.PROVIDER_GITHUB)
        assertThat(restored.providerId).isEqualTo(GitProviderId.GITHUB)
        assertThat(restored.owner).isEqualTo("octocat")

        // The upgrade is persisted so the next open needs no migration step.
        val onDisk = file.readText(Charsets.UTF_8)
        assertThat(onDisk).contains("\"schemaVersion\":2")
        assertThat(onDisk).contains("\"provider\":\"github\"")
    }

    @Test
    fun `a host may carry a non-default port`() {
        val project = folder.newFolder("port")

        store.write(project, metadata().copy(provider = "gitea", host = "git.example.com:8443"))

        assertThat(store.read(project)!!.host).isEqualTo("git.example.com:8443")
    }

    companion object {
        private const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
