package com.sherwood.wta.core.studio.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Gitea/Forgejo publish path.
 *
 * The request body is the contract here: one atomic call, a new branch when one is
 * asked for, never a force flag, and a per-file blob sha on every update and delete so a
 * file changed remotely is refused rather than overwritten.
 */
class GiteaCommitWriterTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun writer(token: String? = TOKEN): GiteaCommitWriter = GiteaCommitWriter(
        GiteaApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { token },
            httpClient = OkHttpClient(),
            // MockWebServer speaks plain HTTP on localhost; production keeps the HTTPS rule.
            urlGuard = {}
        )
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun changes() = listOf(
        GitFileChange("added.txt", content = "hi".toByteArray()),
        GitFileChange("changed.txt", content = "bye".toByteArray(), baseSha = OLD_SHA),
        GitFileChange("removed.txt", content = null, baseSha = OLD_SHA)
    )

    // ------------------------------------------------------------------ publish

    @Test
    fun `a new branch is created in the same call and is never forced`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"commit":{"sha":"$NEW_SHA"},"files":[]}"""))

        val result = writer().publishNewBranch(
            ref = REF,
            baseBranch = "main",
            branch = "feat/red-logo",
            expectedBaseSha = BASE_SHA,
            message = "feat(brand): new logo",
            changes = changes()
        )

        assertThat(result).isInstanceOf(GitResult.Success::class.java)
        assertThat((result as GitResult.Success<GitPublishOutcome>).value.commitSha).isEqualTo(NEW_SHA)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/v1/repos/o/r/contents")
        assertThat(request.getHeader("Authorization")).isEqualTo("token $TOKEN")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"message\":\"feat(brand): new logo\"")
        // The base branch the diff was taken from, and the branch created from it.
        assertThat(body).contains("\"branch\":\"main\"")
        assertThat(body).contains("\"new_branch\":\"feat/red-logo\"")
        // Create, update (with the blob it was diffed against) and delete.
        assertThat(body).contains("\"path\":\"added.txt\",\"operation\":\"create\"")
        assertThat(body).contains("\"content\":\"aGk=\"")
        assertThat(body).contains("\"path\":\"changed.txt\",\"operation\":\"update\"")
        assertThat(body).contains("\"content\":\"Ynll\"")
        assertThat(body).contains("\"sha\":\"$OLD_SHA\"")
        assertThat(body).contains("\"path\":\"removed.txt\",\"operation\":\"delete\"")
        // Rule: no force, ever. The schema defaults it to false and this build never sends it.
        assertThat(body).doesNotContain("force_push")
    }

    @Test
    fun `publishing to an existing branch does not ask for a new one`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"commit":{"sha":"$NEW_SHA"},"files":[]}"""))

        val result = writer().publishToBranch(
            ref = REF,
            branch = "feat/red-logo",
            expectedBaseSha = BASE_SHA,
            message = "fix: tweak",
            changes = listOf(GitFileChange("a.txt", content = "x".toByteArray()))
        )

        assertThat(result).isInstanceOf(GitResult.Success::class.java)
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"branch\":\"feat/red-logo\"")
        assertThat(body).doesNotContain("new_branch")
        assertThat(body).doesNotContain("force_push")
    }

    @Test
    fun `a branch that already exists is reported as a conflict`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse("""{"message":"branch already exists [name: feat/x]"}""")
                .setResponseCode(422)
        )

        val result = writer().publishNewBranch(
            ref = REF,
            baseBranch = "main",
            branch = "feat/x",
            expectedBaseSha = BASE_SHA,
            message = "m",
            changes = listOf(GitFileChange("a.txt", content = "x".toByteArray()))
        )

        val failure = result as GitResult.Failure
        assertThat(failure.kind).isEqualTo(GitFailureKind.CONFLICT)
        assertThat(failure.message).contains("already exists")
    }

    @Test
    fun `a blob sha that no longer matches is reported as a conflict`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse(
                """{"message":"the SHA given does not match the current SHA for this file"}"""
            ).setResponseCode(422)
        )

        val result = writer().publishToBranch(
            ref = REF,
            branch = "main",
            expectedBaseSha = BASE_SHA,
            message = "m",
            changes = listOf(GitFileChange("a.txt", content = "x".toByteArray(), baseSha = OLD_SHA))
        )

        assertThat((result as GitResult.Failure).kind).isEqualTo(GitFailureKind.CONFLICT)
    }

    @Test
    fun `an oversized change set is refused before the network`() = runBlocking<Unit> {
        val many = (0 until 501).map {
            GitFileChange("f$it.txt", content = "x".toByteArray())
        }

        val result = writer().publishNewBranch(
            ref = REF,
            baseBranch = "main",
            branch = "feat/x",
            expectedBaseSha = BASE_SHA,
            message = "m",
            changes = many
        )

        assertThat((result as GitResult.Failure).kind)
            .isEqualTo(GitFailureKind.UNPROCESSABLE)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a file above the per-file ceiling is refused before the network`() = runBlocking<Unit> {
        val big = ByteArray((writer().publishLimits.maxFileBytes + 1).toInt())

        val result = writer().publishToBranch(
            ref = REF,
            branch = "main",
            expectedBaseSha = BASE_SHA,
            message = "m",
            changes = listOf(GitFileChange("big.bin", content = big))
        )

        assertThat((result as GitResult.Failure).kind)
            .isEqualTo(GitFailureKind.UNPROCESSABLE)
        assertThat(server.requestCount).isEqualTo(0)
    }

    // ---------------------------------------------------------------- base tree

    @Test
    fun `the base tree is read from the commit and paginated until complete`() =
        runBlocking<Unit> {
            server.enqueue(
                jsonResponse("""{"sha":"$BASE_SHA","commit":{"tree":{"sha":"$TREE_SHA"}}}""")
            )
            server.enqueue(
                jsonResponse(
                    """{"sha":"$TREE_SHA","page":1,"truncated":true,
                       "tree":[{"path":"a.txt","sha":"$OLD_SHA","mode":"100644","type":"blob"}]}"""
                )
            )
            server.enqueue(
                jsonResponse(
                    """{"sha":"$TREE_SHA","page":2,"truncated":false,
                       "tree":[{"path":"dir/b.txt","sha":"$OLD_SHA","mode":"100755","type":"blob"},
                               {"path":"dir","sha":"$OLD_SHA","mode":"040000","type":"tree"}]}"""
                )
            )

            val base = writer().baseTree(REF, BASE_SHA) as GitResult.Success<GitBaseTree>
            assertThat(base.value.commitSha).isEqualTo(BASE_SHA)
            assertThat(base.value.treeSha).isEqualTo(TREE_SHA)
            assertThat(base.value.snapshot.entries).hasSize(3)
            assertThat(base.value.snapshot.truncated).isFalse()
            assertThat(base.value.snapshot.entries[1].mode).isEqualTo("100755")

            assertThat(server.takeRequest().path)
                .isEqualTo("/api/v1/repos/o/r/git/commits/$BASE_SHA")
            val firstPage = server.takeRequest().path.orEmpty()
            assertThat(firstPage).contains("/git/trees/$TREE_SHA?")
            assertThat(firstPage).contains("recursive=true")
            assertThat(firstPage).contains("page=1")
            assertThat(server.takeRequest().path).contains("page=2")
        }

    // ------------------------------------------------------------ host limits

    @Test
    fun `the staged methods are unavailable and say why`() = runBlocking<Unit> {
        val writer = writer()

        val staged = writer.createCommit(REF, BASE_SHA, TREE_SHA, "m", emptyList())
        val moved = writer.moveBranch(REF, "feat/x", NEW_SHA, BASE_SHA)

        assertThat((staged as GitResult.Failure).kind).isEqualTo(GitFailureKind.UNSUPPORTED)
        assertThat((moved as GitResult.Failure).kind).isEqualTo(GitFailureKind.UNSUPPORTED)
        assertThat(writer.commitsInOneStep).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the published set is narrower than the shared default`() {
        val limits = writer().publishLimits

        assertThat(limits.maxFiles).isAtMost(GitCommitWriter.DEFAULT_PUBLISH_LIMITS.maxFiles)
        assertThat(limits.maxBytes).isAtMost(GitCommitWriter.DEFAULT_PUBLISH_LIMITS.maxBytes)
        assertThat(limits.maxFileBytes)
            .isAtMost(GitCommitWriter.DEFAULT_PUBLISH_LIMITS.maxFileBytes)
    }

    companion object {
        private val TOKEN = "a".repeat(40)
        private val REF = GitRepoRef.parse("o/r")!!
        private const val BASE_SHA = "1111111111111111111111111111111111111111"
        private const val NEW_SHA = "2222222222222222222222222222222222222222"
        private const val OLD_SHA = "3333333333333333333333333333333333333333"
        private const val TREE_SHA = "4444444444444444444444444444444444444444"
    }
}
