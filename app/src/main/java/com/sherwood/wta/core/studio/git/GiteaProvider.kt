package com.sherwood.wta.core.studio.git

import com.sherwood.wta.core.frontend.GitHubUrlPolicy
import com.sherwood.wta.core.frontend.GitTokenProvider
import com.sherwood.wta.core.network.NetworkModule
import okhttp3.OkHttpClient
import java.io.File

/**
 * Gitea / Forgejo as a [GitProvider].
 *
 * This driver is deliberately **read-only** in this build. Reading is what makes the
 * Studio useful on a self-hosted server: browse repositories and branches, read pull
 * requests and their files, see commit statuses, import a branch, and pull build
 * artifacts and releases into the vault. Writing (creating a repository, opening or
 * merging a pull request, pushing a commit, dispatching a workflow) is not implemented
 * yet, and every one of those methods fails with [GitFailureKind.UNSUPPORTED] instead of
 * pointing at some other host.
 *
 * The capability flags are the single source of truth for that: a screen that reads them
 * hides a control it cannot honour and says why, rather than letting the user tap a
 * button that always fails.
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
        unsupported("Creating a repository on Gitea/Forgejo")

    // ----------------------------------------------------------------- branches

    override suspend fun branches(ref: GitRepoRef): GitResult<List<GitBranch>> =
        api.listBranches(ref.owner, ref.name)

    override suspend fun branch(ref: GitRepoRef, name: String): GitResult<GitBranch> =
        api.getBranch(ref.owner, ref.name, name)

    override suspend fun createBranch(
        ref: GitRepoRef,
        name: String,
        fromSha: String
    ): GitResult<GitBranch> = unsupported("Creating a branch on Gitea/Forgejo")

    override suspend fun mergeBranches(
        ref: GitRepoRef,
        base: String,
        head: String,
        message: String?
    ): GitResult<GitMergeResult> = unsupported("Merging branches on Gitea/Forgejo")

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
    ): GitResult<GitChangeRequest> = unsupported("Opening a pull request on Gitea/Forgejo")

    override suspend fun mergeChangeRequest(
        ref: GitRepoRef,
        number: Int,
        commitTitle: String?,
        method: String
    ): GitResult<GitMergeResult> = unsupported("Merging a pull request on Gitea/Forgejo")

    override suspend fun checks(ref: GitRepoRef, revision: String): GitResult<GitCheckSummary> =
        api.combinedStatus(ref.owner, ref.name, revision)

    override suspend fun submitReview(
        ref: GitRepoRef,
        number: Int,
        body: String,
        requestChanges: Boolean
    ): GitResult<Unit> = unsupported("Reviewing a pull request on Gitea/Forgejo")

    override suspend fun addReviewComment(
        ref: GitRepoRef,
        number: Int,
        commitId: String,
        path: String,
        line: Int,
        body: String
    ): GitResult<Unit> = unsupported("Commenting on a pull request on Gitea/Forgejo")

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
    ): GitResult<Unit> = unsupported("Dispatching a workflow on Gitea/Forgejo")

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

    /** Null: no publish path exists for this host in this build. */
    override fun commitWriter(): GitCommitWriter? = null

    /**
     * One uniform answer for every write this driver does not implement, so the message
     * names the host and the missing feature instead of surfacing a bare HTTP status.
     */
    private fun <T> unsupported(feature: String): GitResult<T> = GitResult.Failure(
        GitFailureKind.UNSUPPORTED,
        message = "$feature is not available in this build. Reading, importing and " +
            "downloading build artifacts work; creating or changing anything on a " +
            "Gitea/Forgejo server is planned for a later release."
    )

    companion object {
        /**
         * Read-only, stated exactly: everything that only reads is on, every operation
         * that would change the server is off.
         */
        val CAPABILITIES = GitCapabilities(
            changeRequestTerm = GitChangeRequestTerm.PULL_REQUEST,
            reviews = false,
            inlineComments = false,
            checks = true,
            pipelines = true,
            pipelineDispatch = false,
            runLogs = true,
            artifacts = true,
            releases = true,
            binaryReleaseAssets = true,
            createRepository = false,
            oauthDeviceFlow = false,
            createBranches = false,
            mergeBranches = false,
            createChangeRequests = false,
            mergeChangeRequests = false
        )

        /** Minimum server version whose Actions API this driver can read. */
        const val MIN_ACTIONS_VERSION = "1.22"
    }
}
