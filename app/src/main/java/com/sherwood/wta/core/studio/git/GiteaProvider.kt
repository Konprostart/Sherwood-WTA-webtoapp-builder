package com.sherwood.wta.core.studio.git

import com.sherwood.wta.core.frontend.GitHubUrlPolicy
import com.sherwood.wta.core.frontend.GitTokenProvider
import com.sherwood.wta.core.network.NetworkModule
import okhttp3.OkHttpClient
import java.io.File

/**
 * Gitea / Forgejo as a [GitProvider].
 *
 * Read and write, within what the server's API actually offers: browse repositories and
 * branches, read pull requests and their files, see commit statuses, import a branch,
 * download build artifacts and releases — and, in the other direction, create a
 * repository, create or delete a branch, open, review and merge a pull request, dispatch a
 * workflow, and publish a commit through the atomic `change-files` endpoint
 * ([GiteaCommitWriter]).
 *
 * Three things are deliberately *not* offered, and the capability flags say so:
 *
 * - **Merging two branches outside a pull request.** Gitea has no such endpoint; merging
 *   goes through a pull request.
 * - **Line-anchored review comments.** They need the diff positions of a pull request,
 *   which this client does not compute; a review with a body and an approve/request-change
 *   verdict is available instead.
 * - **Forcing a ref.** No call sends `force_push`, so a branch is never rewritten.
 *
 * Forgejo shares Gitea's API for everything used here, so both ids resolve to this class.
 * The id is kept separate so the UI can name the host the user actually configured.
 */
