package com.sherwood.wta.core.frontend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
class GitHubTokenProbeTest {

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

    private fun fetcher() = GitHubRepoFetcher(
        context = ApplicationProvider.getApplicationContext<Context>(),
        tokenProvider = { null },
        httpClient = OkHttpClient(),
        apiBaseUrl = apiBase,
        archiveBaseUrl = apiBase,
        // MockWebServer speaks plain HTTP on localhost; production keeps the strict rule.
        urlGuard = {}
    )

    @Test
    fun `200 OK resolves the login and sends the bearer token to GET user`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"login": "testuser", "id": 1}""")
        )

        val result = fetcher().probeToken(TOKEN)

        assertThat(result).isEqualTo(GitHubTokenProbeResult.Success("testuser"))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/user")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer $TOKEN")
    }

    @Test
    fun `401 Unauthorized maps to Unauthorized`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message": "Bad credentials"}""")
        )

        val result = fetcher().probeToken(TOKEN)

        assertThat(result).isEqualTo(GitHubTokenProbeResult.Unauthorized)
    }

    @Test
    fun `403 maps to RateLimited`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("x-ratelimit-remaining", "0")
                .setBody("""{"message": "API rate limit exceeded"}""")
        )

        val result = fetcher().probeToken(TOKEN)

        assertThat(result).isEqualTo(GitHubTokenProbeResult.RateLimited)
    }

    @Test
    fun `malformed 200 body maps to UnexpectedResponse`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id": 1}"""))

        val result = fetcher().probeToken(TOKEN)

        assertThat(result).isEqualTo(GitHubTokenProbeResult.UnexpectedResponse)
    }

    @Test
    fun `malformed token is rejected locally without any request`() = runBlocking {
        val result = fetcher().probeToken("not a token")

        assertThat(result).isEqualTo(GitHubTokenProbeResult.InvalidToken)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `unreachable server maps to NetworkError`() = runBlocking {
        server.shutdown()

        val result = fetcher().probeToken(TOKEN)

        assertThat(result).isEqualTo(GitHubTokenProbeResult.NetworkError)
    }

    companion object {
        private val TOKEN = "ghp_" + "a".repeat(36)
    }
}
