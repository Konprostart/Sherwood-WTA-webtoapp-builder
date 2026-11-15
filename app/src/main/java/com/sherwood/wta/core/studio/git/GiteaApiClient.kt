package com.sherwood.wta.core.studio.git

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sherwood.wta.core.frontend.GitHubUrlPolicy
import com.sherwood.wta.core.frontend.GitTokenProvider
import com.sherwood.wta.core.frontend.GitTreeEntry
import com.sherwood.wta.core.frontend.GitTreeSnapshot
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.util.zip.ZipInputStream

/**
 * REST transport for a Gitea or Forgejo server (`{server}/api/v1`).
 *
 * Reads: account, repositories, branches, pull requests, commit statuses, commits, file
 * contents, git trees, Actions workflows/runs/jobs/logs/artifacts, releases and the
 * source archive.
 *
 * Writes: create a repository, create/delete a branch, open/merge/review a pull request,
 * dispatch a workflow, and commit a multi-file change set through the `change-files`
 * endpoint. The change set is the only commit path: Gitea has no API that creates a
 * commit object separately from moving its branch, so a change is always atomic — the
 * branch appears with the commit or not at all. `force_push` is never sent, and every
 * update or delete carries the blob sha it was computed against, so content that changed
 * remotely makes the host refuse instead of being overwritten.
 *
 * Security rules match the GitHub client, because they are properties of the feature and
 * not of the host: the token travels only in the `Authorization` header, only HTTPS URLs
 * are requested, HTTPS-to-HTTP redirects are refused, the token is never logged, and every
 * failure is mapped to [GitFailureKind].
 *
 * The server's own version decides which of the Actions endpoints exist. Gitea gained the
 * workflow API over several releases, so a 404/405 from dispatch is reported as
 * [GitFailureKind.UNSUPPORTED] with the server version in the message rather than being
 * retried forever.
 */
