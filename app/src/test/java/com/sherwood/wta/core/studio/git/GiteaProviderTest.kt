package com.sherwood.wta.core.studio.git

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Gitea/Forgejo driver: JSON mapping, request shapes and the read/write boundary.
 *
 * The parsing tests pin the field names this build reads; the server tests pin the URL,
 * the auth header and the error mapping. Both matter: a server that started answering
 * `{"data": [...]}` instead of an array must produce a visible failure, not silently
 * empty lists, and the token must never be sent anywhere but the `Authorization` header.
 */
class GiteaProviderTest {

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

    private fun client(token: String? = TOKEN): GiteaApiClient = GiteaApiClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        tokenProvider = { token },
        httpClient = OkHttpClient(),
        // MockWebServer speaks plain HTTP on localhost; production keeps the HTTPS rule.
        urlGuard = {}
    )

    private fun jsonOf(text: String) = JsonParser.parseString(text)

    // ------------------------------------------------------------------ parsing

    @Test
    fun `parses an account`() {
        val account = GiteaApiClient.parseAccount(
            jsonOf("""{"login":"octocat","full_name":"The Octocat","avatar_url":"https://x/a.png"}""")
        )

        assertThat(account).isNotNull()
        assertThat(account!!.login).isEqualTo("octocat")
        assertThat(account.name).isEqualTo("The Octocat")
    }

    @Test
    fun `parses a repository and falls back to updated_at for the last push`() {
        val repo = GiteaApiClient.parseRepository(
            jsonOf(
                """{"id":7,"name":"app","full_name":"octocat/app","private":true,"fork":false,
                   "default_branch":"main","description":"d",
                   "updated_at":"2026-01-02T00:00:00Z",
                   "owner":{"login":"octocat"}}"""
            )
        )

        requireNotNull(repo)
        assertThat(repo.owner).isEqualTo("octocat")
        assertThat(repo.name).isEqualTo("app")
        assertThat(repo.fullName).isEqualTo("octocat/app")
        assertThat(repo.privateRepo).isTrue()
        assertThat(repo.defaultBranch).isEqualTo("main")
        assertThat(repo.pushedAt).isEqualTo("2026-01-02T00:00:00Z")
    }

    @Test
    fun `a repository without an owner is rejected`() {
        assertThat(GiteaApiClient.parseRepository(jsonOf("""{"id":1,"name":"app"}"""))).isNull()
    }

    @Test
    fun `parses a branch whose sha lives on the inline commit`() {
        val branch = GiteaApiClient.parseBranch(
            jsonOf("""{"name":"main","protected":true,"commit":{"id":"$SHA","message":"m"}}""")
        )

        requireNotNull(branch)
        assertThat(branch.name).isEqualTo("main")
        assertThat(branch.sha).isEqualTo(SHA)
        assertThat(branch.protectedBranch).isTrue()
    }

    @Test
    fun `parses a pull request including its draft flag`() {
        val pull = GiteaApiClient.parsePullRequest(
            jsonOf(
                """{"number":4,"title":"Fix","state":"open","draft":true,"merged":false,
                   "user":{"login":"dev"},"head":{"ref":"fix/x"},"base":{"ref":"main"},
                   "html_url":"https://git.example.com/o/r/pulls/4","body":"b"}"""
            )
        )

        requireNotNull(pull)
        assertThat(pull.number).isEqualTo(4)
        assertThat(pull.title).isEqualTo("Fix")
        assertThat(pull.draft).isTrue()
        assertThat(pull.author).isEqualTo("dev")
        assertThat(pull.headRef).isEqualTo("fix/x")
        assertThat(pull.baseRef).isEqualTo("main")
    }

    @Test
    fun `parses pull request details and the file list`() {
        val details = GiteaApiClient.parsePullRequestDetails(
            jsonOf(
                """{"number":4,"title":"Fix","state":"open","mergeable":true,
                   "additions":3,"deletions":1,"changed_files":1,"merged":false,
                   "head":{"ref":"fix/x","sha":"$SHA"},
                   "base":{"ref":"main","sha":"$SHA"},
                   "user":{"login":"dev"}}"""
            )
        )
        requireNotNull(details)
        assertThat(details.mergeable).isTrue()
        assertThat(details.additions).isEqualTo(3)
        assertThat(details.changedFiles).isEqualTo(1)
        assertThat(details.headSha).isEqualTo(SHA)

        val file = GiteaApiClient.parsePullRequestFile(
            jsonOf("""{"filename":"a.kt","status":"modified","additions":2,"deletions":1,"changes":3}""")
        )
        requireNotNull(file)
        assertThat(file.filename).isEqualTo("a.kt")
        // Gitea's file list carries no unified patch; callers must tolerate it being null.
        assertThat(file.patch).isNull()
    }

    @Test
    fun `commit statuses are normalised into checks`() {
        val pending = GiteaApiClient.parseCommitStatus(
            jsonOf("""{"context":"CI","status":"pending","target_url":"https://x/1"}""")
        )
        requireNotNull(pending)
        assertThat(pending.status).isEqualTo("queued")
        assertThat(pending.conclusion).isNull()

        val passed = GiteaApiClient.parseCommitStatus(
            jsonOf("""{"context":"CI","status":"success"}""")
        )
        requireNotNull(passed)
        assertThat(GitChecks.isCompleted(passed.status)).isTrue()
        assertThat(GitChecks.isPassing(passed.conclusion)).isTrue()
    }

    @Test
    fun `a workflow id is text because gitea encodes it as a string`() {
        val workflow = GiteaApiClient.parseWorkflow(
            jsonOf("""{"id":"release","name":"Release","path":".gitea/workflows/release.yml","state":"active"}""")
        )

        requireNotNull(workflow)
        assertThat(workflow.id).isEqualTo("release")
        assertThat(workflow.path).isEqualTo(".gitea/workflows/release.yml")
    }

    @Test
    fun `a run names its workflow by path and carries no id`() {
        val run = GiteaApiClient.parseRun(
            jsonOf(
                """{"id":900,"name":"Release","path":".gitea/workflows/release.yml",
                   "status":"success","conclusion":"success","head_branch":"main",
                   "run_number":3,"event":"push"}"""
            )
        )

        requireNotNull(run)
        assertThat(run.workflowId).isEmpty()
        assertThat(run.workflowPath).isEqualTo(".gitea/workflows/release.yml")
        assertThat(run.conclusion).isEqualTo("success")
    }

    @Test
    fun `an artifact download url points at the api zip route`() {
        val artifact = GiteaApiClient.parseArtifact(
            apiBase = "https://git.example.com/api/v1",
            owner = "octocat",
            repo = "app",
            json = jsonOf(
                """{"id":55,"name":"apk","size_in_bytes":10,"expired":false,
                   "archive_download_url":"https://git.example.com/o/r/actions/artifacts/55/zip"}"""
            ).asJsonObject
        )

        requireNotNull(artifact)
        assertThat(artifact.archiveDownloadUrl)
            .isEqualTo("https://git.example.com/api/v1/repos/octocat/app/actions/artifacts/55/zip")
    }

    @Test
    fun `an expired artifact is reported as expired`() {
        val artifact = GiteaApiClient.parseArtifact(
            apiBase = "https://git.example.com/api/v1",
            owner = "o",
            repo = "r",
            json = jsonOf("""{"id":1,"name":"x","expired":true}""").asJsonObject
        )
        requireNotNull(artifact)
        assertThat(artifact.expired).isTrue()
    }

    @Test
    fun `a release and its browser-served assets are parsed`() {
        val release = GiteaApiClient.parseRelease(
            jsonOf(
                """{"id":3,"tag_name":"v1","name":"1.0","draft":false,"prerelease":false,
                   "html_url":"https://git.example.com/o/r/releases/tag/v1",
                   "assets":[{"id":9,"name":"app.apk","size":123,
                     "browser_download_url":"https://git.example.com/o/r/releases/download/v1/app.apk"}]}"""
            )
        )

        requireNotNull(release)
        assertThat(release.tagName).isEqualTo("v1")
        assertThat(release.assets).hasSize(1)
        assertThat(release.assets[0].apiDownloadUrl)
            .isEqualTo("https://git.example.com/o/r/releases/download/v1/app.apk")
    }

    // ------------------------------------------------------------------ requests

    @Test
    fun `the account is read from api v1 with the token in the authorization header`() =
        runBlocking<Unit> {
            server.enqueue(jsonResponse("""{"login":"octocat"}"""))

            val result = client().currentUser()

            assertThat(result).isInstanceOf(GitResult.Success::class.java)
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/v1/user")
            assertThat(request.getHeader("Authorization")).isEqualTo("token $TOKEN")
            assertThat(request.getHeader("Accept")).isEqualTo("application/json")
        }

    @Test
    fun `repositories are paginated with the server's limit parameter`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""[{"id":1,"name":"app","owner":{"login":"dev"}}]"""))

        val result = client().listRepositories(page = 2)

        assertThat(result).isInstanceOf(GitResult.Success::class.java)
        val request = server.takeRequest()
        assertThat(request.path).startsWith("/api/v1/user/repos?")
        assertThat(request.path).contains("page=2")
        assertThat(request.path).contains("limit=50")
    }

    @Test
    fun `branches and runs use the nested repository routes`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse("""[{"name":"main","commit":{"id":"$SHA"}}]""")
        )
        server.enqueue(
            jsonResponse("""{"workflow_runs":[{"id":1,"path":".gitea/workflows/ci.yml"}]}""")
        )

        val api = client()
        api.listBranches("o", "r")
        api.listRuns("o", "r", branch = "main", perPage = 20)

        assertThat(server.takeRequest().path).isEqualTo("/api/v1/repos/o/r/branches?limit=50")
        val runRequest = server.takeRequest()
        assertThat(runRequest.path).startsWith("/api/v1/repos/o/r/actions/runs?")
        assertThat(runRequest.path).contains("branch=main")
    }

    @Test
    fun `the archive is fetched from the repository archive route`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("zip-bytes"))

        val target = Files.createTempFile("gitea-archive", ".zip").toFile()
        target.deleteOnExit()
        val result = client().downloadArchive("o", "r", "main", target) {}

        assertThat(result).isInstanceOf(GitResult.Success::class.java)
        assertThat(target.readText()).isEqualTo("zip-bytes")
        assertThat(server.takeRequest().path).isEqualTo("/api/v1/repos/o/r/archive/main.zip")
    }

    @Test
    fun `a 401 is reported as unauthorized and a 404 as not found`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(404))

        val api = client()
        val unauthorized = api.currentUser()
        val missing = api.getRepository("o", "missing")

        assertThat((unauthorized as GitResult.Failure).kind)
            .isEqualTo(GitFailureKind.UNAUTHORIZED)
        assertThat((missing as GitResult.Failure).kind).isEqualTo(GitFailureKind.NOT_FOUND)
    }

    @Test
    fun `no token means no authorization header`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"login":"public"}"""))

        client(token = null).currentUser()

        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `a non-json body is a typed failure rather than an empty success`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("<html>login</html>"))

        val result = client().currentUser()

        assertThat(result).isInstanceOf(GitResult.Failure::class.java)
        assertThat((result as GitResult.Failure).kind).isEqualTo(GitFailureKind.UNEXPECTED)
    }

    // ---------------------------------------------------------------- writes

    @Test
    fun `a repository is created on main and may be private`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse(
                """{"id":5,"name":"rebrand","full_name":"octocat/rebrand","private":false,
                   "default_branch":"main","owner":{"login":"octocat"}}"""
            )
        )

        val provider = provider()
        val created = provider.createRepository(
            NewGitRepository(name = "rebrand", description = "d", privateRepository = false)
        )

        assertThat(created).isInstanceOf(GitResult.Success::class.java)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/v1/user/repos")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"name\":\"rebrand\"")
        assertThat(body).contains("\"auto_init\":true")
        // Gitea's own default branch is a server setting; the caller expects `main`.
        assertThat(body).contains("\"default_branch\":\"main\"")
        assertThat(body).contains("\"private\":false")
        assertThat((created as GitResult.Success).value.defaultBranch).isEqualTo("main")
    }

    @Test
    fun `a new branch is created from the exact commit, not from a branch name`() =
        runBlocking<Unit> {
            server.enqueue(jsonResponse("""{"name":"feat/x","commit":{"id":"$SHA"}}"""))

            provider().createBranch(GitRepoRef.parse("o/r")!!, "feat/x", SHA)

            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/v1/repos/o/r/branches")
            val body = request.body.readUtf8()
            assertThat(body).contains("\"new_branch_name\":\"feat/x\"")
            assertThat(body).contains("\"old_ref_name\":\"$SHA\"")
        }

    @Test
    fun `a pull request carries its title, head and base`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse(
                """{"number":7,"title":"t","state":"open","head":{"ref":"feat/x"},
                   "base":{"ref":"main"},"html_url":"https://git.example.com/o/r/pulls/7"}"""
            )
        )

        provider().createChangeRequest(
            GitRepoRef.parse("o/r")!!,
            NewGitChangeRequest(title = "t", head = "feat/x", base = "main", body = "b")
        )

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"title\":\"t\"")
        assertThat(body).contains("\"head\":\"feat/x\"")
        assertThat(body).contains("\"base\":\"main\"")
        assertThat(body).contains("\"body\":\"b\"")
    }

    @Test
    fun `merging a pull request maps the strategy onto the server's vocabulary`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(200))

            val merged = provider().mergeChangeRequest(
                GitRepoRef.parse("o/r")!!,
                number = 7,
                method = "squash"
            )

            assertThat(merged).isInstanceOf(GitResult.Success::class.java)
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/v1/repos/o/r/pulls/7/merge")
            assertThat(request.body.readUtf8()).contains("\"do\":\"squash\"")
        }

    @Test
    fun `a review is submitted with the approve or request-changes verdict`() =
        runBlocking<Unit> {
            server.enqueue(jsonResponse("""{"id":1,"state":"REQUEST_CHANGES"}"""))

            provider().submitReview(GitRepoRef.parse("o/r")!!, 7, "please fix", requestChanges = true)

            val body = server.takeRequest().body.readUtf8()
            assertThat(body).contains("\"event\":\"REQUEST_CHANGES\"")
            assertThat(body).contains("\"body\":\"please fix\"")
        }

    @Test
    fun `a workflow dispatch posts the ref and its inputs`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(204))

        val dispatched = provider().dispatch(
            GitRepoRef.parse("o/r")!!,
            pipelineId = "build.yml",
            branch = "main",
            inputs = mapOf("flavor" to "standard")
        )

        assertThat(dispatched).isInstanceOf(GitResult.Success::class.java)
        val request = server.takeRequest()
        assertThat(request.path)
            .isEqualTo("/api/v1/repos/o/r/actions/workflows/build.yml/dispatches")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"ref\":\"main\"")
        assertThat(body).contains("\"flavor\":\"standard\"")
    }

    @Test
    fun `a server without the dispatch endpoint says so instead of not-found`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(404))

            val dispatched = provider().dispatch(
                GitRepoRef.parse("o/r")!!,
                pipelineId = "build.yml",
                branch = "main"
            )

            val failure = dispatched as GitResult.Failure
            assertThat(failure.kind).isEqualTo(GitFailureKind.UNSUPPORTED)
            assertThat(failure.message).contains("1.22")
        }

    @Test
    fun `a token that cannot create a repository is a typed failure`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"message":"user does not have permission"}""").setResponseCode(403))

        val created = provider().createRepository(NewGitRepository(name = "x"))

        assertThat((created as GitResult.Failure).kind).isEqualTo(GitFailureKind.FORBIDDEN)
    }

    private fun provider(): GiteaProvider = GiteaProvider(
        id = GitProviderId.GITEA,
        baseUrl = server.url("/").toString().trimEnd('/'),
        tokenProvider = { TOKEN },
        httpClient = OkHttpClient(),
        // MockWebServer speaks plain HTTP on localhost; production keeps the HTTPS rule.
        urlGuard = {}
    )

    // ------------------------------------------------------------ server info

    @Test
    fun `test connection reports the login and the server version`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"login":"octocat"}"""))
        server.enqueue(jsonResponse("""{"version":"1.22.6"}"""))

        val provider = GiteaProvider(
            id = GitProviderId.GITEA,
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { TOKEN },
            httpClient = OkHttpClient(),
            // MockWebServer speaks plain HTTP on localhost; production keeps HTTPS.
            urlGuard = {}
        )
        val info = provider.serverInfo() as GitResult.Success<GitServerInfo>

        assertThat(info.value.login).isEqualTo("octocat")
        assertThat(info.value.serverVersion).isEqualTo("1.22.6")
        assertThat(info.value.provider).isEqualTo(GitProviderId.GITEA)
    }

    @Test
    fun `a missing version endpoint still reports a usable connection`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"login":"octocat"}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        val provider = GiteaProvider(
            id = GitProviderId.FORGEJO,
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { TOKEN },
            httpClient = OkHttpClient(),
            // MockWebServer speaks plain HTTP on localhost; production keeps HTTPS.
            urlGuard = {}
        )
        val info = provider.serverInfo() as GitResult.Success<GitServerInfo>

        assertThat(info.value.login).isEqualTo("octocat")
        assertThat(info.value.serverVersion).isNull()
    }

    // ------------------------------------------------------------------ helpers

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    companion object {
        private val TOKEN = "a".repeat(40)
        private const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
