package com.sherwood.wta.core.studio

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sherwood.wta.core.frontend.GitHubTokenFormat
import com.sherwood.wta.core.frontend.GitTokenProvider
import com.sherwood.wta.core.frontend.GitHubTokenStore
import com.sherwood.wta.core.frontend.GitHubUrlPolicy
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import android.content.Context
import com.sherwood.wta.core.studio.git.*

/**
 * Authenticated (or public-read) REST client for the parts of GitHub that the Studio
 * uses: account, repositories, branches, pull requests, commits, Actions workflows,
 * runs, jobs, logs and artifacts.
 *
 * This is GitHub's transport, not the Studio's interface: [GitHubProvider] adapts it to
 * [GitProvider], and the neutral model types it returns live in
 * [com.sherwood.wta.core.studio.git]. It was the only network surface before the
 * provider split and it still is the only GitHub one. It sends the token only in the
 * `Authorization` header, refuses non-HTTPS URLs, refuses HTTPS->HTTP downgrades, never
 * logs the token, and maps every failure to [GitFailureKind] so the UI can tell
 * "connect a token" from "no access" from "rate limited".
 */
class GitHubApiClient(
    context: Context,
    private val endpoints: EndpointConfig = EndpointSettingsStore.current(context),
    private val tokenProvider: GitTokenProvider = GitHubTokenStore(context),
    httpClient: OkHttpClient = NetworkModule.downloadClient,
    private val urlGuard: (String) -> Unit = { GitHubUrlPolicy.requireHttps(it) }
) {

    private val apiBase = endpoints.githubApiBaseUrl.trimEnd('/')

    private val secureClient: OkHttpClient = httpClient.newBuilder()
        .followSslRedirects(false)
        .build()

    private val redirectClient: OkHttpClient = httpClient.newBuilder()
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    // ----------------------------------------------------------------- account

    suspend fun currentUser(): GitResult<GitHubUser> = getJson(url("user")) { json ->
        parseUser(json)
    }

    // ------------------------------------------------------------ repositories

    suspend fun listRepositories(page: Int = 1, perPage: Int = 100): GitResult<List<GitHubRepository>> =
        getJson(
            url(
                "user", "repos"
            ) + "?affiliation=owner,collaborator,organization_member" +
                "&sort=updated&per_page=${perPage.coerceIn(1, 100)}&page=${page.coerceAtLeast(1)}"
        ) { json ->
            val array = json.asArrayOrNull() ?: return@getJson null
            array.mapNotNull { it.asObjectOrNull()?.let(::parseRepository) }
        }

    suspend fun getRepository(owner: String, repo: String): GitResult<GitHubRepository> =
        getJson(url("repos", owner, repo)) { parseRepository(it) }

    /**
     * Creates a repository under the authenticated account (`POST /user/repos`).
     *
     * [autoInit] makes GitHub create the initial commit (and therefore the default
     * branch) immediately, so the result is usable for an import or a pull request
     * without a second step. The created repository is always independent: this never
     * calls `/forks`, so the response carries `fork = false` and no upstream link.
     *
     * Organisations are deliberately not supported — those live under
     * `POST /orgs/{org}/repos`, which needs a scope decision the caller has not made.
     *
     * The common rejections are `422` (the name is taken or malformed) and `403` (the
     * token may not create repositories); [failureFrom] keeps GitHub's own text.
     */
    suspend fun createRepository(
        name: String,
        description: String? = null,
        privateRepository: Boolean = false,
        autoInit: Boolean = true,
        homepage: String? = null
    ): GitResult<GitHubRepository> {
        if (name.isBlank()) {
            return GitResult.Failure(
                GitFailureKind.UNPROCESSABLE,
                message = "A repository name is required"
            )
        }
        val payload = JsonObject().apply {
            addProperty("name", name.trim())
            addProperty("private", privateRepository)
            addProperty("auto_init", autoInit)
            if (!description.isNullOrBlank()) addProperty("description", description.trim())
            if (!homepage.isNullOrBlank()) addProperty("homepage", homepage.trim())
        }
        return sendJson("POST", url("user", "repos"), payload.toString()) { json ->
            parseRepository(json)
        }
    }

    // ----------------------------------------------------------------- branches

    suspend fun listBranches(owner: String, repo: String, perPage: Int = 100): GitResult<List<GitHubBranch>> =
        getJson(url("repos", owner, repo, "branches") + "?per_page=${perPage.coerceIn(1, 100)}") { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parseBranch) }
        }

    suspend fun getBranch(owner: String, repo: String, branch: String): GitResult<GitHubBranch> =
        getJson(url("repos", owner, repo, "branches", branch)) { parseBranch(it) }

    /** Creates `refs/heads/[branch]` at [fromSha]; fails with CONFLICT when it exists. */
    suspend fun createBranch(
        owner: String,
        repo: String,
        branch: String,
        fromSha: String
    ): GitResult<GitHubBranch> = sendJson(
        method = "POST",
        url = url("repos", owner, repo, "git", "refs"),
        body = JsonObject().apply {
            addProperty("ref", "refs/heads/$branch")
            addProperty("sha", fromSha)
        }.toString()
    ) { json ->
        val obj = json.asObjectOrNull() ?: return@sendJson null
        val ref = obj.string("ref") ?: return@sendJson null
        val sha = obj.obj("object")?.string("sha") ?: return@sendJson null
        GitHubBranch(ref.removePrefix("refs/heads/"), sha, false)
    }

    // ------------------------------------------------------------ pull requests

    suspend fun listPullRequests(
        owner: String,
        repo: String,
        state: String = "open"
    ): GitResult<List<GitHubPullRequest>> =
        getJson(url("repos", owner, repo, "pulls") + "?state=$state&per_page=100&sort=updated&direction=desc") { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parsePullRequest) }
        }

    suspend fun mergePullRequest(
        owner: String,
        repo: String,
        number: Int,
        commitTitle: String? = null,
        mergeMethod: String = "merge"
    ): GitResult<GitHubMergeResult> {
        val payload = JsonObject().apply {
            addProperty("merge_method", mergeMethod)
            if (!commitTitle.isNullOrBlank()) addProperty("commit_title", commitTitle)
        }
        return sendJson(
            "PUT",
            url("repos", owner, repo, "pulls", number.toString(), "merge"),
            payload.toString()
        ) { json ->
            val obj = json.asObjectOrNull() ?: return@sendJson null
            GitHubMergeResult(
                merged = obj.bool("merged") ?: false,
                sha = obj.string("sha"),
                message = obj.string("message")
            )
        }
    }

    /**
     * Opens a pull request from [head] into [base].
     *
     * GitHub answers `422` for the common rejections — an open PR already exists for
     * that branch, the head has no commits ahead of the base, a branch does not
     * exist — and the JSON `message`/`errors` text is preserved by
     * [failureFrom] so the UI can show GitHub's own explanation instead of a
     * generic failure. A `403` means the token lacks pull-request permission; `401`
     * means the token is invalid or expired.
     */
    suspend fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        head: String,
        base: String,
        body: String? = null,
        draft: Boolean = false
    ): GitResult<GitHubPullRequest> {
        if (title.isBlank()) {
            return GitResult.Failure(
                GitFailureKind.UNPROCESSABLE,
                message = "A pull request title is required"
            )
        }
        if (head.isBlank() || base.isBlank()) {
            return GitResult.Failure(
                GitFailureKind.UNPROCESSABLE,
                message = "Head and base branches are required"
            )
        }
        if (head.equals(base, ignoreCase = true)) {
            return GitResult.Failure(
                GitFailureKind.UNPROCESSABLE,
                message = "Head and base branches must differ"
            )
        }
        val payload = JsonObject().apply {
            addProperty("title", title.trim())
            addProperty("head", head.trim())
            addProperty("base", base.trim())
            addProperty("draft", draft)
            if (!body.isNullOrBlank()) addProperty("body", body)
        }
        return sendJson("POST", url("repos", owner, repo, "pulls"), payload.toString()) { json ->
            parsePullRequest(json)
        }
    }

    /** One pull request with mergeability, diff size and timestamps. */
    suspend fun getPullRequest(
        owner: String,
        repo: String,
        number: Int
    ): GitResult<GitHubPullRequestDetails> =
        getJson(url("repos", owner, repo, "pulls", number.toString())) { json ->
            parsePullRequestDetails(json)
        }

    /** Changed files of a pull request (GitHub caps the list at 300 files per PR). */
    suspend fun listPullRequestFiles(
        owner: String,
        repo: String,
        number: Int
    ): GitResult<List<GitHubPullRequestFile>> {
        val builder = urlBuilder("repos", owner, repo, "pulls", number.toString(), "files")
            .addQueryParameter("per_page", "100")
        return getJson(builder.build().toString()) { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parsePullRequestFile) }
        }
    }

    /**
     * CI state for one commit.
     *
     * Check-runs are the modern signal; the legacy combined commit status is only
     * consulted when no check-run exists, so a repository that uses one or the
     * other both report a state. When neither source has data the state is
     * [ChecksState.UNKNOWN] — never "passed".
     */
    suspend fun getCommitChecks(
        owner: String,
        repo: String,
        ref: String
    ): GitResult<GitHubCheckSummary> {
        val runsBuilder = urlBuilder("repos", owner, repo, "commits", ref, "check-runs")
            .addQueryParameter("per_page", "100")
        val runs = getJson(runsBuilder.build().toString()) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("check_runs")?.mapNotNull { element ->
                val run = element.asObjectOrNull() ?: return@mapNotNull null
                GitHubCheckRun(
                    name = run.string("name").orEmpty(),
                    status = run.string("status") ?: "queued",
                    conclusion = run.string("conclusion"),
                    detailsUrl = run.string("details_url") ?: run.string("html_url")
                )
            } ?: emptyList()
        }
        val runsValue = when (runs) {
            is GitResult.Success -> runs.value
            is GitResult.Failure -> {
                if (runs.kind != GitFailureKind.NOT_FOUND) {
                    return GitResult.Failure(runs.kind, runs.statusCode, runs.message)
                }
                emptyList()
            }
        }
        if (runsValue.isNotEmpty()) {
            return GitResult.Success(summarizeChecks(runsValue, combinedState = null))
        }
        val statusUrl = url("repos", owner, repo, "commits", ref, "status")
        val combined = getJson(statusUrl) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            val state = obj.string("state") ?: return@getJson null
            val contexts: List<GitHubCheckRun> = obj.arr("statuses")?.mapNotNull { element ->
                val status = element.asObjectOrNull() ?: return@mapNotNull null
                GitHubCheckRun(
                    name = status.string("context").orEmpty(),
                    status = "completed",
                    conclusion = status.string("state"),
                    detailsUrl = status.string("target_url")
                )
            } ?: emptyList()
            GitHubCheckSummary(
                state = checksStateOf(combinedState = state, checks = contexts),
                total = contexts.size,
                pending = contexts.count { !isCompleted(it.status) },
                passing = contexts.count { isPassing(it.conclusion) },
                failing = contexts.count { isFailing(it.conclusion) },
                checks = contexts,
                combinedState = state
            )
        }
        return when (combined) {
            is GitResult.Success -> combined
            is GitResult.Failure ->
                if (combined.kind == GitFailureKind.NOT_FOUND) {
                    GitResult.Success(summarizeChecks(emptyList(), combinedState = null))
                } else {
                    GitResult.Failure(combined.kind, combined.statusCode, combined.message)
                }
        }
    }

    /**
     * Submits a review. `COMMENT` and `REQUEST_CHANGES` are supported; an approval is
     * deliberately not exposed — merging and approving are decisions the owner makes
     * in GitHub's own UI, not something this app should do on their behalf.
     */
    suspend fun submitPullRequestReview(
        owner: String,
        repo: String,
        number: Int,
        body: String,
        requestChanges: Boolean = false
    ): GitResult<GitHubPullRequestReview> {
        if (body.isBlank()) {
            return GitResult.Failure(
                GitFailureKind.UNPROCESSABLE,
                message = "A review needs a comment"
            )
        }
        val payload = JsonObject().apply {
            addProperty("body", body)
            addProperty("event", if (requestChanges) "REQUEST_CHANGES" else "COMMENT")
        }
        return sendJson(
            "POST",
            url("repos", owner, repo, "pulls", number.toString(), "reviews"),
            payload.toString()
        ) { json ->
            val obj = json.asObjectOrNull() ?: return@sendJson null
            GitHubPullRequestReview(
                id = obj.long("id") ?: 0L,
                state = obj.string("state").orEmpty(),
                author = obj.obj("user")?.string("login").orEmpty(),
                body = obj.string("body"),
                submittedAt = obj.string("submitted_at")
            )
        }
    }

    /**
     * Adds a review comment on one line of a changed file.
     *
     * GitHub requires the commit the line refers to and the *new-side* line number,
     * so the caller passes the PR head SHA. `subject_type = file` was introduced for
     * multi-line comments and is not used here; a single-line comment is accepted by
     * every API version this client targets.
     */
    suspend fun addPullRequestReviewComment(
        owner: String,
        repo: String,
        number: Int,
        commitId: String,
        path: String,
        line: Int,
        body: String
    ): GitResult<GitHubPullRequestReviewComment> {
        if (body.isBlank()) {
            return GitResult.Failure(
                GitFailureKind.UNPROCESSABLE,
                message = "A review comment needs a body"
            )
        }
        if (commitId.isBlank() || path.isBlank() || line <= 0) {
            return GitResult.Failure(
                GitFailureKind.UNPROCESSABLE,
                message = "A review comment needs a commit, a path and a line"
            )
        }
        val payload = JsonObject().apply {
            addProperty("body", body)
            addProperty("commit_id", commitId)
            addProperty("path", path)
            addProperty("line", line)
            addProperty("side", "RIGHT")
        }
        return sendJson(
            "POST",
            url("repos", owner, repo, "pulls", number.toString(), "comments"),
            payload.toString()
        ) { json ->
            parsePullRequestReviewComment(json)
        }
    }

    // ------------------------------------------------------------------ commits

    suspend fun listCommits(
        owner: String,
        repo: String,
        sha: String? = null,
        perPage: Int = 50
    ): GitResult<List<GitHubCommitSummary>> {
        val builder = urlBuilder("repos", owner, repo, "commits")
            .addQueryParameter("per_page", perPage.coerceIn(1, 100).toString())
        if (!sha.isNullOrBlank()) builder.addQueryParameter("sha", sha)
        return getJson(builder.build().toString()) { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parseCommit) }
        }
    }

    /**
     * Merges [head] into [base] on the server. This is GitHub's own 3-way merge, so
     * the commit is identical to a local merge push and appears in the branch history.
     */
    suspend fun mergeBranches(
        owner: String,
        repo: String,
        base: String,
        head: String,
        commitMessage: String? = null
    ): GitResult<GitHubMergeResult> {
        val payload = JsonObject().apply {
            addProperty("base", base)
            addProperty("head", head)
            if (!commitMessage.isNullOrBlank()) addProperty("commit_message", commitMessage)
        }
        return sendJson("POST", url("repos", owner, repo, "merges"), payload.toString()) { json ->
            val obj = json.asObjectOrNull() ?: return@sendJson null
            GitHubMergeResult(
                merged = true,
                sha = obj.string("sha"),
                message = obj.string("message")
            )
        }
    }

    suspend fun getFileContent(
        owner: String,
        repo: String,
        path: String,
        ref: String
    ): GitResult<GitHubFileContent> {
        val builder = urlBuilder("repos", owner, repo, "contents")
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        builder.addQueryParameter("ref", ref)
        return getJson(builder.build().toString()) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            val content = obj.string("content")?.let {
                runCatching { Base64.decode(it.replace("\n", ""), Base64.DEFAULT) }.getOrNull()
            }
            GitHubFileContent(
                path = obj.string("path") ?: path,
                name = obj.string("name") ?: path.substringAfterLast('/'),
                sha = obj.string("sha").orEmpty(),
                type = obj.string("type") ?: "file",
                size = obj.long("size") ?: 0L,
                content = content
            )
        }
    }

    // ------------------------------------------------------------------ actions

    suspend fun listWorkflows(owner: String, repo: String): GitResult<List<GitHubWorkflow>> =
        getJson(url("repos", owner, repo, "actions", "workflows")) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("workflows")?.mapNotNull { it.asObjectOrNull()?.let(::parseWorkflow) }
        }

    suspend fun listWorkflowRuns(
        owner: String,
        repo: String,
        perPage: Int = 20,
        branch: String? = null
    ): GitResult<List<GitHubWorkflowRun>> {
        val builder = urlBuilder("repos", owner, repo, "actions", "runs")
            .addQueryParameter("per_page", perPage.coerceIn(1, 100).toString())
        if (!branch.isNullOrBlank()) builder.addQueryParameter("branch", branch)
        return getJson(builder.build().toString()) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("workflow_runs")?.mapNotNull { it.asObjectOrNull()?.let(::parseWorkflowRun) }
        }
    }

    suspend fun getWorkflowRun(owner: String, repo: String, runId: Long): GitResult<GitHubWorkflowRun> =
        getJson(url("repos", owner, repo, "actions", "runs", runId.toString())) { json ->
            json.asObjectOrNull()?.let(::parseWorkflowRun)
        }

    suspend fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowIdOrFile: String,
        ref: String,
        inputs: Map<String, String> = emptyMap()
    ): GitResult<Unit> {
        val payload = JsonObject().apply {
            addProperty("ref", ref)
            if (inputs.isNotEmpty()) {
                add("inputs", JsonObject().apply {
                    inputs.forEach { (key, value) -> addProperty(key, value) }
                })
            }
        }
        val identifier = workflowIdOrFile.trim()
        val path = if (identifier.toLongOrNull() != null) identifier else {
            identifier.substringAfterLast('/')
        }
        return sendJson(
            "POST",
            url("repos", owner, repo, "actions", "workflows", path, "dispatches"),
            payload.toString(),
            expectBody = false
        ) { Unit }
    }

    suspend fun listJobs(owner: String, repo: String, runId: Long): GitResult<List<GitHubJob>> =
        getJson(url("repos", owner, repo, "actions", "runs", runId.toString(), "jobs") + "?per_page=100") { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("jobs")?.mapNotNull { it.asObjectOrNull()?.let(::parseJob) }
        }

    /** Downloads a run's log as plain text. GitHub answers with a redirect to a signed URL. */
    suspend fun downloadRunLogs(owner: String, repo: String, runId: Long): GitResult<String> =
        withContext(Dispatchers.IO) {
            downloadText(url("repos", owner, repo, "actions", "runs", runId.toString(), "logs"))
        }

    suspend fun listRunArtifacts(owner: String, repo: String, runId: Long): GitResult<List<GitHubArtifact>> =
        getJson(url("repos", owner, repo, "actions", "runs", runId.toString(), "artifacts") + "?per_page=100") { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("artifacts")?.mapNotNull { it.asObjectOrNull()?.let(::parseArtifact) }
        }

    suspend fun listRepositoryArtifacts(
        owner: String,
        repo: String,
        perPage: Int = 50
    ): GitResult<List<GitHubArtifact>> =
        getJson(url("repos", owner, repo, "actions", "artifacts") + "?per_page=${perPage.coerceIn(1, 100)}") { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("artifacts")?.mapNotNull { it.asObjectOrNull()?.let(::parseArtifact) }
        }

    /** Downloads the artifact zip bytes (archive_download_url). */
    suspend fun downloadArtifact(artifact: GitHubArtifact): GitResult<ByteArray> =
        withContext(Dispatchers.IO) {
            downloadBytes(artifact.archiveDownloadUrl)
        }

    // ----------------------------------------------------------------- releases

    /**
     * Recent releases, newest first. Drafts are included when the token can see them
     * (the owner's token can); a release's assets are what `gh release upload`
     * publishes, e.g. the signed APK/AAB of a tag build.
     */
    suspend fun listReleases(
        owner: String,
        repo: String,
        perPage: Int = 20
    ): GitResult<List<GitHubRelease>> =
        getJson(url("repos", owner, repo, "releases") + "?per_page=${perPage.coerceIn(1, 100)}") { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parseRelease) }
        }

    /**
     * Downloads one release asset.
     *
     * The asset's REST URL only yields the binary when asked for
     * `application/octet-stream`; the default JSON Accept header returns metadata
     * instead. GitHub answers with a redirect to a signed storage URL, which the
     * redirect client follows.
     */
    suspend fun downloadReleaseAsset(asset: GitHubReleaseAsset): GitResult<ByteArray> =
        withContext(Dispatchers.IO) {
            downloadBytes(asset.apiDownloadUrl, BINARY_ACCEPT)
        }

    // ------------------------------------------------------------------ helpers

    private suspend fun <T> getJson(url: String, parse: (JsonElement) -> T?): GitResult<T> =
        withContext(Dispatchers.IO) {
            execute("GET", url, null, parse)
        }

    private suspend fun <T> sendJson(
        method: String,
        url: String,
        body: String,
        expectBody: Boolean = true,
        parse: (JsonElement) -> T?
    ): GitResult<T> = withContext(Dispatchers.IO) {
        val token = tokenOrNull()
        if (token == null) {
            return@withContext GitResult.Failure(
                GitFailureKind.NO_TOKEN,
                message = "Connect a GitHub account first"
            )
        }
        val request = Request.Builder()
            .url(url)
            .method(method, body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_ACCEPT)
            .header("X-GitHub-Api-Version", API_VERSION)
            .build()
        try {
            urlGuard(url)
            secureClient.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    return@withContext failureFrom(response.code, response.header("x-ratelimit-remaining"), response.header("retry-after"), text)
                }
                if (!expectBody || text.isNullOrBlank()) {
                    val empty = parse(JsonObject())
                        ?: return@withContext GitResult.Failure(
                            GitFailureKind.UNEXPECTED, response.code, "Unexpected response from GitHub"
                        )
                    return@withContext GitResult.Success(empty)
                }
                val element = runCatching { JsonParser.parseString(text) }.getOrNull()
                    ?: return@withContext GitResult.Failure(
                        GitFailureKind.UNEXPECTED, response.code, "Unexpected response from GitHub"
                    )
                val parsed = parse(element)
                    ?: return@withContext GitResult.Failure(
                        GitFailureKind.UNEXPECTED, response.code, "Unexpected response from GitHub"
                    )
                GitResult.Success(parsed)
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "GitHub request failed: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "GitHub request error: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        }
    }

    private fun <T> execute(
        method: String,
        url: String,
        body: String?,
        parse: (JsonElement) -> T?
    ): GitResult<T> {
        val token = tokenOrNull()
        val builder = Request.Builder()
            .url(url)
            .header("Accept", JSON_ACCEPT)
            .header("X-GitHub-Api-Version", API_VERSION)
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) {
            builder.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.method(method, null)
        }
        return try {
            urlGuard(url)
            secureClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    return failureFrom(
                        response.code,
                        response.header("x-ratelimit-remaining"),
                        response.header("retry-after"),
                        text
                    )
                }
                val element = runCatching { JsonParser.parseString(text.orEmpty()) }.getOrNull()
                val parsed = element?.let { runCatching { parse(it) }.getOrNull() }
                if (parsed == null) {
                    GitResult.Failure(GitFailureKind.UNEXPECTED, response.code, "Unexpected response from GitHub")
                } else {
                    GitResult.Success(parsed)
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "GitHub request failed: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "GitHub request error: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        }
    }

    private fun downloadText(url: String): GitResult<String> =
        downloadBytes(url).map { String(it, Charsets.UTF_8) }

    private fun downloadBytes(
        url: String,
        accept: String = JSON_ACCEPT
    ): GitResult<ByteArray> {
        val token = tokenOrNull()
        val builder = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", API_VERSION)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return try {
            urlGuard(url)
            redirectClient.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes()
                if (!response.isSuccessful) {
                    return failureFrom(
                        response.code,
                        response.header("x-ratelimit-remaining"),
                        response.header("retry-after"),
                        null
                    )
                }
                if (bytes == null) {
                    GitResult.Failure(GitFailureKind.UNEXPECTED, response.code, "Empty response")
                } else {
                    GitResult.Success(bytes)
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "GitHub download failed: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "GitHub download error: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        }
    }

    private fun tokenOrNull(): String? =
        tokenProvider.currentToken()?.takeIf { GitHubTokenFormat.isValid(it) }

    private fun failureFrom(
        code: Int,
        rateRemaining: String?,
        retryAfter: String?,
        body: String?
    ): GitResult.Failure {
        val message = extractMessage(body)
        val kind = when {
            code == 401 -> GitFailureKind.UNAUTHORIZED
            code == 429 -> GitFailureKind.RATE_LIMITED
            code == 403 && (rateRemaining == "0" || retryAfter != null) -> GitFailureKind.RATE_LIMITED
            code == 403 -> GitFailureKind.FORBIDDEN
            code == 404 -> GitFailureKind.NOT_FOUND
            code == 409 -> GitFailureKind.CONFLICT
            code == 422 -> GitFailureKind.UNPROCESSABLE
            else -> GitFailureKind.HTTP
        }
        return GitResult.Failure(kind, code, message)
    }

    private fun url(vararg segments: String): String =
        urlBuilder(*segments).build().toString()

    private fun urlBuilder(vararg segments: String) =
        apiBase.toHttpUrl().newBuilder().apply {
            segments.forEach { addPathSegments(it) }
        }

    /**
     * GitHub's own explanation, for the UI and for the agent.
     *
     * A `422` usually carries a generic top-level `message` ("Repository creation
     * failed.") plus the useful text in `errors[].message` ("name already exists on this
     * account"), so the details are appended instead of dropped — otherwise the caller
     * can only report "creation failed" and the user cannot act on it.
     */
    private fun extractMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val json = runCatching {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
        val message = json?.string("message") ?: body
        val details = json?.get("errors")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.string("message")
            }
            .orEmpty()
            .filter { it.isNotBlank() && !message.contains(it) }
        val combined = if (details.isEmpty()) message else message + ": " + details.joinToString("; ")
        return combined.trim().take(MAX_ERROR_MESSAGE)
    }

    companion object {
        private const val TAG = "GitHubApi"
        private const val API_VERSION = "2022-11-28"
        private const val MAX_ERROR_MESSAGE = 500
        private const val JSON_ACCEPT = "application/vnd.github+json"
        private const val BINARY_ACCEPT = "application/octet-stream"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // --------------------------------------------------------- pure parsers

        fun parseUser(json: JsonElement): GitHubUser? {
            val obj = json.asObjectOrNull() ?: return null
            val login = obj.string("login") ?: return null
            return GitHubUser(login, obj.string("name"), obj.string("avatar_url"))
        }

        fun parseRepository(json: JsonElement): GitHubRepository? {
            val obj = json.asObjectOrNull() ?: return null
            val name = obj.string("name") ?: return null
            val owner = obj.obj("owner")?.string("login") ?: return null
            return GitHubRepository(
                id = obj.long("id") ?: 0L,
                name = name,
                owner = owner,
                fullName = obj.string("full_name") ?: "$owner/$name",
                privateRepo = obj.bool("private") ?: false,
                fork = obj.bool("fork") ?: false,
                defaultBranch = obj.string("default_branch") ?: "main",
                description = obj.string("description"),
                pushedAt = obj.string("pushed_at")
            )
        }

        fun parseBranch(json: JsonElement): GitHubBranch? {
            val obj = json.asObjectOrNull() ?: return null
            val name = obj.string("name") ?: return null
            val sha = obj.obj("commit")?.string("sha") ?: return null
            return GitHubBranch(name, sha, obj.bool("protected") ?: false)
        }

        fun parsePullRequest(json: JsonElement): GitHubPullRequest? {
            val obj = json.asObjectOrNull() ?: return null
            val number = obj.int("number") ?: return null
            val head = obj.obj("head")
            val base = obj.obj("base")
            return GitHubPullRequest(
                number = number,
                title = obj.string("title").orEmpty(),
                state = obj.string("state") ?: "open",
                draft = obj.bool("draft") ?: false,
                merged = obj.bool("merged") ?: false,
                author = obj.obj("user")?.string("login").orEmpty(),
                headRef = head?.string("ref").orEmpty(),
                baseRef = base?.string("ref").orEmpty(),
                htmlUrl = obj.string("html_url").orEmpty(),
                updatedAt = obj.string("updated_at"),
                body = obj.string("body")
            )
        }

        fun parseCommit(json: JsonElement): GitHubCommitSummary? {
            val obj = json.asObjectOrNull() ?: return null
            val sha = obj.string("sha") ?: return null
            val commit = obj.obj("commit")
            return GitHubCommitSummary(
                sha = sha,
                message = commit?.string("message")?.lineSequence()?.firstOrNull().orEmpty(),
                authorName = commit?.obj("author")?.string("name").orEmpty(),
                authorDate = commit?.obj("author")?.string("date"),
                htmlUrl = obj.string("html_url")
            )
        }

        fun parsePullRequestDetails(json: JsonElement): GitHubPullRequestDetails? {
            val pull = parsePullRequest(json) ?: return null
            val obj = json.asObjectOrNull() ?: return null
            return GitHubPullRequestDetails(
                pull = pull,
                // `mergeable` is null while GitHub is still computing it — the UI
                // must show "unknown" rather than guess.
                mergeable = obj.bool("mergeable"),
                mergeableState = obj.string("mergeable_state"),
                additions = obj.int("additions") ?: 0,
                deletions = obj.int("deletions") ?: 0,
                changedFiles = obj.int("changed_files") ?: 0,
                commitCount = obj.int("commits") ?: 0,
                createdAt = obj.string("created_at"),
                mergedAt = obj.string("merged_at"),
                closedAt = obj.string("closed_at"),
                headSha = obj.obj("head")?.string("sha").orEmpty(),
                baseSha = obj.obj("base")?.string("sha")
            )
        }

        fun parsePullRequestFile(json: JsonElement): GitHubPullRequestFile? {
            val obj = json.asObjectOrNull() ?: return null
            val filename = obj.string("filename") ?: return null
            return GitHubPullRequestFile(
                filename = filename,
                status = obj.string("status") ?: "modified",
                additions = obj.int("additions") ?: 0,
                deletions = obj.int("deletions") ?: 0,
                changes = obj.int("changes") ?: 0,
                previousFilename = obj.string("previous_filename"),
                patch = obj.string("patch")
            )
        }

        /**
         * New-side line numbers a review comment may target, read from a unified-diff
         * `patch`. Removed lines do not exist on the new side and are skipped, so every
         * number returned is one GitHub accepts with `side = RIGHT`.
         *
         * Returns an empty list when GitHub omitted the patch (binary or oversized diff);
         * callers then fall back to manual line entry instead of guessing.
         */
        fun commentableNewLines(patch: String?, limit: Int = 40): List<Int> {
            if (patch.isNullOrBlank() || limit <= 0) return emptyList()
            val result = ArrayList<Int>()
            var newLine = 0
            for (raw in patch.split('\n')) {
                if (raw.startsWith("@@")) {
                    // Hunk header: @@ -oldStart[,oldCount] +newStart[,newCount] @@
                    val plus = raw.indexOf('+')
                    if (plus < 0) continue
                    newLine = raw.substring(plus + 1).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                    continue
                }
                if (newLine <= 0) continue
                when {
                    raw.startsWith("+") -> {
                        result.add(newLine)
                        newLine++
                    }
                    raw.startsWith("-") -> Unit   // gone on the new side
                    raw.startsWith("\\") -> Unit  // "\ No newline at end of file"
                    else -> newLine++             // context line
                }
                if (result.size >= limit) break
            }
            return result
        }

        fun parsePullRequestReviewComment(json: JsonElement): GitHubPullRequestReviewComment? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            return GitHubPullRequestReviewComment(
                id = id,
                path = obj.string("path").orEmpty(),
                line = obj.int("line"),
                body = obj.string("body").orEmpty(),
                author = obj.obj("user")?.string("login").orEmpty(),
                htmlUrl = obj.string("html_url")
            )
        }

        /** `completed` is the only terminal check-run status. */
        fun isCompleted(status: String): Boolean = status.equals("completed", ignoreCase = true)

        fun isPassing(conclusion: String?): Boolean = when (conclusion?.lowercase()) {
            "success", "neutral", "skipped" -> true
            else -> false
        }

        fun isFailing(conclusion: String?): Boolean = when (conclusion?.lowercase()) {
            "failure", "cancelled", "timed_out", "action_required", "startup_failure" -> true
            else -> false
        }

        fun summarizeChecks(checks: List<GitHubCheckRun>, combinedState: String?): GitHubCheckSummary {
            val total = checks.size
            val pending = checks.count { !isCompleted(it.status) }
            val failing = checks.count { isCompleted(it.status) && isFailing(it.conclusion) }
            val passing = checks.count { isCompleted(it.status) && isPassing(it.conclusion) }
            return GitHubCheckSummary(
                state = checksStateOf(combinedState, checks),
                total = total,
                pending = pending,
                passing = passing,
                failing = failing,
                checks = checks,
                combinedState = combinedState
            )
        }

        /**
         * Failing wins over pending, pending over passing, and no signal at all stays
         * [ChecksState.UNKNOWN]. Reporting UNKNOWN instead of PASSING is what keeps the
         * UI from telling the user a build is green when GitHub has not said anything.
         */
        fun checksStateOf(combinedState: String?, checks: List<GitHubCheckRun>): ChecksState {
            val combined = combinedState?.lowercase()
            if (checks.any { isCompleted(it.status) && isFailing(it.conclusion) }) {
                return ChecksState.FAILING
            }
            if (combined == "failure" || combined == "error") return ChecksState.FAILING
            if (checks.any { !isCompleted(it.status) }) return ChecksState.PENDING
            if (combined == "pending") return ChecksState.PENDING
            if (checks.isNotEmpty() && checks.all { isCompleted(it.status) }) {
                return ChecksState.PASSING
            }
            if (combined == "success") return ChecksState.PASSING
            return ChecksState.UNKNOWN
        }

        fun parseWorkflow(json: JsonElement): GitHubWorkflow? {
            val obj = json.asObjectOrNull() ?: return null
            return GitHubWorkflow(
                id = obj.long("id") ?: return null,
                name = obj.string("name") ?: return null,
                path = obj.string("path").orEmpty(),
                state = obj.string("state")
            )
        }

        fun parseWorkflowRun(json: JsonElement): GitHubWorkflowRun? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            return GitHubWorkflowRun(
                id = id,
                name = obj.string("name") ?: obj.string("display_title").orEmpty(),
                workflowId = obj.long("workflow_id") ?: 0L,
                status = obj.string("status") ?: "unknown",
                conclusion = obj.string("conclusion"),
                branch = obj.string("head_branch"),
                headSha = obj.string("head_sha"),
                event = obj.string("event"),
                runNumber = obj.int("run_number") ?: 0,
                displayTitle = obj.string("display_title"),
                createdAt = obj.string("created_at"),
                updatedAt = obj.string("updated_at"),
                htmlUrl = obj.string("html_url")
            )
        }

        fun parseJob(json: JsonElement): GitHubJob? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            val steps = obj.arr("steps")?.mapNotNull { element ->
                element.asObjectOrNull()?.let { step ->
                    GitHubJobStep(
                        number = step.int("number") ?: 0,
                        name = step.string("name").orEmpty(),
                        status = step.string("status") ?: "unknown",
                        conclusion = step.string("conclusion")
                    )
                }
            }.orEmpty()
            return GitHubJob(
                id = id,
                name = obj.string("name").orEmpty(),
                status = obj.string("status") ?: "unknown",
                conclusion = obj.string("conclusion"),
                startedAt = obj.string("started_at"),
                completedAt = obj.string("completed_at"),
                steps = steps
            )
        }

        fun parseArtifact(json: JsonElement): GitHubArtifact? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            val url = obj.string("archive_download_url") ?: return null
            return GitHubArtifact(
                id = id,
                name = obj.string("name").orEmpty(),
                sizeInBytes = obj.long("size_in_bytes") ?: 0L,
                expired = obj.bool("expired") ?: false,
                archiveDownloadUrl = url,
                createdAt = obj.string("created_at"),
                workflowRunId = obj.obj("workflow_run")?.long("id")
            )
        }

        fun parseReleaseAsset(json: JsonElement): GitHubReleaseAsset? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            val name = obj.string("name") ?: return null
            // `url` is the REST endpoint that returns the binary for an
            // application/octet-stream request; `browser_download_url` is a plain
            // public link that does not carry a token and is unusable for private
            // repositories, so the API URL is the one stored.
            val apiUrl = obj.string("url") ?: return null
            return GitHubReleaseAsset(
                id = id,
                name = name,
                sizeInBytes = obj.long("size") ?: 0L,
                contentType = obj.string("content_type"),
                apiDownloadUrl = apiUrl,
                state = obj.string("state")
            )
        }

        fun parseRelease(json: JsonElement): GitHubRelease? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            val tag = obj.string("tag_name") ?: return null
            val assets = obj.arr("assets")?.mapNotNull { element ->
                element.asObjectOrNull()?.let { parseReleaseAsset(it) }
            }.orEmpty()
            return GitHubRelease(
                id = id,
                tagName = tag,
                name = obj.string("name"),
                draft = obj.bool("draft") ?: false,
                prerelease = obj.bool("prerelease") ?: false,
                publishedAt = obj.string("published_at"),
                htmlUrl = obj.string("html_url"),
                assets = assets
            )
        }

        // ------------------------------------------------------- JSON utilities

        internal fun JsonElement.asObjectOrNull(): JsonObject? =
            takeIf { it.isJsonObject }?.asJsonObject

        internal fun JsonElement.asArrayOrNull(): JsonArray? =
            takeIf { it.isJsonArray }?.asJsonArray

        internal fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

        internal fun JsonObject.int(key: String): Int? =
            (get(key)?.takeIf { it.isJsonPrimitive }?.asInt)

        internal fun JsonObject.long(key: String): Long? =
            (get(key)?.takeIf { it.isJsonPrimitive }?.asLong)

        internal fun JsonObject.bool(key: String): Boolean? =
            (get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean)

        internal fun JsonObject.obj(key: String): JsonObject? =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        internal fun JsonObject.arr(key: String): JsonArray? =
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    }
}

private inline fun <T> GitResult<ByteArray>.map(transform: (ByteArray) -> T): GitResult<T> =
    when (this) {
        is GitResult.Success -> GitResult.Success(transform(value))
        is GitResult.Failure -> this
    }