internal class GiteaApiClient(
    baseUrl: String,
    private val tokenProvider: GitTokenProvider,
    httpClient: OkHttpClient = NetworkModule.downloadClient,
    private val urlGuard: (String) -> Unit = { GitHubUrlPolicy.requireHttps(it) }
) {

    private val webBase = baseUrl.trimEnd('/')
    private val apiBase = "$webBase$API_SUFFIX"

    private val secureClient: OkHttpClient = httpClient.newBuilder()
        .followSslRedirects(false)
        .build()

    private val redirectClient: OkHttpClient = httpClient.newBuilder()
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    // ----------------------------------------------------------------- account

    suspend fun currentUser(): GitResult<GitAccount> = getJson(apiUrl("user")) { json ->
        parseAccount(json)
    }

    /** `GET /version`; the only endpoint that needs no token on every server version. */
    suspend fun serverVersion(): GitResult<String> = getJson(webUrl("api", "v1", "version")) { json ->
        json.asObjectOrNull()?.string("version")
    }

    // ------------------------------------------------------------ repositories

    suspend fun listRepositories(page: Int): GitResult<List<GitRepository>> {
        val url = urlBuilder("user", "repos")
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("limit", PER_PAGE.toString())
            .build()
        return getJson(url) { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parseRepository) }
        }
    }

    suspend fun getRepository(owner: String, repo: String): GitResult<GitRepository> =
        getJson(apiUrl("repos", owner, repo)) { json ->
            json.asObjectOrNull()?.let(::parseRepository)
        }

    /**
     * `POST /user/repos`. `default_branch` is sent explicitly when the repository is
     * initialised: Gitea's own default is a server setting, and a caller that expects
     * `main` (the name every release of this app tags) must not depend on it.
     */
    suspend fun createRepository(request: NewGitRepository): GitResult<GitRepository> {
        val body = JsonObject().apply {
            addProperty("name", request.name)
            request.description?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { addProperty("description", it) }
            addProperty("private", request.privateRepository)
            addProperty("auto_init", request.autoInit)
            if (request.autoInit) addProperty("default_branch", DEFAULT_BRANCH)
        }
        return sendJson("POST", apiUrl("user", "repos"), body) { json ->
            json.asObjectOrNull()?.let(::parseRepository)
        }
    }

    // ----------------------------------------------------------------- branches

    suspend fun listBranches(owner: String, repo: String): GitResult<List<GitBranch>> {
        val url = urlBuilder("repos", owner, repo, "branches")
            .addQueryParameter("limit", PER_PAGE.toString())
            .build()
        return getJson(url) { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parseBranch) }
        }
    }

    suspend fun getBranch(owner: String, repo: String, name: String): GitResult<GitBranch> =
        getJson(apiUrl("repos", owner, repo, "branches", name)) { json ->
            json.asObjectOrNull()?.let(::parseBranch)
        }

    /**
     * `POST /repos/{owner}/{repo}/branches`.
     *
     * [fromRef] may be a branch name or a full commit sha; `old_ref_name` is resolved by
     * git itself, which is what lets a publish pin the new branch to the exact commit the
     * diff was taken against instead of "whatever the base points at now".
     */
    suspend fun createBranch(
        owner: String,
        repo: String,
        name: String,
        fromRef: String
    ): GitResult<GitBranch> {
        val body = JsonObject().apply {
            addProperty("new_branch_name", name)
            addProperty("old_ref_name", fromRef)
        }
        return sendJson("POST", apiUrl("repos", owner, repo, "branches"), body) { json ->
            json.asObjectOrNull()?.let(::parseBranch)
        }
    }

    /** `DELETE /repos/{owner}/{repo}/branches/{branch}`; only used to undo an empty one. */
    suspend fun deleteBranch(owner: String, repo: String, name: String): GitResult<Unit> =
        sendEmpty("DELETE", apiUrl("repos", owner, repo, "branches", name))

    // ------------------------------------------------------------ pull requests

    suspend fun listPullRequests(
        owner: String,
        repo: String,
        state: String
    ): GitResult<List<GitChangeRequest>> {
        val requested = when (state.lowercase()) {
            "closed" -> "closed"
            "all" -> "all"
            else -> "open"
        }
        val url = urlBuilder("repos", owner, repo, "pulls")
            .addQueryParameter("state", requested)
            .addQueryParameter("limit", PER_PAGE.toString())
            .build()
        return getJson(url) { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parsePullRequest) }
        }
    }

    suspend fun getPullRequest(
        owner: String,
        repo: String,
        number: Int
    ): GitResult<GitChangeRequestDetails> =
        getJson(apiUrl("repos", owner, repo, "pulls", number.toString())) { json ->
            json.asObjectOrNull()?.let(::parsePullRequestDetails)
        }

    suspend fun listPullRequestFiles(
        owner: String,
        repo: String,
        number: Int
    ): GitResult<List<GitChangeRequestFile>> =
        getJson(apiUrl("repos", owner, repo, "pulls", number.toString(), "files")) { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parsePullRequestFile) }
        }

    /**
     * `POST /repos/{owner}/{repo}/pulls`.
     *
     * Gitea has no draft field on creation (a draft is set afterwards), so [NewGitChangeRequest.draft]
     * is not sent: the caller that needs a draft-free pull request is the publisher, and
     * silently prefixing a title would alter text the user approved.
     */
    suspend fun createPullRequest(
        owner: String,
        repo: String,
        request: NewGitChangeRequest
    ): GitResult<GitChangeRequest> {
        val body = JsonObject().apply {
            addProperty("title", request.title)
            addProperty("head", request.head)
            addProperty("base", request.base)
            request.body?.takeIf { it.isNotBlank() }?.let { addProperty("body", it) }
        }
        return sendJson("POST", apiUrl("repos", owner, repo, "pulls"), body) { json ->
            json.asObjectOrNull()?.let(::parsePullRequest)
        }
    }

    /**
     * `POST /repos/{owner}/{repo}/pulls/{index}/merge`.
     *
     * Gitea answers `200` with an empty body, so the merge is reported as done with the
     * sha unknown; the caller re-reads the pull request when it needs the commit.
     */
    suspend fun mergePullRequest(
        owner: String,
        repo: String,
        number: Int,
        commitTitle: String?,
        method: String
    ): GitResult<GitMergeResult> {
        val body = JsonObject().apply {
            addProperty("do", GITEA_MERGE_METHODS[method.lowercase()] ?: "merge")
            commitTitle?.takeIf { it.isNotBlank() }?.let { addProperty("merge_title_field", it) }
        }
        return when (val merged = sendEmpty(
            "POST",
            apiUrl("repos", owner, repo, "pulls", number.toString(), "merge"),
            body
        )) {
            is GitResult.Success -> GitResult.Success(
                GitMergeResult(merged = true, sha = null, message = null)
            )
            is GitResult.Failure -> merged
        }
    }

    /** `POST /repos/{owner}/{repo}/pulls/{index}/reviews` with an approve / request-changes event. */
    suspend fun submitReview(
        owner: String,
        repo: String,
        number: Int,
        body: String,
        requestChanges: Boolean
    ): GitResult<Unit> {
        val payload = JsonObject().apply {
            addProperty("body", body)
            addProperty("event", if (requestChanges) "REQUEST_CHANGES" else "APPROVED")
        }
        val url = apiUrl("repos", owner, repo, "pulls", number.toString(), "reviews")
        return when (
            val submitted = sendJson("POST", url, payload) { json -> json.asObjectOrNull() }
        ) {
            is GitResult.Success -> GitResult.Success(Unit)
            is GitResult.Failure -> submitted
        }
    }

    // --------------------------------------------------------------- checks

    /**
     * Gitea has commit statuses, not check runs. A status whose own value is `pending` is
     * normalised into an incomplete [GitCheck] so [GitChecks] applies the same rules it
     * applies to GitHub's pairs.
     */
    suspend fun combinedStatus(owner: String, repo: String, revision: String): GitResult<GitCheckSummary> =
        getJson(apiUrl("repos", owner, repo, "commits", revision, "status")) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            val checks = obj.arr("statuses")?.mapNotNull { element ->
                element.asObjectOrNull()?.let(::parseCommitStatus)
            }.orEmpty()
            GitChecks.summarize(checks, obj.string("state"))
        }

    // ------------------------------------------------------------------ commits

    suspend fun listCommits(
        owner: String,
        repo: String,
        branch: String?,
        perPage: Int
    ): GitResult<List<GitCommitSummary>> {
        val builder = urlBuilder("repos", owner, repo, "commits")
            .addQueryParameter("limit", perPage.coerceIn(1, 100).toString())
        if (!branch.isNullOrBlank()) builder.addQueryParameter("sha", branch)
        return getJson(builder.build()) { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parseCommit) }
        }
    }

    /** File contents at [ref]; Gitea answers with base64 in `content` unless the file is huge. */
    suspend fun fileContent(
        owner: String,
        repo: String,
        path: String,
        ref: String
    ): GitResult<GitFileContent> {
        val builder = urlBuilder("repos", owner, repo, "contents")
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        builder.addQueryParameter("ref", ref)
        return getJson(builder.build()) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            val bytes = decodeContent(obj.string("content"), obj.string("encoding"))
            GitFileContent(
                path = obj.string("path").orEmpty(),
                name = obj.string("name").orEmpty(),
                sha = obj.string("sha").orEmpty(),
                type = obj.string("type") ?: "file",
                size = obj.long("size") ?: 0L,
                content = bytes
            )
        }
    }

    /**
     * `GET /repos/{owner}/{repo}/git/commits/{sha}`: the commit sha and the sha of its tree.
     *
     * A working copy's provenance records the commit; diffing needs the tree it points at.
     */
    suspend fun getCommitTree(owner: String, repo: String, sha: String): GitResult<GitCommitTree> =
        getJson(apiUrl("repos", owner, repo, "git", "commits", sha)) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            val commitSha = obj.string("sha") ?: return@getJson null
            val treeSha = obj.obj("commit")?.obj("tree")?.string("sha")
                ?: obj.obj("tree")?.string("sha")
                ?: return@getJson null
            GitCommitTree(commitSha = commitSha, treeSha = treeSha)
        }

    /**
     * `GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1`, paginated.
     *
     * The server reports `truncated` while more entries remain. The loop stops at
     * [MAX_TREE_PAGES] and marks the result truncated, which makes a publish refuse
     * instead of diffing against a tree that is missing files (those would look deleted).
     */
    suspend fun getTree(owner: String, repo: String, treeSha: String): GitResult<GitTreeSnapshot> {
        val entries = ArrayList<GitTreeEntry>()
        var page = 1
        var truncated = true
        while (truncated && page <= MAX_TREE_PAGES) {
            val url = urlBuilder("repos", owner, repo, "git", "trees", treeSha)
                .addQueryParameter("recursive", "true")
                .addQueryParameter("per_page", TREE_PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())
                .build()
            when (val pageResult = getJson(url) { json -> json.asObjectOrNull() }) {
                is GitResult.Failure -> return pageResult
                is GitResult.Success -> {
                    val obj = pageResult.value
                    obj.arr("tree")?.forEach { element ->
                        val entry = element.asObjectOrNull() ?: return@forEach
                        val path = entry.string("path") ?: return@forEach
                        entries += GitTreeEntry(
                            path = path,
                            sha = entry.string("sha"),
                            mode = entry.string("mode") ?: "100644",
                            type = entry.string("type") ?: "blob"
                        )
                    }
                    truncated = obj.bool("truncated") ?: false
                    // A server that ignores `page` would otherwise loop on page 1 forever.
                    val reported = obj.int("page")
                    if (reported != null && reported != page && truncated) {
                        return GitResult.Failure(
                            GitFailureKind.UNEXPECTED,
                            message = "The server did not paginate the tree"
                        )
                    }
                }
            }
            page++
        }
        return GitResult.Success(GitTreeSnapshot(entries, truncated))
    }

    // ---------------------------------------------------------------- actions

    suspend fun listWorkflows(owner: String, repo: String): GitResult<List<GitPipeline>> =
        getJson(apiUrl("repos", owner, repo, "actions", "workflows")) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("workflows")?.mapNotNull { it.asObjectOrNull()?.let(::parseWorkflow) }
        }

    suspend fun listRuns(
        owner: String,
        repo: String,
        branch: String?,
        perPage: Int
    ): GitResult<List<GitPipelineRun>> {
        val builder = urlBuilder("repos", owner, repo, "actions", "runs")
            .addQueryParameter("limit", perPage.coerceIn(1, 100).toString())
        if (!branch.isNullOrBlank()) builder.addQueryParameter("branch", branch)
        return getJson(builder.build()) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("workflow_runs")?.mapNotNull { it.asObjectOrNull()?.let(::parseRun) }
        }
    }

    suspend fun getRun(owner: String, repo: String, runId: Long): GitResult<GitPipelineRun> =
        getJson(apiUrl("repos", owner, repo, "actions", "runs", runId.toString())) { json ->
            json.asObjectOrNull()?.let(::parseRun)
        }

    suspend fun listJobs(owner: String, repo: String, runId: Long): GitResult<List<GitRunJob>> =
        getJson(apiUrl("repos", owner, repo, "actions", "runs", runId.toString(), "jobs")) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("jobs")?.mapNotNull { it.asObjectOrNull()?.let(::parseJob) }
        }

    /**
     * Gitea returns the logs of a run as a **zip archive of per-job text files**, not as
     * one text body. The archive is unpacked and the text files are concatenated, bounded
     * so a chatty workflow cannot exhaust memory. A server that answers with plain text
     * instead is passed through unchanged.
     */
    suspend fun runLogs(owner: String, repo: String, runId: Long): GitResult<String> =
        withContext(Dispatchers.IO) {
            when (
                val bytes = downloadBytes(
                    apiUrl("repos", owner, repo, "actions", "runs", runId.toString(), "logs")
                )
            ) {
                is GitResult.Failure -> bytes
                is GitResult.Success -> GitResult.Success(unpackLogs(bytes.value))
            }
        }

    suspend fun listRunArtifacts(owner: String, repo: String, runId: Long): GitResult<List<GitArtifact>> =
        getJson(apiUrl("repos", owner, repo, "actions", "runs", runId.toString(), "artifacts")) { json ->
            val obj = json.asObjectOrNull() ?: return@getJson null
            obj.arr("artifacts")?.mapNotNull { element ->
                element.asObjectOrNull()?.let { parseArtifact(apiBase, owner, repo, it) }
            }
        }

    suspend fun downloadArtifact(artifact: GitArtifact): GitResult<ByteArray> =
        withContext(Dispatchers.IO) { downloadBytes(artifact.archiveDownloadUrl) }

    // --------------------------------------------------------------- releases

    suspend fun listReleases(
        owner: String,
        repo: String,
        perPage: Int
    ): GitResult<List<GitRelease>> {
        val url = urlBuilder("repos", owner, repo, "releases")
            .addQueryParameter("limit", perPage.coerceIn(1, 100).toString())
            .build()
        return getJson(url) { json ->
            json.asArrayOrNull()?.mapNotNull { it.asObjectOrNull()?.let(::parseRelease) }
        }
    }

    /**
     * Release assets are served by the web download route, not the API. Gitea accepts the
     * API token there only in some configurations, so this tries the plain route and
     * reports a clear failure instead of pretending it worked. Workflow artifacts are the
     * reliable path for a private repository.
     */
    suspend fun downloadReleaseAsset(asset: GitReleaseAsset): GitResult<ByteArray> =
        withContext(Dispatchers.IO) {
            val token = tokenOrNull()
            val builder = Request.Builder().url(asset.apiDownloadUrl).get()
            if (token != null) builder.header("Authorization", "token $token")
            try {
                urlGuard(asset.apiDownloadUrl)
                redirectClient.newCall(builder.build()).execute().use { response ->
                    val bytes = response.body?.bytes()
                    if (!response.isSuccessful) {
                        return@withContext failureFrom(response.code, null)
                    }
                    if (bytes == null) {
                        GitResult.Failure(GitFailureKind.UNEXPECTED, response.code, "Empty response")
                    } else {
                        GitResult.Success(bytes)
                    }
                }
            } catch (e: IOException) {
                AppLogger.w(TAG, "Release asset download failed: ${e.javaClass.simpleName}")
                GitResult.Failure(GitFailureKind.NETWORK)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Release asset download error: ${e.javaClass.simpleName}")
                GitResult.Failure(GitFailureKind.NETWORK)
            }
        }

    // ---------------------------------------------------------------- archive

    /** `GET /repos/{owner}/{repo}/archive/{ref}.zip`, streamed to [target]. */
    suspend fun downloadArchive(
        owner: String,
        repo: String,
        ref: String,
        target: File,
        onProgress: (Float) -> Unit
    ): GitResult<Unit> = withContext(Dispatchers.IO) {
        val token = tokenOrNull()
        val url = apiUrl("repos", owner, repo, "archive", "$ref.zip")
        val builder = Request.Builder().url(url).get()
        if (token != null) builder.header("Authorization", "token $token")
        try {
            urlGuard(url)
            redirectClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext failureFrom(response.code, null)
                }
                val body = response.body
                    ?: return@withContext GitResult.Failure(
                        GitFailureKind.UNEXPECTED,
                        message = "Empty response"
                    )
                streamToFile(body.byteStream(), body.contentLength(), target, onProgress)
                GitResult.Success(Unit)
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Archive download failed: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Archive download error: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        }
    }

    // ---------------------------------------------------------------- dispatch

    /**
     * `POST /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches`.
     *
     * The endpoint only exists once the server ships the Actions API, which older Gitea
     * releases do not. A 404/405 is therefore reported as
     * [GitFailureKind.UNSUPPORTED] with the version this build needs, not as "not found":
     * the workflow genuinely exists, the server just cannot start it.
     */
    suspend fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowId: String,
        branch: String,
        inputs: Map<String, String>
    ): GitResult<Unit> {
        val body = JsonObject().apply {
            addProperty("ref", branch)
            if (inputs.isNotEmpty()) {
                add("inputs", JsonObject().apply {
                    inputs.forEach { (key, value) -> addProperty(key, value) }
                })
            }
        }
        val url = apiUrl(
            "repos", owner, repo, "actions", "workflows", workflowId, "dispatches"
        )
        return when (val dispatched = sendEmpty("POST", url, body)) {
            is GitResult.Success -> dispatched
            is GitResult.Failure -> if (dispatched.statusCode == 404 || dispatched.statusCode == 405) {
                GitResult.Failure(
                    GitFailureKind.UNSUPPORTED,
                    dispatched.statusCode,
                    "This server does not expose the workflow dispatch endpoint " +
                        "(${dispatched.message ?: "not found"}). Dispatch needs the Actions API " +
                        "of Gitea $MIN_DISPATCH_VERSION+ / Forgejo with Actions enabled."
                )
            } else {
                dispatched
            }
        }
    }

    // ----------------------------------------------------------- change files

    /**
     * Commits a whole change set in one call (`POST /repos/{owner}/{repo}/contents`).
     *
     * This is the only write path on Gitea, because its API creates a commit and moves
     * (or creates) the branch together. [newBranch] makes the host start that branch from
     * [baseBranch] and attach the commit to it, so the branch and the commit appear
     * together: a refused change set leaves neither behind.
     *
     * Every update or delete carries the blob sha it was computed against
     * ([GitFileChange.baseSha]) and `force_push` is never sent, so content that changed
     * remotely makes the commit fail instead of being overwritten.
     */
    suspend fun changeFiles(
        owner: String,
        repo: String,
        baseBranch: String,
        newBranch: String?,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<String> = withContext(Dispatchers.IO) {
        val url = apiUrl("repos", owner, repo, "contents")
        val body = ChangeFilesBody(
            message = message,
            branch = baseBranch,
            newBranch = newBranch,
            changes = changes
        )
        val builder = Request.Builder()
            .url(url)
            .header("Accept", JSON_ACCEPT)
            .apply { tokenOrNull()?.let { header("Authorization", "token $it") } }
            .post(body)
        try {
            urlGuard(url)
            secureClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    return@withContext changeFilesFailure(response.code, text)
                }
                val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
                val sha = parsed?.asObjectOrNull()?.obj("commit")?.string("sha")
                if (sha == null) {
                    GitResult.Failure(
                        GitFailureKind.UNEXPECTED,
                        response.code,
                        "The server did not return the new commit"
                    )
                } else {
                    GitResult.Success(sha)
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Change set failed: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Change set error: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * One request with a JSON body, returning the parsed value.
     *
     * [parse] receives null for a successful response with no body (Gitea answers `204`
     * to deletes and dispatches), so a caller that expects nothing can accept both `200`
     * and `204` without treating one of them as a failure.
     */
    private suspend fun <T> send(
        method: String,
        url: String,
        body: RequestBody?,
        parse: (JsonElement?) -> T?
    ): GitResult<T> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", JSON_ACCEPT)
            .apply { tokenOrNull()?.let { header("Authorization", "token $it") } }
            .method(method, body)
        try {
            urlGuard(url)
            secureClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    return@withContext failureFrom(response.code, text)
                }
                val element = text
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
                val parsed = parse(element)
                    ?: return@withContext GitResult.Failure(
                        GitFailureKind.UNEXPECTED,
                        response.code,
                        "Unexpected response from the git server"
                    )
                GitResult.Success(parsed)
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Request failed: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Request error: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        }
    }

    private suspend fun <T> sendJson(
        method: String,
        url: String,
        body: JsonElement,
        parse: (JsonElement) -> T?
    ): GitResult<T> = send(method, url, body.toString().toRequestBody(JSON_MEDIA_TYPE)) { element ->
        element?.let(parse)
    }

    /** A write whose success carries nothing; accepts an empty or JSON response body. */
    private suspend fun sendEmpty(
        method: String,
        url: String,
        body: JsonElement? = null
    ): GitResult<Unit> = send(
        method,
        url,
        body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
    ) { Unit }

    /**
     * A `422` from change-files is usually a refused precondition, not a malformed
     * request: the branch already exists, or a file's blob sha no longer matches the
     * branch. Reporting those as [GitFailureKind.CONFLICT] is what lets the publisher say
     * "the remote changed, re-import" instead of "the server rejected the payload".
     */
    private fun changeFilesFailure(code: Int, body: String?): GitResult.Failure {
        val failure = failureFrom(code, body)
        if (code != 422) return failure
        val message = failure.message.orEmpty()
        val conflict = message.contains("already exists", ignoreCase = true) ||
            message.contains("does not match", ignoreCase = true) ||
            message.contains("not up to date", ignoreCase = true) ||
            message.contains("out of date", ignoreCase = true)
        return if (conflict) {
            GitResult.Failure(GitFailureKind.CONFLICT, code, message)
        } else {
            failure
        }
    }

    private fun streamToFile(
        input: java.io.InputStream,
        contentLength: Long,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        var total = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_ARCHIVE_BYTES) {
                    throw IOException("Archive larger than ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB")
                }
                output.write(buffer, 0, read)
                if (contentLength > 0) {
                    onProgress((total.toFloat() / contentLength).coerceIn(0f, 1f))
                }
            }
        }
    }

    /** Bounded unpack of the log archive. Non-zip payloads are returned as text. */
    private fun unpackLogs(bytes: ByteArray): String {
        val looksLikeZip = bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!looksLikeZip) return String(bytes, Charsets.UTF_8)
        val output = StringBuilder()
        try {
            ZipInputStream(bytes.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && output.length < MAX_LOG_CHARS) {
                    if (!entry.isDirectory) {
                        output.append("== ").append(entry.name).append(" ==\n")
                        val buffer = ByteArray(16 * 1024)
                        while (output.length < MAX_LOG_CHARS) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            output.append(String(buffer, 0, read, Charsets.UTF_8))
                        }
                        output.append('\n')
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Log archive could not be unpacked: ${e.javaClass.simpleName}")
            return String(bytes, Charsets.UTF_8)
        }
        return output.toString()
    }

    private fun apiUrl(vararg segments: String): String {
        var builder = apiBase.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    /** A web (non-API) URL, used only for `/api/v1/version`. */
    private fun webUrl(vararg segments: String): String {
        var builder = webBase.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    private fun urlBuilder(vararg segments: String) = apiBase.toHttpUrl().newBuilder().apply {
        segments.forEach { addPathSegment(it) }
    }

    private suspend fun <T> getJson(
        builder: okhttp3.HttpUrl.Builder,
        parse: (JsonElement) -> T?
    ): GitResult<T> = getJson(builder.build().toString(), parse)

    private suspend fun <T> getJson(
        url: okhttp3.HttpUrl,
        parse: (JsonElement) -> T?
    ): GitResult<T> = getJson(url.toString(), parse)

    private suspend fun <T> getJson(
        url: String,
        parse: (JsonElement) -> T?
    ): GitResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", JSON_ACCEPT)
            .apply { tokenOrNull()?.let { header("Authorization", "token $it") } }
            .build()
        try {
            urlGuard(url)
            secureClient.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    return@withContext failureFrom(response.code, text)
                }
                val element = runCatching { JsonParser.parseString(text) }.getOrNull()
                    ?: return@withContext GitResult.Failure(
                        GitFailureKind.UNEXPECTED,
                        response.code,
                        "The server did not return JSON"
                    )
                val parsed = parse(element)
                    ?: return@withContext GitResult.Failure(
                        GitFailureKind.UNEXPECTED,
                        response.code,
                        "Unexpected response from the git server"
                    )
                GitResult.Success(parsed)
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Request failed: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Request error: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        }
    }

    private fun downloadBytes(url: String): GitResult<ByteArray> {
        val builder = Request.Builder().url(url).get().header("Accept", ANY_ACCEPT)
        tokenOrNull()?.let { builder.header("Authorization", "token $it") }
        return try {
            urlGuard(url)
            redirectClient.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes()
                if (!response.isSuccessful) {
                    return failureFrom(response.code, null)
                }
                if (bytes == null) {
                    GitResult.Failure(GitFailureKind.UNEXPECTED, response.code, "Empty response")
                } else {
                    GitResult.Success(bytes)
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Download failed: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Download error: ${e.javaClass.simpleName}")
            GitResult.Failure(GitFailureKind.NETWORK)
        }
    }

    private fun tokenOrNull(): String? = tokenProvider.currentToken()?.takeIf { it.isNotBlank() }

    private fun failureFrom(code: Int, body: String?): GitResult.Failure {
        val message = extractMessage(body)
        val kind = when {
            code == 401 -> GitFailureKind.UNAUTHORIZED
            code == 403 -> GitFailureKind.FORBIDDEN
            code == 404 -> GitFailureKind.NOT_FOUND
            code == 409 -> GitFailureKind.CONFLICT
            code == 422 -> GitFailureKind.UNPROCESSABLE
            code == 429 -> GitFailureKind.RATE_LIMITED
            code >= 500 -> GitFailureKind.HTTP
            else -> GitFailureKind.HTTP
        }
        return GitResult.Failure(kind, code, message)
    }

    /**
     * Gitea reports errors as `{"message": "...", "url": "..."}`. The message is kept —
     * it is the only place a "repository does not exist" is distinguished from
     * "token lacks access".
     */
    private fun extractMessage(body: String?): String? {
        val text = body?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
        val message = parsed?.asObjectOrNull()?.string("message")
            ?: parsed?.asObjectOrNull()?.string("error")
            ?: text
        return message.take(MAX_ERROR_MESSAGE)
    }

    private fun decodeContent(content: String?, encoding: String?): ByteArray? {
        val raw = content ?: return null
        val normalized = raw.filterNot { it == '\n' || it == '\r' || it == ' ' }
        return if (encoding.equals("base64", ignoreCase = true) || encoding == null) {
            runCatching { android.util.Base64.decode(normalized, android.util.Base64.DEFAULT) }
                .getOrNull()
        } else {
            normalized.toByteArray(Charsets.UTF_8)
        }
    }

    companion object {
        private const val TAG = "GiteaApi"
        private const val API_SUFFIX = "/api/v1"
        private const val PER_PAGE = 50
        private const val MAX_ERROR_MESSAGE = 500
        private const val MAX_ARCHIVE_BYTES = 300L * 1024 * 1024
        private const val MAX_LOG_CHARS = 400_000
        private const val JSON_ACCEPT = "application/json"
        private const val ANY_ACCEPT = "*/*"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Written explicitly on creation so a new repository always starts on `main`. */
        const val DEFAULT_BRANCH = "main"

        /** First Gitea release that serves the workflow dispatch endpoint this build calls. */
        const val MIN_DISPATCH_VERSION = "1.22"

        /** Server-side cap for one tree page; the loop keeps going while `truncated`. */
        private const val TREE_PAGE_SIZE = 1_000

        /** 50 pages × 1000 entries. Beyond that the tree is reported truncated, never partial. */
        private const val MAX_TREE_PAGES = 50

        /** Gitea's merge strategies, by the neutral name a caller passes. */
        private val GITEA_MERGE_METHODS = mapOf(
            "merge" to "merge",
            "squash" to "squash",
            "rebase" to "rebase",
            "rebase-merge" to "rebase-merge",
            "fast-forward" to "fast-forward-only",
            "fast-forward-only" to "fast-forward-only"
        )

        internal val BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

        // ----------------------------------------------------------------- parsing

        internal fun parseAccount(json: JsonElement): GitAccount? {
            val obj = json.asObjectOrNull() ?: return null
            val login = obj.string("login") ?: return null
            return GitAccount(
                login = login,
                name = obj.string("full_name"),
                avatarUrl = obj.string("avatar_url")
            )
        }

        internal fun parseRepository(json: JsonElement): GitRepository? {
            val obj = json.asObjectOrNull() ?: return null
            val name = obj.string("name") ?: return null
            val owner = obj.obj("owner")?.string("login") ?: return null
            return GitRepository(
                id = obj.long("id") ?: 0L,
                name = name,
                owner = owner,
                fullName = obj.string("full_name") ?: "$owner/$name",
                privateRepo = obj.bool("private") ?: false,
                fork = obj.bool("fork") ?: false,
                defaultBranch = obj.string("default_branch") ?: "main",
                description = obj.string("description"),
                // Gitea has no pushed_at on a repository; updated_at is the closest signal.
                pushedAt = obj.string("updated_at")
            )
        }

        internal fun parseBranch(json: JsonElement): GitBranch? {
            val obj = json.asObjectOrNull() ?: return null
            val name = obj.string("name") ?: return null
            // A Gitea branch carries its commit inline; the sha is the payload commit's id.
            val sha = obj.obj("commit")?.string("id") ?: return null
            return GitBranch(
                name = name,
                sha = sha,
                protectedBranch = obj.bool("protected") ?: false
            )
        }

        internal fun parsePullRequest(json: JsonElement): GitChangeRequest? {
            val obj = json.asObjectOrNull() ?: return null
            val number = obj.int("number") ?: return null
            return GitChangeRequest(
                number = number,
                title = obj.string("title").orEmpty(),
                state = obj.string("state") ?: "open",
                draft = obj.bool("draft") ?: false,
                merged = obj.bool("merged") ?: false,
                author = obj.obj("user")?.string("login").orEmpty(),
                headRef = obj.obj("head")?.string("ref").orEmpty(),
                baseRef = obj.obj("base")?.string("ref").orEmpty(),
                htmlUrl = obj.string("html_url").orEmpty(),
                updatedAt = obj.string("updated_at"),
                body = obj.string("body")
            )
        }

        internal fun parsePullRequestDetails(json: JsonElement): GitChangeRequestDetails? {
            val obj = json.asObjectOrNull() ?: return null
            val pull = parsePullRequest(json) ?: return null
            return GitChangeRequestDetails(
                pull = pull,
                mergeable = obj.bool("mergeable"),
                mergeableState = if (obj.bool("merged") == true) "merged" else null,
                additions = obj.int("additions") ?: 0,
                deletions = obj.int("deletions") ?: 0,
                changedFiles = obj.int("changed_files") ?: 0,
                commitCount = 0,
                createdAt = obj.string("created_at"),
                mergedAt = obj.string("merged_at"),
                closedAt = obj.string("closed_at"),
                headSha = obj.obj("head")?.string("sha").orEmpty(),
                baseSha = obj.obj("base")?.string("sha")
            )
        }

        internal fun parsePullRequestFile(json: JsonElement): GitChangeRequestFile? {
            val obj = json.asObjectOrNull() ?: return null
            val filename = obj.string("filename") ?: return null
            return GitChangeRequestFile(
                filename = filename,
                status = obj.string("status") ?: "modified",
                additions = obj.int("additions") ?: 0,
                deletions = obj.int("deletions") ?: 0,
                changes = obj.int("changes") ?: 0,
                previousFilename = obj.string("previous_filename"),
                // Gitea's file list does not include a unified patch.
                patch = null
            )
        }

        internal fun parseCommitStatus(json: JsonElement): GitCheck? {
            val obj = json.asObjectOrNull() ?: return null
            val status = obj.string("status") ?: return null
            val pending = status.equals("pending", ignoreCase = true)
            return GitCheck(
                name = obj.string("context") ?: obj.string("description") ?: "status",
                status = if (pending) "queued" else "completed",
                conclusion = if (pending) null else status,
                detailsUrl = obj.string("target_url")
            )
        }

        internal fun parseCommit(json: JsonElement): GitCommitSummary? {
            val obj = json.asObjectOrNull() ?: return null
            val sha = obj.string("sha") ?: return null
            val commit = obj.obj("commit")
            return GitCommitSummary(
                sha = sha,
                message = commit?.string("message").orEmpty(),
                authorName = commit?.obj("author")?.string("name").orEmpty(),
                authorDate = commit?.obj("author")?.string("date"),
                htmlUrl = obj.string("html_url")
            )
        }

        internal fun parseWorkflow(json: JsonElement): GitPipeline? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.idString("id") ?: return null
            return GitPipeline(
                id = id,
                name = obj.string("name") ?: id,
                path = obj.string("path").orEmpty(),
                state = obj.string("state")
            )
        }

        internal fun parseRun(json: JsonElement): GitPipelineRun? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            // Gitea does not repeat the workflow id on a run; the path identifies it.
            return GitPipelineRun(
                id = id,
                name = obj.string("name") ?: obj.string("display_title").orEmpty(),
                workflowId = "",
                workflowPath = obj.string("path"),
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

        internal fun parseJob(json: JsonElement): GitRunJob? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            val steps = obj.arr("steps")?.mapNotNull { element ->
                element.asObjectOrNull()?.let { step ->
                    GitRunStep(
                        number = step.int("number") ?: 0,
                        name = step.string("name").orEmpty(),
                        status = step.string("status") ?: "unknown",
                        conclusion = step.string("conclusion")
                    )
                }
            }.orEmpty()
            return GitRunJob(
                id = id,
                name = obj.string("name").orEmpty(),
                status = obj.string("status") ?: "unknown",
                conclusion = obj.string("conclusion"),
                startedAt = obj.string("started_at"),
                completedAt = obj.string("completed_at"),
                steps = steps
            )
        }

        internal fun parseArtifact(
            apiBase: String,
            owner: String,
            repo: String,
            json: JsonObject
        ): GitArtifact? {
            val id = json.long("id") ?: return null
            val name = json.string("name") ?: return null
            return GitArtifact(
                id = id,
                name = name,
                sizeInBytes = json.long("size_in_bytes") ?: 0L,
                expired = json.bool("expired") ?: false,
                // The API zip route serves the binary with the stored token; the
                // archive_download_url is a browser route that may require a session.
                archiveDownloadUrl = "$apiBase/repos/$owner/$repo/actions/artifacts/$id/zip",
                createdAt = json.string("created_at"),
                workflowRunId = json.obj("workflow_run")?.long("id")
            )
        }

        internal fun parseRelease(json: JsonElement): GitRelease? {
            val obj = json.asObjectOrNull() ?: return null
            val id = obj.long("id") ?: return null
            val tag = obj.string("tag_name") ?: return null
            val assets = obj.arr("assets")?.mapNotNull { element ->
                element.asObjectOrNull()?.let { asset ->
                    val assetId = asset.long("id") ?: return@let null
                    val name = asset.string("name") ?: return@let null
                    val url = asset.string("browser_download_url") ?: return@let null
                    GitReleaseAsset(
                        id = assetId,
                        name = name,
                        sizeInBytes = asset.long("size") ?: 0L,
                        contentType = null,
                        // Gitea serves release assets from the web route, not the API.
                        apiDownloadUrl = url,
                        state = null
                    )
                }
            }.orEmpty()
            return GitRelease(
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

    }
}

/** The commit sha and tree sha of one git commit, as Gitea reports them. */
internal data class GitCommitTree(val commitSha: String, val treeSha: String)

/**
 * Streams the `change-files` request body.
 *
 * The body is written straight to the socket instead of being built as one string: a
 * change set can carry tens of megabytes, and materialising it twice (bytes plus the
 * base64 text plus the JSON string) is how a phone runs out of memory during a push.
 * Since each file's content is already in memory, the only extra allocation here is the
 * `Message` and the per-character JSON escaping.
 */
private class ChangeFilesBody(
    private val message: String,
    private val branch: String,
    private val newBranch: String?,
    private val changes: List<GitFileChange>
) : RequestBody() {

    override fun contentType() = "application/json; charset=utf-8".toMediaType()

    /** Unknown length: the body is streamed chunked. */
    override fun contentLength(): Long = -1L

    override fun writeTo(sink: BufferedSink) {
        val out = sink.outputStream().writer(Charsets.UTF_8)
        out.write("{\"message\":")
        writeJsonString(out, message)
        out.write(",\"branch\":")
        writeJsonString(out, branch)
        if (newBranch != null) {
            out.write(",\"new_branch\":")
            writeJsonString(out, newBranch)
        }
        // `force_push` is deliberately absent: the schema defaults it to false, and a
        // publish must never overwrite a branch that already exists.
        out.write(",\"files\":[")
        changes.forEachIndexed { index, change ->
            if (index > 0) out.write(",")
            out.write("{\"path\":")
            writeJsonString(out, change.path)
            out.write(",\"operation\":\"")
            // "create" fails when the path appeared remotely, "update" fails when its
            // blob sha no longer matches, and both are refused instead of overwritten.
            out.write(
                when {
                    change.content == null -> "delete"
                    change.baseSha == null && change.upsert -> "upload"
                    change.baseSha == null -> "create"
                    else -> "update"
                }
            )
            out.write("\"")
            val content = change.content
            if (content != null) {
                out.write(",\"content\":\"")
                writeBase64(out, content)
                out.write("\"")
            }
            change.baseSha?.let {
                out.write(",\"sha\":")
                writeJsonString(out, it)
            }
            out.write("}")
        }
        out.write("]}")
        out.flush()
    }
}

/** Writes [value] as a JSON string, escaping only what JSON requires. */
private fun writeJsonString(out: Writer, value: String) {
    out.write('"'.code)
    for (character in value) {
        when (character) {
            '"' -> out.write("\\\"")
            '\\' -> out.write("\\\\")
            '\n' -> out.write("\\n")
            '\r' -> out.write("\\r")
            '\t' -> out.write("\\t")
            '\b' -> out.write("\\b")
            '\u000C' -> out.write("\\f")
            else -> if (character < ' ') {
                out.write("\\u")
                out.write(Integer.toHexString(character.code).padStart(4, '0'))
            } else {
                out.write(character.code)
            }
        }
    }
    out.write('"'.code)
}

/** Writes [bytes] as standard base64, without line breaks. */
private fun writeBase64(out: Writer, bytes: ByteArray) {
    val alphabet = GiteaApiClient.BASE64_ALPHABET
    var index = 0
    val full = bytes.size - (bytes.size % 3)
    while (index < full) {
        val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            (bytes[index + 2].toInt() and 0xFF)
        out.write(alphabet[(chunk ushr 18) and 0x3F].code)
        out.write(alphabet[(chunk ushr 12) and 0x3F].code)
        out.write(alphabet[(chunk ushr 6) and 0x3F].code)
        out.write(alphabet[chunk and 0x3F].code)
        index += 3
    }
    when (bytes.size - index) {
        1 -> {
            val chunk = (bytes[index].toInt() and 0xFF) shl 16
            out.write(alphabet[(chunk ushr 18) and 0x3F].code)
            out.write(alphabet[(chunk ushr 12) and 0x3F].code)
            out.write('='.code)
            out.write('='.code)
        }
        2 -> {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8)
            out.write(alphabet[(chunk ushr 18) and 0x3F].code)
            out.write(alphabet[(chunk ushr 12) and 0x3F].code)
            out.write(alphabet[(chunk ushr 6) and 0x3F].code)
            out.write('='.code)
        }
    }
}
