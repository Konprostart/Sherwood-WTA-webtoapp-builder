package com.sherwood.wta.core.frontend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepoBridgeCommitUseCaseTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var projectDir: File
    private lateinit var metadataStore: RepoBridgeMetadataStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        projectDir = temporaryFolder.newFolder("project")
        metadataStore = RepoBridgeMetadataStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val apiBase: String get() = server.url("/").toString().trimEnd('/')

    private fun useCase(token: String? = TOKEN): RepoBridgeCommitUseCase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return RepoBridgeCommitUseCase(
            context = context,
            projectDir = projectDir,
            client = GitDataApiClient(
                context = context,
                tokenProvider = { token },
                httpClient = OkHttpClient(),
                apiBaseUrl = apiBase,
                urlGuard = {}
            ),
            metadataStore = metadataStore
        )
    }

    private fun writeMetadata(baseSha: String? = BASE, subPath: String? = null) {
        metadataStore.write(
            projectDir,
            RepoBridgeMetadata(
                host = RepoBridgeMetadata.HOST_GITHUB,
                owner = OWNER,
                repo = REPO,
                ref = "main",
                baseSha = baseSha,
                subPath = subPath
            )
        )
    }

    private fun writeFile(path: String, content: String) {
        val file = File(projectDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun blobEntry(path: String, content: String): String =
        """{"path":"$path","mode":"100644","type":"blob","sha":"${gitBlobSha(content)}"}"""

    private fun enqueueBase(vararg entries: String, truncated: Boolean = false) {
        server.enqueue(ok("""{"sha":"$BASE","tree":{"sha":"$BASE_TREE"}}"""))
        server.enqueue(
            ok(
                """{"sha":"$BASE_TREE","tree":[${entries.joinToString(",")}],""" +
                    """"truncated":$truncated}"""
            )
        )
    }

    private fun enqueueWrites(blobShas: List<String>) {
        blobShas.forEach { server.enqueue(ok("""{"sha":"$it"}""")) }
        server.enqueue(ok("""{"sha":"$NEW_TREE"}"""))
        server.enqueue(ok("""{"sha":"$NEW_COMMIT"}"""))
        server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$BASE"}}"""))
        server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$NEW_COMMIT"}}"""))
    }

    @Test
    fun `prepare compares the working copy with the remote tree`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        writeFile("edit.txt", "new")
        writeFile("new.txt", "hello")
        enqueueBase(
            blobEntry("keep.txt", "same"),
            blobEntry("edit.txt", "old"),
            blobEntry("old.txt", "bye")
        )

        val prepared = useCase().prepare() as RepoBridgePrepareResult.Ready

        assertThat(prepared.owner).isEqualTo(OWNER)
        assertThat(prepared.repo).isEqualTo(REPO)
        assertThat(prepared.branch).isEqualTo("main")
        assertThat(prepared.baseSha).isEqualTo(BASE)
        assertThat(prepared.changes.added).containsExactly("new.txt")
        assertThat(prepared.changes.modified).containsExactly("edit.txt")
        assertThat(prepared.changes.deleted).containsExactly("old.txt")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `commit uploads blobs then creates tree commit and advances the ref`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        writeFile("edit.txt", "new")
        writeFile("new.txt", "hello")
        enqueueBase(
            blobEntry("keep.txt", "same"),
            blobEntry("edit.txt", "old"),
            blobEntry("old.txt", "bye")
        )
        enqueueWrites(listOf(BLOB_NEW, BLOB_EDIT))

        val useCase = useCase()
        assertThat(useCase.prepare()).isInstanceOf(RepoBridgePrepareResult.Ready::class.java)
        val result = useCase.commit("Update files")

        val success = result as RepoBridgeCommitResult.Success
        assertThat(success.commitSha).isEqualTo(NEW_COMMIT)
        assertThat(success.changedFiles).isEqualTo(3)
        assertThat(success.metadataSaved).isTrue()
        assertThat(metadataStore.read(projectDir)?.baseSha).isEqualTo(NEW_COMMIT)

        assertThat(server.takeRequest().path)
            .isEqualTo("/repos/$OWNER/$REPO/git/commits/$BASE")
        assertThat(server.takeRequest().path)
            .isEqualTo("/repos/$OWNER/$REPO/git/trees/$BASE_TREE?recursive=1")

        val firstBlob = server.takeRequest()
        assertThat(firstBlob.path).isEqualTo("/repos/$OWNER/$REPO/git/blobs")
        assertThat(blobContent(firstBlob.body.readUtf8()))
            .isEqualTo(Base64.getEncoder().encodeToString("hello".toByteArray()))

        val secondBlob = server.takeRequest()
        assertThat(blobContent(secondBlob.body.readUtf8()))
            .isEqualTo(Base64.getEncoder().encodeToString("new".toByteArray()))

        val treeRequest = server.takeRequest()
        assertThat(treeRequest.path).isEqualTo("/repos/$OWNER/$REPO/git/trees")
        val treeJson = JsonParser.parseString(treeRequest.body.readUtf8()).asJsonObject
        assertThat(treeJson.get("base_tree").asString).isEqualTo(BASE_TREE)
        val entries = treeJson.getAsJsonArray("tree")
            .map { it.asJsonObject }
            .map { entry ->
                entry.get("path").asString to entry
            }
        assertThat(entries.map { it.first }).containsExactly("new.txt", "edit.txt", "old.txt")
        assertThat(entries.first { it.first == "new.txt" }.second.get("sha").asString).isEqualTo(BLOB_NEW)
        assertThat(entries.first { it.first == "edit.txt" }.second.get("sha").asString).isEqualTo(BLOB_EDIT)
        val deletion = entries.first { it.first == "old.txt" }.second
        assertThat(deletion.has("sha")).isTrue()
        assertThat(deletion.get("sha").isJsonNull).isTrue()

        val commitRequest = server.takeRequest()
        assertThat(commitRequest.path).isEqualTo("/repos/$OWNER/$REPO/git/commits")
        val commitJson = JsonParser.parseString(commitRequest.body.readUtf8()).asJsonObject
        assertThat(commitJson.get("message").asString).isEqualTo("Update files")
        assertThat(commitJson.get("tree").asString).isEqualTo(NEW_TREE)
        assertThat(commitJson.getAsJsonArray("parents").first().asString).isEqualTo(BASE)

        assertThat(server.requestCount).isEqualTo(8)
    }

    @Test
    fun `identical working copy reports no changes and writes nothing`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        enqueueBase(blobEntry("keep.txt", "same"))

        val useCase = useCase()
        val prepared = useCase.prepare() as RepoBridgePrepareResult.Ready
        assertThat(prepared.changes.isEmpty).isTrue()

        val result = useCase.commit("No-op") as RepoBridgeCommitResult.Failure
        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.NO_CHANGES)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `blank commit message fails before any request`() = runBlocking<Unit> {
        writeMetadata()

        val result = useCase().commit("   ") as RepoBridgeCommitResult.Failure

        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.NO_COMMIT_MESSAGE)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `missing metadata fails locally`() = runBlocking<Unit> {
        writeFile("index.html", "<h1>hi</h1>")

        val result = useCase().prepare() as RepoBridgePrepareResult.Failure

        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.NO_METADATA)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `unknown base commit fails before contacting GitHub`() = runBlocking<Unit> {
        writeMetadata(baseSha = null)

        val result = useCase().prepare() as RepoBridgePrepareResult.Failure

        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.NO_BASE)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `missing token fails during prepare without a request`() = runBlocking<Unit> {
        writeMetadata()

        val result = useCase(token = null).prepare() as RepoBridgePrepareResult.Failure

        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.NO_TOKEN)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `moved branch fails the push and leaves metadata untouched`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("edit.txt", "new")
        enqueueBase(blobEntry("edit.txt", "old"))
        server.enqueue(ok("""{"sha":"$BLOB_EDIT"}"""))
        server.enqueue(ok("""{"sha":"$NEW_TREE"}"""))
        server.enqueue(ok("""{"sha":"$NEW_COMMIT"}"""))
        server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$MOVED"}}"""))

        val useCase = useCase()
        useCase.prepare()
        val result = useCase.commit("Edit") as RepoBridgeCommitResult.Failure

        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.CONFLICT)
        assertThat(metadataStore.read(projectDir)?.baseSha).isEqualTo(BASE)
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test
    fun `sub path only diffs and pushes files below that directory`() = runBlocking<Unit> {
        writeMetadata(subPath = "app")
        writeFile("index.html", "new")
        writeFile("keep.txt", "same")
        enqueueBase(
            blobEntry("app/index.html", "old"),
            blobEntry("app/keep.txt", "same"),
            blobEntry("README.md", "readme")
        )
        enqueueWrites(listOf(BLOB_EDIT))

        val useCase = useCase()
        val prepared = useCase.prepare() as RepoBridgePrepareResult.Ready
        assertThat(prepared.changes.added).isEmpty()
        assertThat(prepared.changes.modified).containsExactly("app/index.html")
        assertThat(prepared.changes.deleted).isEmpty()

        assertThat(useCase.commit("Edit index")).isInstanceOf(RepoBridgeCommitResult.Success::class.java)

        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        val treeJson = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        val paths = treeJson.getAsJsonArray("tree").map { it.asJsonObject.get("path").asString }
        assertThat(paths).containsExactly("app/index.html")
        assertThat(server.requestCount).isEqualTo(7)
    }

    @Test
    fun `truncated remote tree is rejected instead of pushing a partial diff`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("index.html", "hi")
        enqueueBase(blobEntry("index.html", "old"), truncated = true)

        val result = useCase().prepare() as RepoBridgePrepareResult.Failure

        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.REMOTE_REJECTED)
    }

    @Test
    fun `deleting a file sends an explicit null sha and nothing else`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        enqueueBase(
            blobEntry("keep.txt", "same"),
            blobEntry("old.txt", "bye")
        )
        server.enqueue(ok("""{"sha":"$NEW_TREE"}"""))
        server.enqueue(ok("""{"sha":"$NEW_COMMIT"}"""))
        server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$BASE"}}"""))
        server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$NEW_COMMIT"}}"""))

        val useCase = useCase()
        assertThat(useCase.prepare()).isInstanceOf(RepoBridgePrepareResult.Ready::class.java)
        val result = useCase.commit("Drop old file") as RepoBridgeCommitResult.Success
        assertThat(result.changedFiles).isEqualTo(1)

        server.takeRequest()
        server.takeRequest()
        val treeRequest = server.takeRequest()
        val treeJson = JsonParser.parseString(treeRequest.body.readUtf8()).asJsonObject
        val entries = treeJson.getAsJsonArray("tree").map { it.asJsonObject }
        assertThat(entries).hasSize(1)
        assertThat(entries.single().get("path").asString).isEqualTo("old.txt")
        assertThat(entries.single().has("sha")).isTrue()
        assertThat(entries.single().get("sha").isJsonNull).isTrue()
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test
    fun `unchanged oversized file does not block the commit`() = runBlocking<Unit> {
        writeMetadata()
        val oversized = "x".repeat((RepoBridgeCommitUseCase.MAX_FILE_BYTES + 1L).toInt())
        writeFile("huge.bin", oversized)
        writeFile("edit.txt", "new")
        enqueueBase(
            blobEntry("huge.bin", oversized),
            blobEntry("edit.txt", "old")
        )
        enqueueWrites(listOf(BLOB_EDIT))

        val useCase = useCase()
        val prepared = useCase.prepare() as RepoBridgePrepareResult.Ready
        assertThat(prepared.changes.added).isEmpty()
        assertThat(prepared.changes.modified).containsExactly("edit.txt")
        assertThat(prepared.changes.deleted).isEmpty()

        val result = useCase.commit("Edit") as RepoBridgeCommitResult.Success
        assertThat(result.changedFiles).isEqualTo(1)
    }

    @Test
    fun `changed oversized file fails before uploading anything`() = runBlocking<Unit> {
        writeMetadata()
        val oversized = "x".repeat((RepoBridgeCommitUseCase.MAX_FILE_BYTES + 1L).toInt())
        writeFile("huge.bin", oversized)
        enqueueBase(blobEntry("huge.bin", "small"))

        val result = useCase().prepare() as RepoBridgePrepareResult.Failure

        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.FILE_TOO_LARGE)
        assertThat(result.detail).contains("huge.bin")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `unexpected failure during prepare is reported instead of crashing`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("index.html", "hi")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val broken = RepoBridgeCommitUseCase(
            context = context,
            projectDir = projectDir,
            client = GitDataApiClient(
                context = context,
                tokenProvider = { throw IllegalStateException("boom") },
                httpClient = OkHttpClient(),
                apiBaseUrl = apiBase,
                urlGuard = {}
            ),
            metadataStore = metadataStore
        )

        val result = broken.prepare() as RepoBridgePrepareResult.Failure

        assertThat(result.reason).isEqualTo(RepoBridgeCommitFailureKind.UNEXPECTED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun blobContent(body: String): String =
        JsonParser.parseString(body).asJsonObject.get("content").asString

    private fun gitBlobSha(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(bytes)
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    @Test
    fun `prepare reports how many remote files the branch holds`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        enqueueBase(
            blobEntry("keep.txt", "same"),
            blobEntry("a.txt", "a"),
            blobEntry("b.txt", "b")
        )

        val prepared = useCase().prepare() as RepoBridgePrepareResult.Ready

        assertThat(prepared.remoteFileCount).isEqualTo(3)
        assertThat(prepared.changes.deleted).containsExactly("a.txt", "b.txt")
    }

    @Test
    fun `mass deletion is refused without confirmation and nothing is uploaded`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        enqueueBase(
            blobEntry("keep.txt", "same"),
            blobEntry("a.txt", "a"),
            blobEntry("b.txt", "b")
        )

        val result = useCase().commit("remove most files")

        assertThat(result).isInstanceOf(RepoBridgeCommitResult.Failure::class.java)
        assertThat((result as RepoBridgeCommitResult.Failure).reason)
            .isEqualTo(RepoBridgeCommitFailureKind.MASS_DELETION_UNCONFIRMED)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `an empty working copy against a non-empty remote is a mass deletion`() = runBlocking<Unit> {
        writeMetadata()
        enqueueBase(blobEntry("index.html", "x"), blobEntry("app.js", "y"))

        val result = useCase().commit("oops")

        assertThat((result as RepoBridgeCommitResult.Failure).reason)
            .isEqualTo(RepoBridgeCommitFailureKind.MASS_DELETION_UNCONFIRMED)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `mass deletion goes through when the user confirmed the same count`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        enqueueBase(
            blobEntry("keep.txt", "same"),
            blobEntry("a.txt", "a"),
            blobEntry("b.txt", "b")
        )
        enqueueWrites(emptyList())

        val result = useCase().commit("remove most files", confirmedDeletions = 2)

        assertThat(result).isInstanceOf(RepoBridgeCommitResult.Success::class.java)
    }

    @Test
    fun `confirmation smaller than the real deletion count is refused`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        enqueueBase(
            blobEntry("keep.txt", "same"),
            blobEntry("a.txt", "a"),
            blobEntry("b.txt", "b")
        )

        val result = useCase().commit("remove most files", confirmedDeletions = 1)

        assertThat((result as RepoBridgeCommitResult.Failure).reason)
            .isEqualTo(RepoBridgeCommitFailureKind.MASS_DELETION_UNCONFIRMED)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `deleting exactly half of the files is not a mass deletion`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        writeFile("a.txt", "a")
        enqueueBase(
            blobEntry("keep.txt", "same"),
            blobEntry("a.txt", "a"),
            blobEntry("b.txt", "b"),
            blobEntry("c.txt", "c")
        )
        enqueueWrites(emptyList())

        val result = useCase().commit("drop two of four")

        assertThat(result).isInstanceOf(RepoBridgeCommitResult.Success::class.java)
    }

    private fun modeEntry(path: String, content: String, mode: String): String =
        """{"path":"$path","mode":"$mode","type":"blob","sha":"${gitBlobSha(content)}"}"""

    /** Maps each path in a tree request body to its mode. */
    private fun modesSentIn(request: RecordedRequest): Map<String, String> {
        val tree = JsonParser.parseString(request.body.readUtf8())
            .asJsonObject.getAsJsonArray("tree")
        return tree.associate { entry ->
            val obj = entry.asJsonObject
            obj.get("path").asString to obj.get("mode").asString
        }
    }

    @Test
    fun `modified executable and symlink keep the mode they have on GitHub`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("run.sh", "echo new")
        writeFile("link", "target-new")
        server.enqueue(ok("""{"sha":"$BASE","tree":{"sha":"$BASE_TREE"}}"""))
        server.enqueue(
            ok(
                """{"sha":"$BASE_TREE","tree":[${modeEntry("run.sh", "echo old", "100755")},""" +
                    """${modeEntry("link", "target-old", "120000")}],"truncated":false}"""
            )
        )
        enqueueWrites(listOf(BLOB_EDIT, BLOB_NEW))

        val result = useCase().commit("Keep modes")

        assertThat(result).isInstanceOf(RepoBridgeCommitResult.Success::class.java)
        repeat(4) { server.takeRequest() } // base commit, base tree, two blobs
        val treeRequest = server.takeRequest()
        assertThat(modesSentIn(treeRequest)).containsExactly(
            "run.sh", "100755",
            "link", "120000"
        )
    }

    @Test
    fun `a new file is pushed as a regular file`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("keep.txt", "same")
        writeFile("fresh.sh", "echo hi")
        enqueueBase(blobEntry("keep.txt", "same"))
        enqueueWrites(listOf(BLOB_NEW))

        useCase().commit("Add script")

        repeat(3) { server.takeRequest() } // base commit, base tree, blob
        assertThat(modesSentIn(server.takeRequest())).containsExactly("fresh.sh", "100644")
    }

    @Test
    fun `a pending base that the branch already points at is adopted`() = runBlocking<Unit> {
        writeMetadata()
        metadataStore.write(
            projectDir,
            metadataStore.read(projectDir)!!.copy(pendingBaseSha = NEW_COMMIT)
        )
        writeFile("keep.txt", "same")
        server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$NEW_COMMIT"}}"""))
        server.enqueue(ok("""{"sha":"$NEW_COMMIT","tree":{"sha":"$NEW_TREE"}}"""))
        server.enqueue(
            ok(
                """{"sha":"$NEW_TREE","tree":[${blobEntry("keep.txt", "same")}],"truncated":false}"""
            )
        )

        val prepared = useCase().prepare() as RepoBridgePrepareResult.Ready

        assertThat(prepared.baseSha).isEqualTo(NEW_COMMIT)
        assertThat(prepared.changes.isEmpty).isTrue()
        val saved = metadataStore.read(projectDir)!!
        assertThat(saved.baseSha).isEqualTo(NEW_COMMIT)
        assertThat(saved.pendingBaseSha).isNull()
    }

    @Test
    fun `a stale pending base is dropped when the branch still points at the old base`() =
        runBlocking<Unit> {
            writeMetadata()
            metadataStore.write(
                projectDir,
                metadataStore.read(projectDir)!!.copy(pendingBaseSha = NEW_COMMIT)
            )
            writeFile("keep.txt", "same")
            server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$BASE"}}"""))
            enqueueBase(blobEntry("keep.txt", "same"))

            val prepared = useCase().prepare() as RepoBridgePrepareResult.Ready

            assertThat(prepared.baseSha).isEqualTo(BASE)
            val saved = metadataStore.read(projectDir)!!
            assertThat(saved.baseSha).isEqualTo(BASE)
            assertThat(saved.pendingBaseSha).isNull()
        }

    @Test
    fun `a successful push records the new base and clears the pending sha`() = runBlocking<Unit> {
        writeMetadata()
        writeFile("edit.txt", "new")
        enqueueBase(blobEntry("edit.txt", "old"))
        enqueueWrites(listOf(BLOB_EDIT))

        val result = useCase().commit("Edit")

        assertThat(result).isInstanceOf(RepoBridgeCommitResult.Success::class.java)
        val saved = metadataStore.read(projectDir)!!
        assertThat(saved.baseSha).isEqualTo(NEW_COMMIT)
        assertThat(saved.pendingBaseSha).isNull()
    }

    private fun ok(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    companion object {
        private const val OWNER = "octocat"
        private const val REPO = "hello-world"

        private val TOKEN = "ghp_" + "a".repeat(36)

        private val BASE = "1".repeat(40)
        private val BASE_TREE = "2".repeat(40)
        private val BLOB_NEW = "3".repeat(40)
        private val BLOB_EDIT = "4".repeat(40)
        private val NEW_TREE = "5".repeat(40)
        private val NEW_COMMIT = "6".repeat(40)
        private val MOVED = "7".repeat(40)
    }
}
