package com.sherwood.wta.core.frontend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
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

    private fun archiveResponse(body: ByteArray = zipBytes()): MockResponse = MockResponse()
        .setBody(Buffer().write(body))

    @Test
    fun `authenticated import sends the bearer token and records the base commit`() = runBlocking<Unit> {
        server.enqueue(refResponse(SHA))
        server.enqueue(archiveResponse())

        val fetcher = fetcher(TOKEN)
        val result = fetcher.fetch("octocat/hello-world", explicitBranch = "main")

        assertThat(result.isSuccess).isTrue()
        val value = result.getOrThrow()
        assertThat(value.baseSha).isEqualTo(SHA)
        assertThat(value.metadataFile).isNotNull()

        // The archive really is unpacked: the single root folder is unwrapped.
        val root = File(value.localPath)
        assertThat(File(root, "index.html").readText()).isEqualTo("<html><body>hello</body></html>")
        assertThat(File(root, "assets/app.js").readText()).isEqualTo("console.log('hello')")

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
        assertThat(tarballRequest.path).isEqualTo("/repos/octocat/hello-world/zipball/main")
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
    fun `failed authenticated download falls back to the public archive`() = runBlocking<Unit> {
        server.enqueue(refResponse(SHA))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(archiveResponse())

        val fetcher = fetcher(TOKEN)
        val result = fetcher.fetch("octocat/hello-world", explicitBranch = "main")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().baseSha).isEqualTo(SHA)

        assertThat(server.takeRequest().path).isEqualTo("/repos/octocat/hello-world/git/refs/heads/main")
        assertThat(server.takeRequest().path).isEqualTo("/repos/octocat/hello-world/zipball/main")
        assertThat(server.takeRequest().path)
            .isEqualTo("/octocat/hello-world/archive/refs/heads/main.zip")
    }

    @Test
    fun `gzipped tar body is never imported as an empty folder`() = runBlocking<Unit> {
        // The old route returned tar.gz data; that must fail loudly, not "succeed" empty.
        server.enqueue(refResponse(SHA))
        server.enqueue(MockResponse().setBody(Buffer().write(tarGzBytes())))

        val fetcher = fetcher(TOKEN)
        val result = fetcher.fetch("octocat/hello-world", explicitBranch = "main")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("no files")
    }

    @Test
    fun `archive without any regular file is rejected`() = runBlocking<Unit> {
        server.enqueue(refResponse(SHA))
        server.enqueue(archiveResponse(directoriesOnlyZipBytes()))

        val result = fetcher(TOKEN).fetch("octocat/hello-world", explicitBranch = "main")

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `production url policy refuses plain http before any socket is opened`() = runBlocking<Unit> {
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

    private fun directoriesOnlyZipBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("hello-world-main/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("hello-world-main/assets/"))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** A real ustar archive with one file, gzipped, as GitHub's tarball route serves it. */
    private fun tarGzBytes(): ByteArray {
        val content = "<html>hello</html>".toByteArray(Charsets.UTF_8)
        val tar = ByteArrayOutputStream()
        tar.write(tarHeader("hello-world-main/index.html", content.size))
        tar.write(content)
        tar.write(ByteArray((512 - content.size % 512) % 512))
        tar.write(ByteArray(1024)) // end-of-archive marker
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    private fun tarHeader(name: String, size: Int): ByteArray {
        val header = ByteArray(512)
        fun put(offset: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        put(0, name)
        put(100, "0000644\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, String.format("%011o", size) + "\u0000")
        put(136, "00000000000\u0000")
        put(148, "        ") // checksum is computed with this field set to spaces
        put(156, "0")
        put(257, "ustar\u0000")
        put(263, "00")
        val checksum = header.sumOf { it.toInt() and 0xff }
        put(148, String.format("%06o", checksum) + "\u0000 ")
        return header
    }

    companion object {
        private const val SHA = "0123456789abcdef0123456789abcdef01234567"
        private val TOKEN = "ghp_" + "a".repeat(36)
    }
}
