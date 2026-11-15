package com.sherwood.wta.core.studio.git

import android.content.Context
import com.sherwood.wta.core.frontend.GitDataApiClient
import com.sherwood.wta.core.frontend.GitTokenProvider
import com.sherwood.wta.core.network.NetworkModule
import com.sherwood.wta.core.studio.EndpointConfig
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.GitHubApiClient

/**
 * GitHub as a [GitProvider].
 *
 * This is deliberately a thin adapter: every call goes to the client the Studio has used
 * since the first release, so the split into a provider layer changes no request, no
 * header and no error message. What it does change is who knows about GitHub — from here
 * on only this file and the GitHub-named agent tools do.
 *
 * It also keeps GitHub's two API surfaces together: the REST client for repositories,
 * pull requests, Actions and releases, and the Git Data API client used to publish a
 * commit without a local repository.
 */
class GitHubProvider(
    private val context: Context,
    private val endpoints: EndpointConfig = EndpointSettingsStore.current(context),
    private val tokenProvider: GitTokenProvider =
        GitCredentialStore(context).tokenProvider(
            GitCredentialKey(GitProviderId.GITHUB, GITHUB_HOST)
        )
) : GitProvider {

    private val api = GitHubApiClient(context, endpoints, tokenProvider)

    /**
     * Kept lazy so a read-only Studio session never builds the publishing client, and so
     * the Git Data API base follows the same configured API base as the REST client.
     */
    private val dataClient: GitDataApiClient by lazy {
        GitDataApiClient(
            context = context,
            tokenProvider = tokenProvider,
            httpClient = NetworkModule.downloadClient,
            apiBaseUrl = endpoints.githubApiBaseUrl
        )
    }

    override val id: GitProviderId = GitProviderId.GITHUB

    override val baseUrl: String = endpoints.githubWebBaseUrl.trimEnd('/')

    override val capabilities: GitCapabilities = CAPABILITIES

    override suspend fun account(): GitResult<GitAccount> = api.currentUser()

    override suspend fun repositories(page: Int): GitResult<List<GitRepository>> =
        api.listRepositories(page)

    override suspend fun repository(ref: GitRepoRef): GitResult<GitRepository> =
        api.getRepository(ref.owner, ref.name)

    override suspend fun createRepository(request: NewGitRepository): GitResult<GitRepository> =
        api.createRepository(
            name = request.name,
            description = request.description,
            privateRepository = request.privateRepository,
            autoInit = request.autoInit,
            homepage = request.homepage
        )

    override suspend fun branches(ref: GitRepoRef): GitResult<List<GitBranch>> =
        api.listBranches(ref.owner, ref.name)

    override suspend fun branch(ref: GitRepoRef, name: String): GitResult<GitBranch> =
        api.getBranch(ref.owner, ref.name, name)

    override suspend fun createBranch(
        ref: GitRepoRef,
        name: String,
        fromSha: String
    ): GitResult<GitBranch> = api.createBranch(ref.owner, ref.name, name, fromSha)

    override suspend fun mergeBranches(
        ref: GitRepoRef,
        base: String,
        head: String,
        message: String?
    ): GitResult<GitMergeResult> = api.mergeBranches(ref.owner, ref.name, base, head, message)

    override suspend fun changeRequests(
        ref: GitRepoRef,
        state: String
    ): GitResult<List<GitChangeRequest>> = api.listPullRequests(ref.owner, ref.name, state)

    override suspend fun changeRequest(
        ref: GitRepoRef,
        number: Int
    ): GitResult<GitChangeRequestDetails> = api.getPullRequest(ref.owner, ref.name, number)

    override suspend fun changeRequestFiles(
        ref: GitRepoRef,
        number: Int
    ): GitResult<List<GitChangeRequestFile>> =
        api.listPullRequestFiles(ref.owner, ref.name, number)

    override suspend fun createChangeRequest(
        ref: GitRepoRef,
        request: NewGitChangeRequest
    ): GitResult<GitChangeRequest> = api.createPullRequest(
        owner = ref.owner,
        repo = ref.name,
        title = request.title,
        head = request.head,
        base = request.base,
        body = request.body,
        draft = request.draft
    )

    override suspend fun mergeChangeRequest(
        ref: GitRepoRef,
        number: Int,
        commitTitle: String?,
        method: String
    ): GitResult<GitMergeResult> =
        api.mergePullRequest(ref.owner, ref.name, number, commitTitle, method)

    override suspend fun checks(ref: GitRepoRef, revision: String): GitResult<GitCheckSummary> =
        api.getCommitChecks(ref.owner, ref.name, revision)

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
    ): GitResult<GitFileContent> = api.getFileContent(ref.owner, ref.name, path, at)

    override suspend fun pipelines(ref: GitRepoRef): GitResult<List<GitPipeline>> =
        api.listWorkflows(ref.owner, ref.name)

    override suspend fun runs(
        ref: GitRepoRef,
        branch: String?,
        perPage: Int
    ): GitResult<List<GitPipelineRun>> = api.listWorkflowRuns(ref.owner, ref.name, perPage, branch)

    override suspend fun run(ref: GitRepoRef, runId: Long): GitResult<GitPipelineRun> =
        api.getWorkflowRun(ref.owner, ref.name, runId)

    override suspend fun dispatch(
        ref: GitRepoRef,
        pipelineId: String,
        branch: String,
        inputs: Map<String, String>
    ): GitResult<Unit> = api.dispatchWorkflow(ref.owner, ref.name, pipelineId, branch, inputs)

    override suspend fun jobs(ref: GitRepoRef, runId: Long): GitResult<List<GitRunJob>> =
        api.listJobs(ref.owner, ref.name, runId)

    override suspend fun runLogs(ref: GitRepoRef, runId: Long): GitResult<String> =
        api.downloadRunLogs(ref.owner, ref.name, runId)

    override suspend fun runArtifacts(ref: GitRepoRef, runId: Long): GitResult<List<GitArtifact>> =
        api.listRunArtifacts(ref.owner, ref.name, runId)

    override suspend fun downloadArtifact(artifact: GitArtifact): GitResult<ByteArray> =
        api.downloadArtifact(artifact)

    override suspend fun releases(ref: GitRepoRef, perPage: Int): GitResult<List<GitRelease>> =
        api.listReleases(ref.owner, ref.name, perPage)

    override suspend fun downloadReleaseAsset(asset: GitReleaseAsset): GitResult<ByteArray> =
        api.downloadReleaseAsset(asset)

    override fun commitWriter(): GitCommitWriter = GitHubCommitWriter(dataClient)

    companion object {
        /** GitHub.com's host; a GitHub Enterprise server would need its own slot. */
        private const val GITHUB_HOST = "github.com"

        /**
         * GitHub can do everything the Studio currently offers. `stagedCommit` is true
         * because the Git Data API builds a commit object before any ref moves, which is
         * what lets the publisher record a pending base commit first.
         */
        val CAPABILITIES = GitCapabilities(
            changeRequestTerm = GitChangeRequestTerm.PULL_REQUEST,
            reviews = true,
            inlineComments = true,
            checks = true,
            pipelines = true,
            pipelineDispatch = true,
            runLogs = true,
            artifacts = true,
            releases = true,
            binaryReleaseAssets = true,
            createRepository = true,
            stagedCommit = true,
            oauthDeviceFlow = true
        )
    }
}