internal class GiteaProvider(
    override val id: GitProviderId,
    baseUrl: String,
    tokenProvider: GitTokenProvider,
    httpClient: OkHttpClient = NetworkModule.downloadClient,
    urlGuard: (String) -> Unit = { GitHubUrlPolicy.requireHttps(it) }
) : GitProvider {

    override val baseUrl: String = baseUrl.trimEnd('/')

    private val api = GiteaApiClient(
        baseUrl = this.baseUrl,
        tokenProvider = tokenProvider,
        httpClient = httpClient,
        urlGuard = urlGuard
    )

    private val writer: GitCommitWriter by lazy { GiteaCommitWriter(api) }

    override val capabilities: GitCapabilities = CAPABILITIES

    // ----------------------------------------------------------------- account

    override suspend fun account(): GitResult<GitAccount> = api.currentUser()

    override suspend fun serverInfo(): GitResult<GitServerInfo> {
        val account = when (val user = api.currentUser()) {
            is GitResult.Success -> user.value
            is GitResult.Failure -> return user
        }
        // A version lookup is informational: a server that does not answer it is still
        // usable, so the failure is not propagated.
        val version = (api.serverVersion() as? GitResult.Success)?.value
        return GitResult.Success(
            GitServerInfo(
                provider = id,
                baseUrl = baseUrl,
                login = account.login,
                serverVersion = version
            )
        )
    }

    // ------------------------------------------------------------ repositories

    override suspend fun repositories(page: Int): GitResult<List<GitRepository>> =
        api.listRepositories(page)

    override suspend fun repository(ref: GitRepoRef): GitResult<GitRepository> =
        api.getRepository(ref.owner, ref.name)

    override suspend fun createRepository(request: NewGitRepository): GitResult<GitRepository> =
        api.createRepository(request)

    // ----------------------------------------------------------------- branches

    override suspend fun branches(ref: GitRepoRef): GitResult<List<GitBranch>> =
        api.listBranches(ref.owner, ref.name)

    override suspend fun branch(ref: GitRepoRef, name: String): GitResult<GitBranch> =
        api.getBranch(ref.owner, ref.name, name)

    override suspend fun createBranch(
        ref: GitRepoRef,
        name: String,
        fromSha: String
    ): GitResult<GitBranch> = api.createBranch(ref.owner, ref.name, name, fromSha)

    /** Gitea merges only through a pull request; there is no branch-merge endpoint. */
    override suspend fun mergeBranches(
        ref: GitRepoRef,
        base: String,
        head: String,
        message: String?
    ): GitResult<GitMergeResult> = unsupported(
        "Merging two branches directly",
        "Gitea and Forgejo merge through a pull request, so open one and merge that."
    )

    // ------------------------------------------------------------ change requests

    override suspend fun changeRequests(
        ref: GitRepoRef,
        state: String
    ): GitResult<List<GitChangeRequest>> =
        api.listPullRequests(ref.owner, ref.name, state)

    override suspend fun changeRequest(
        ref: GitRepoRef,
        number: Int
    ): GitResult<GitChangeRequestDetails> =
        api.getPullRequest(ref.owner, ref.name, number)

    override suspend fun changeRequestFiles(
        ref: GitRepoRef,
        number: Int
    ): GitResult<List<GitChangeRequestFile>> =
        api.listPullRequestFiles(ref.owner, ref.name, number)

    override suspend fun createChangeRequest(
        ref: GitRepoRef,
        request: NewGitChangeRequest
    ): GitResult<GitChangeRequest> =
        api.createPullRequest(ref.owner, ref.name, request)

    override suspend fun mergeChangeRequest(
        ref: GitRepoRef,
        number: Int,
        commitTitle: String?,
        method: String
    ): GitResult<GitMergeResult> =
        api.mergePullRequest(ref.owner, ref.name, number, commitTitle, method)

    override suspend fun checks(ref: GitRepoRef, revision: String): GitResult<GitCheckSummary> =
        api.combinedStatus(ref.owner, ref.name, revision)

    override suspend fun submitReview(
        ref: GitRepoRef,
        number: Int,
        body: String,
        requestChanges: Boolean
    ): GitResult<Unit> = api.submitReview(ref.owner, ref.name, number, body, requestChanges)

    /** Line comments need the pull request's diff positions, which this client does not build. */
    override suspend fun addReviewComment(
        ref: GitRepoRef,
        number: Int,
        commitId: String,
        path: String,
        line: Int,
        body: String
    ): GitResult<Unit> = unsupported(
        "Line-anchored review comments",
        "Review a pull request with an overall comment and an approve / request-changes " +
            "verdict instead; per-line comments are not implemented for this server."
    )

    // ------------------------------------------------------------------ commits

    override suspend fun commits(
        ref: GitRepoRef,
        branch: String?,
        perPage: Int
    ): GitResult<List<GitCommitSummary>> =
        api.listCommits(ref.owner, ref.name, branch, perPage)

    override suspend fun fileContent(
        ref: GitRepoRef,
        path: String,
        at: String
    ): GitResult<GitFileContent> = api.fileContent(ref.owner, ref.name, path, at)

    // ---------------------------------------------------------------- pipelines

    override suspend fun pipelines(ref: GitRepoRef): GitResult<List<GitPipeline>> =
        api.listWorkflows(ref.owner, ref.name)

    override suspend fun runs(
        ref: GitRepoRef,
        branch: String?,
        perPage: Int
    ): GitResult<List<GitPipelineRun>> =
        api.listRuns(ref.owner, ref.name, branch, perPage)

    override suspend fun run(ref: GitRepoRef, runId: Long): GitResult<GitPipelineRun> =
        api.getRun(ref.owner, ref.name, runId)

    override suspend fun dispatch(
        ref: GitRepoRef,
        pipelineId: String,
        branch: String,
        inputs: Map<String, String>
    ): GitResult<Unit> = api.dispatchWorkflow(ref.owner, ref.name, pipelineId, branch, inputs)

    override suspend fun jobs(ref: GitRepoRef, runId: Long): GitResult<List<GitRunJob>> =
        api.listJobs(ref.owner, ref.name, runId)

    override suspend fun runLogs(ref: GitRepoRef, runId: Long): GitResult<String> =
        api.runLogs(ref.owner, ref.name, runId)

    override suspend fun runArtifacts(ref: GitRepoRef, runId: Long): GitResult<List<GitArtifact>> =
        api.listRunArtifacts(ref.owner, ref.name, runId)

    override suspend fun downloadArtifact(artifact: GitArtifact): GitResult<ByteArray> =
        api.downloadArtifact(artifact)

    // ----------------------------------------------------------------- releases

    override suspend fun releases(ref: GitRepoRef, perPage: Int): GitResult<List<GitRelease>> =
        api.listReleases(ref.owner, ref.name, perPage)

    override suspend fun downloadReleaseAsset(asset: GitReleaseAsset): GitResult<ByteArray> =
        api.downloadReleaseAsset(asset)

    // ------------------------------------------------------------------- writes

    override suspend fun downloadArchive(
        ref: GitRepoRef,
        branch: String,
        target: File,
        onProgress: (Float) -> Unit
    ): GitResult<Unit> = api.downloadArchive(ref.owner, ref.name, branch, target, onProgress)

    override fun commitWriter(): GitCommitWriter = writer

    /**
     * One uniform answer for a write the server genuinely cannot serve, so the message
     * names the host, the missing feature and the alternative instead of surfacing a bare
     * HTTP status.
     */
    private fun <T> unsupported(feature: String, alternative: String): GitResult<T> =
        GitResult.Failure(
            GitFailureKind.UNSUPPORTED,
            message = "$feature is not available on ${if (id == GitProviderId.FORGEJO) {
                "Forgejo"
            } else {
                "Gitea"
            }}. $alternative"
        )

    companion object {
        /**
         * What the server actually supports. Every flag is backed by a call in
         * [GiteaApiClient]; the two that stay false have no endpoint to back them.
         */
        val CAPABILITIES = GitCapabilities(
            changeRequestTerm = GitChangeRequestTerm.PULL_REQUEST,
            reviews = true,
            // Per-line comments need the pull request's diff positions.
            inlineComments = false,
            checks = true,
            pipelines = true,
            pipelineDispatch = true,
            runLogs = true,
            artifacts = true,
            releases = true,
            binaryReleaseAssets = true,
            createRepository = true,
            // Personal access tokens only: Gitea has no OAuth device flow.
            oauthDeviceFlow = false,
            createBranches = true,
            // No branch-merge endpoint; merging happens through a pull request.
            mergeBranches = false,
            createChangeRequests = true,
            mergeChangeRequests = true,
            // Gitea's create endpoint has no draft field.
            draftChangeRequests = false
        )

        /** Minimum server version whose Actions API this driver can read. */
        const val MIN_ACTIONS_VERSION = "1.22"
    }
}
