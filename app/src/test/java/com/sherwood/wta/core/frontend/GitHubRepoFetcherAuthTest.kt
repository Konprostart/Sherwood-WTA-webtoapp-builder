package com.sherwood.wta.core.frontend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitHubRepoFetcherAuthTest {

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

    private fun fetcher(token: String?) = GitHubRepoFetcher(
        context = ApplicationProvider.getApplicationContext<Context>(),
        tokenProvider = { token },
        httpClient = OkHttpClient(),
        apiBaseUrl = apiBase,
        archiveBaseUrl = apiBase,
        // MockWebServer speaks plain HTTP on localhost; production keeps the strict rule.
        urlGuard = {}
    )

    private fun refResponse(sha: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"ref":"refs/heads/main","object":{"type":"commit","sha":"$sha"}}""")

    private fun archiveResponse(): MockResponse = MockResponse()
        .setBody(Buffer().write(zipBytes()))

    @Test
    fun `authenticated import sends the bearer token and records the base commit`() = runBlocking {
        server.enqueue(refResponse(SHA))
        server.enqueue(archiveResponse())

        val fetcher = fetcher(TOKEN)
        val result = fetcher.fetch("octocat/hello-world", explicitBranch = "main")

        assertThat(result.isSuccess).isTrue()
        val value = result.getOrThrow()
        assertThat(value.baseSha).isEqualTo(SHA)
        assertThat(value.metadataFile).isNotNull()

        val metadataDir = File(value.metadataFile!!).parentFile!!
        val metadata = RepoBridgeMetadataStore().read(metadataDir)
        assertThat(metadata).isNotNull()
        val restored = requireNotNull(metadata)
        assertThat(restored.host).isEqualTo(RepoBridgeMetadata.HOST_GITHUB)
        assertThat(restored.owner).isEqualTo("octocat")
        assertThat(restored.repo).isEqualTo("hello-world")
        assertThat(restored.ref).isEqualTo("main")
        assertThat(restored.baseSha).isEqualTo(SHA)

        val refRequest = server.takeRequest()
        assertThat(refRequest.path).isEqualTo("/repos/octocat/hello-world/git/refs/heads/main")
        assertThat(refRequest.getHeader("Authorization")).isEqualTo("Bearer $TOKEN")

        val tarballRequest = server.takeRequest()
        assertThat(tarballRequest.path).isEqualTo("/repos/octocat/hello-world/tarball/main")
        assertThat(tarballRequest.getHeader("Authorization")).isEqualTo("Bearer $TOKEN")

        assertThat(fetcher.logs.value.joinToString("\n")).doesNotContain(TOKEN)
    }

    @Test
    fun `anonymous import keeps the public archive and never sends an authorization header`() =
        runBlocking {
            server.enqueue(refResponse(SHA))
            server.enqueue(archiveResponse())

            val fetcher = fetcher(token = null)
            val result = fetcher.fetch("octocat/hello-world", explicitBranch = "main")

            assertThat(result.isSuccess).isTrue()

            val refRequest = server.takeRequest()
            assertThat(refRequest.path).isEqualTo("/repos/octocat/hello-world/git/refs/heads/main")
            assertThat(refRequest.getHeader("Authorization")).isNull()

            val archiveRequest = server.takeRequest()
            assertThat(archiveRequest.path)
                .isEqualTo("/octocat/hello-world/archive/refs/heads/main.zip")
            assertThat(archiveRequest.getHeader("Authorization")).isNull()
        }

    @Test
    fun `failed authenticated download falls back to the public archive`() = runBlocking {
        server.enqueue(refResponse(SHA))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(archiveResponse())

        val fetcher = fetcher(TOKEN)
        val result = fetcher.fetch("octocat/hello-world", explicitBranch = "main")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().baseSha).isEqualTo(SHA)

        assertThat(server.takeRequest().path).isEqualTo("/repos/octocat/hello-world/git/refs/heads/main")
        assertThat(server.takeRequest().path).isEqualTo("/repos/octocat/hello-world/tarball/main")
        assertThat(server.takeRequest().path)
            .isEqualTo("/octocat/hello-world/archive/refs/heads/main.zip")
    }

    @Test
    fun `production url policy refuses plain http before any socket is opened`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fetcher = GitHubRepoFetcher(
            context = context,
            tokenProvider = { TOKEN },
            httpClient = OkHttpClient(),
            apiBaseUrl = "http://127.0.0.1:1",
            archiveBaseUrl = "http://127.0.0.1:1"
        )

        val result = fetcher.fetch("octocat/hello-world", explicitBranch = "main")

        assertThat(result.isFailure).isTrue()
        assertThat(fetcher.logs.value.joinToString("\n")).doesNotContain(TOKEN)
    }

    private fun zipBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf(
                "index.html" to "<html><body>hello</body></html>",
                "assets/app.js" to "console.log('hello')"
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry("hello-world-main/$name"))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val SHA = "0123456789abcdef0123456789abcdef01234567"
        private val TOKEN = "ghp_" + "a".repeat(36)
    }
}
