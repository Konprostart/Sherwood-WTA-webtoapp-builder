package com.sherwood.wta.core.frontend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitDataApiClientTest {

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

    private val apiBase: String get() = server.url("/").toString().trimEnd('/')

    private fun client(token: String? = TOKEN) = GitDataApiClient(
        context = ApplicationProvider.getApplicationContext<Context>(),
        tokenProvider = { token },
        httpClient = OkHttpClient(),
        apiBaseUrl = apiBase,
        // MockWebServer speaks plain HTTP on localhost; production keeps the strict rule.
        urlGuard = {}
    )

    @Test
    fun `full chain reads base tree then creates blob tree commit and updates the ref`() =
        runBlocking {
            server.enqueue(ok("""{"sha":"$BASE_COMMIT","tree":{"sha":"$BASE_TREE"}}"""))
            server.enqueue(ok("""{"sha":"$BLOB_SHA"}"""))
            server.enqueue(ok("""{"sha":"$NEW_TREE"}"""))
            server.enqueue(ok("""{"sha":"$NEW_COMMIT"}"""))
            server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$NEW_COMMIT"}}"""))

            val api = client()

            val base = api.getCommit(OWNER, REPO, BASE_COMMIT)
            assertThat(base).isEqualTo(GitDataResult.Success(GitCommitInfo(BASE_COMMIT, BASE_TREE)))

            val blob = api.createBlob(OWNER, REPO, "hello".toByteArray())
            assertThat(blob).isEqualTo(GitDataResult.Success(BLOB_SHA))

            val tree = api.createTree(
                OWNER,
                REPO,
                BASE_TREE,
                listOf(GitTreeEntry("docs/a.txt", BLOB_SHA))
            )
            assertThat(tree).isEqualTo(GitDataResult.Success(NEW_TREE))

            val commit = api.createCommit(OWNER, REPO, "Update docs", NEW_TREE, BASE_COMMIT)
            assertThat(commit).isEqualTo(GitDataResult.Success(NEW_COMMIT))

            val ref = api.updateRef(OWNER, REPO, "main", NEW_COMMIT)
            assertThat(ref).isEqualTo(
                GitDataResult.Success(GitRef("refs/heads/main", NEW_COMMIT))
            )

            val baseRequest = server.takeRequest()
            assertThat(baseRequest.method).isEqualTo("GET")
            assertThat(baseRequest.path).isEqualTo("/repos/$OWNER/$REPO/git/commits/$BASE_COMMIT")

            val blobRequest = server.takeRequest()
            assertThat(blobRequest.method).isEqualTo("POST")
            assertThat(blobRequest.path).isEqualTo("/repos/$OWNER/$REPO/git/blobs")
            val blobJson = JsonParser.parseString(blobRequest.body.readUtf8()).asJsonObject
            assertThat(blobJson.get("content").asString).isEqualTo("aGVsbG8=")
            assertThat(blobJson.get("encoding").asString).isEqualTo("base64")

            val treeRequest = server.takeRequest()
            assertThat(treeRequest.method).isEqualTo("POST")
            assertThat(treeRequest.path).isEqualTo("/repos/$OWNER/$REPO/git/trees")
            val treeJson = JsonParser.parseString(treeRequest.body.readUtf8()).asJsonObject
            assertThat(treeJson.get("base_tree").asString).isEqualTo(BASE_TREE)
            val entry = treeJson.getAsJsonArray("tree").first().asJsonObject
            assertThat(entry.get("path").asString).isEqualTo("docs/a.txt")
            assertThat(entry.get("mode").asString).isEqualTo("100644")
            assertThat(entry.get("type").asString).isEqualTo("blob")
            assertThat(entry.get("sha").asString).isEqualTo(BLOB_SHA)

            val commitRequest = server.takeRequest()
            assertThat(commitRequest.method).isEqualTo("POST")
            assertThat(commitRequest.path).isEqualTo("/repos/$OWNER/$REPO/git/commits")
            val commitJson = JsonParser.parseString(commitRequest.body.readUtf8()).asJsonObject
            assertThat(commitJson.get("message").asString).isEqualTo("Update docs")
            assertThat(commitJson.get("tree").asString).isEqualTo(NEW_TREE)
            assertThat(commitJson.getAsJsonArray("parents").first().asString).isEqualTo(BASE_COMMIT)

            val refRequest = server.takeRequest()
            assertThat(refRequest.method).isEqualTo("PATCH")
            assertThat(refRequest.path).isEqualTo("/repos/$OWNER/$REPO/git/refs/heads/main")
            val refJson = JsonParser.parseString(refRequest.body.readUtf8()).asJsonObject
            assertThat(refJson.get("sha").asString).isEqualTo(NEW_COMMIT)
            assertThat(refJson.get("force").asBoolean).isFalse()

            assertThat(server.requestCount).isEqualTo(5)
        }

    @Test
    fun `every request carries the bearer token and api headers`() = runBlocking {
        server.enqueue(ok("""{"sha":"$BASE_COMMIT","tree":{"sha":"$BASE_TREE"}}"""))

        client().getCommit(OWNER, REPO, BASE_COMMIT)

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer $TOKEN")
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github+json")
        assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28")
    }

    @Test
    fun `401 maps to UNAUTHORIZED and never echoes the token`() = runBlocking {
        server.enqueue(error(401, """{"message":"Bad credentials"}"""))

        val result = client().createBlob(OWNER, REPO, "hello".toByteArray())

        assertThat(result.failure().kind).isEqualTo(GitDataFailureKind.UNAUTHORIZED)
        assertThat(result.failure().statusCode).isEqualTo(401)
        assertThat(result.failure().message).doesNotContain(TOKEN)
    }

    @Test
    fun `exhausted rate limit maps to RATE_LIMITED`() = runBlocking {
        server.enqueue(
            error(403, """{"message":"API rate limit exceeded"}""")
                .setHeader("x-ratelimit-remaining", "0")
        )

        val result = client().createBlob(OWNER, REPO, "hello".toByteArray())

        assertThat(result.failure().kind).isEqualTo(GitDataFailureKind.RATE_LIMITED)
        assertThat(result.failure().statusCode).isEqualTo(403)
    }

    @Test
    fun `409 on ref update maps to CONFLICT`() = runBlocking {
        server.enqueue(error(409, """{"message":"Reference does not exist"}"""))

        val result = client().updateRef(OWNER, REPO, "main", NEW_COMMIT)

        assertThat(result.failure().kind).isEqualTo(GitDataFailureKind.CONFLICT)
        assertThat(result.failure().statusCode).isEqualTo(409)
    }

    @Test
    fun `422 on tree creation maps to UNPROCESSABLE`() = runBlocking {
        server.enqueue(error(422, """{"message":"Tree SHA does not exist"}"""))

        val result = client().createTree(OWNER, REPO, BASE_TREE, listOf(GitTreeEntry("a.txt", BLOB_SHA)))

        assertThat(result.failure().kind).isEqualTo(GitDataFailureKind.UNPROCESSABLE)
        assertThat(result.failure().statusCode).isEqualTo(422)
    }

    @Test
    fun `422 fast forward rejection on ref update maps to CONFLICT`() = runBlocking {
        server.enqueue(error(422, """{"message":"Update is not a fast forward"}"""))

        val result = client().updateRef(OWNER, REPO, "main", NEW_COMMIT)

        assertThat(result.failure().kind).isEqualTo(GitDataFailureKind.CONFLICT)
        assertThat(result.failure().statusCode).isEqualTo(422)
    }

    @Test
    fun `stale expected base fails before any write request`() = runBlocking {
        server.enqueue(ok("""{"ref":"refs/heads/main","object":{"sha":"$MOVED"}}"""))

        val result = client().updateRef(
            OWNER,
            REPO,
            "main",
            NEW_COMMIT,
            expectedBaseSha = BASE_COMMIT
        )

        assertThat(result.failure().kind).isEqualTo(GitDataFailureKind.CONFLICT)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().method).isEqualTo("GET")
    }

    @Test
    fun `unreachable server maps to NETWORK`() = runBlocking {
        server.shutdown()

        val result = client().createBlob(OWNER, REPO, "hello".toByteArray())

        assertThat(result.failure().kind).isEqualTo(GitDataFailureKind.NETWORK)
    }

    @Test
    fun `missing token fails locally without a request`() = runBlocking {
        val result = client(token = null).createCommit(OWNER, REPO, "msg", NEW_TREE, BASE_COMMIT)

        assertThat(result.failure().kind).isEqualTo(GitDataFailureKind.UNAUTHORIZED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun ok(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun GitDataResult<*>.failure(): GitDataResult.Failure {
        assertThat(this).isInstanceOf(GitDataResult.Failure::class.java)
        return this as GitDataResult.Failure
    }

    companion object {
        private const val OWNER = "octocat"
        private const val REPO = "hello-world"

        private val TOKEN = "ghp_" + "a".repeat(36)

        private val BASE_COMMIT = "1".repeat(40)
        private val BASE_TREE = "2".repeat(40)
        private val BLOB_SHA = "3".repeat(40)
        private val NEW_TREE = "4".repeat(40)
        private val NEW_COMMIT = "5".repeat(40)
        private val MOVED = "6".repeat(40)
    }
}
