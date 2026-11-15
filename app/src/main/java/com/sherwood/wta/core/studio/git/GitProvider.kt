package com.sherwood.wta.core.studio.git

import java.io.File

/**
 * The hosting platform a [GitProvider] speaks to.
 *
 * [storageId] is the stable lower-case name written into provenance files. It is separate
 * from the enum name so a rename of the constant can never silently invalidate an
 * existing working copy.
 *
 * Adding an entry here does not make it usable: [GitProviderRegistry] only hands out a
 * driver that is actually implemented in this build, and reports the rest as
 * unavailable instead of pretending to connect.
 */
enum class GitProviderId(val storageId: String) {
    GITHUB("github"),
    GITEA("gitea"),
    FORGEJO("forgejo")
}

/** How a provider names its change requests, so the UI and the agent can say the right word. */
enum class GitChangeRequestTerm {
    PULL_REQUEST,
    MERGE_REQUEST
}

/**
 * What a provider can actually do.
 *
 * The Studio reads this instead of testing `provider == GITHUB`: a screen hides the
 * section a provider cannot serve and explains why, rather than showing a control that
 * would always fail. A capability is a statement about the host's API, not a promise
 * that a particular repository has data.
 *
 * The write capabilities are separate flags rather than one "writable" switch because a
 * driver can be read-only in some respects and writable in others. Gitea in this build is
 * the read/write split's first case: everything is readable, nothing is written yet.
 */
data class GitCapabilities(
    val changeRequestTerm: GitChangeRequestTerm,
    /** Reviews / approvals on a change request. */
    val reviews: Boolean,
    /** Line-anchored review comments. */
    val inlineComments: Boolean,
    /** Per-commit CI state. */
    val checks: Boolean,
    /** CI pipelines and their runs. */
    val pipelines: Boolean,
    /** Manual dispatch of a pipeline. */
    val pipelineDispatch: Boolean,
    /** Logs of a run. */
    val runLogs: Boolean,
    /** Build artifacts downloadable from a run. */
    val artifacts: Boolean,
    /** Releases. */
    val releases: Boolean,
    /** Releases carry the binary itself, not just links. */
    val binaryReleaseAssets: Boolean,
    /** Creating a repository through the API. */
    val createRepository: Boolean,
    /** OAuth device flow, as an alternative to a personal access token. */
    val oauthDeviceFlow: Boolean,
    /** Creating a branch on the host. */
    val createBranches: Boolean,
    /** Server-side merge of one branch into another. */
    val mergeBranches: Boolean,
    /** Opening a pull/merge request from the API. */
    val createChangeRequests: Boolean,
    /** Merging a pull/merge request from the API. */
    val mergeChangeRequests: Boolean,
    /**
     * Creating a pull/merge request directly as a draft. Gitea's create endpoint has no
     * draft field, so the UI hides the toggle instead of letting it silently do nothing.
     */
    val draftChangeRequests: Boolean
)

/**
 * A repository identity.
 *
 * [owner] is the top-level namespace and never contains a separator, which is what
 * every supported host uses. It is validated on construction so a value that reached
 * this type is safe to paste into an API path and into a directory name.
 */
data class GitRepoRef(val owner: String, val name: String) {

    val path: String get() = "$owner/$name"

    override fun toString(): String = path

    companion object {
        private val SEGMENT = Regex("^[A-Za-z0-9._-]{1,100}$")

        /** Null when [owner] or [name] is not a single valid path segment. */
        fun of(owner: String, name: String): GitRepoRef? {
            val cleanOwner = owner.trim()
            val cleanName = name.trim()
            if (!isSegment(cleanOwner) || !isSegment(cleanName)) return null
            return GitRepoRef(cleanOwner, cleanName)
        }

        /** Parses `owner/name`. Exactly two segments — a deeper path is not a repository. */
        fun parse(raw: String): GitRepoRef? {
            val parts = raw.trim().split('/')
            if (parts.size != 2) return null
            return of(parts[0], parts[1])
        }

        private fun isSegment(value: String): Boolean {
            if (!SEGMENT.matches(value)) return false
            // "." and ".." match the character class but are traversal, not names.
            return value != "." && value != ".."
        }
    }
}

/** A repository to create; [autoInit] makes the host write the initial commit. */
data class NewGitRepository(
    val name: String,
    val description: String? = null,
    val privateRepository: Boolean = false,
    val autoInit: Boolean = true,
    val homepage: String? = null
)

/** A change request to open. [head] and [base] are branch names. */
data class NewGitChangeRequest(
    val title: String,
    val head: String,
    val base: String,
    val body: String? = null,
    val draft: Boolean = false
)

/** What a "Test connection" reports. [serverVersion] is null when the host has no such endpoint. */
data class GitServerInfo(
    val provider: GitProviderId,
    val baseUrl: String,
    val login: String?,
    val serverVersion: String?
)

/**
 * Everything the Studio does with a git host, expressed without naming one.
 *
 * The UI, the build pipeline and the agent tools call this interface. Which host
 * answers is decided once, by [GitProviderRegistry], from the saved provider setting.
 *
 * Implementations are the only place that knows a host's URL shapes, headers and
 * vocabulary. They must keep the same guarantees the GitHub client already had: the
 * token never leaves the `Authorization` header, non-HTTPS URLs are refused, failures
 * are mapped to [GitFailureKind] so the caller can tell "no token" from "no access"
 * from "rate limited", and no token is ever logged.
 */
interface GitProvider {

    val id: GitProviderId

    /** Web base URL of the host, e.g. `https://github.com` or `https://git.example.com`. */
    val baseUrl: String

    val capabilities: GitCapabilities

    // ----------------------------------------------------------------- account

    suspend fun account(): GitResult<GitAccount>

    /**
     * Identity plus server version, for the settings screen's "Test connection".
     *
     * Never used for authorisation decisions: a failure here is reported to the user and
     * nothing else changes.
     */
    suspend fun serverInfo(): GitResult<GitServerInfo>

    // ------------------------------------------------------------ repositories

    suspend fun repositories(page: Int = 1): GitResult<List<GitRepository>>

    suspend fun repository(ref: GitRepoRef): GitResult<GitRepository>

    suspend fun createRepository(request: NewGitRepository): GitResult<GitRepository>

    // ----------------------------------------------------------------- branches

    suspend fun branches(ref: GitRepoRef): GitResult<List<GitBranch>>

    suspend fun branch(ref: GitRepoRef, name: String): GitResult<GitBranch>

    suspend fun createBranch(ref: GitRepoRef, name: String, fromSha: String): GitResult<GitBranch>

    /** Server-side merge of [head] into [base]. */
    suspend fun mergeBranches(
        ref: GitRepoRef,
        base: String,
        head: String,
        message: String? = null
    ): GitResult<GitMergeResult>

    // ------------------------------------------------------------ change requests

    suspend fun changeRequests(
        ref: GitRepoRef,
        state: String = "open"
    ): GitResult<List<GitChangeRequest>>

    suspend fun changeRequest(ref: GitRepoRef, number: Int): GitResult<GitChangeRequestDetails>

    suspend fun changeRequestFiles(
        ref: GitRepoRef,
        number: Int
    ): GitResult<List<GitChangeRequestFile>>

    suspend fun createChangeRequest(
        ref: GitRepoRef,
        request: NewGitChangeRequest
    ): GitResult<GitChangeRequest>

    suspend fun mergeChangeRequest(
        ref: GitRepoRef,
        number: Int,
        commitTitle: String? = null,
        method: String = "merge"
    ): GitResult<GitMergeResult>

    suspend fun checks(ref: GitRepoRef, revision: String): GitResult<GitCheckSummary>

    /** Submits a review (approve / request changes / plain comment) on a change request. */
    suspend fun submitReview(
        ref: GitRepoRef,
        number: Int,
        body: String,
        requestChanges: Boolean
    ): GitResult<Unit>

    /** Adds one line-anchored review comment to a changed file. */
    suspend fun addReviewComment(
        ref: GitRepoRef,
        number: Int,
        commitId: String,
        path: String,
        line: Int,
        body: String
    ): GitResult<Unit>

    // ------------------------------------------------------------------ commits

    suspend fun commits(
        ref: GitRepoRef,
        branch: String? = null,
        perPage: Int = 50
    ): GitResult<List<GitCommitSummary>>

    suspend fun fileContent(ref: GitRepoRef, path: String, at: String): GitResult<GitFileContent>

    // ------------------------------------------------------------ pipelines

    suspend fun pipelines(ref: GitRepoRef): GitResult<List<GitPipeline>>

    suspend fun runs(
        ref: GitRepoRef,
        branch: String? = null,
        perPage: Int = 20
    ): GitResult<List<GitPipelineRun>>

    suspend fun run(ref: GitRepoRef, runId: Long): GitResult<GitPipelineRun>

    /** Triggers one pipeline on [branch]; [pipelineId] is a workflow id, file name or path. */
    suspend fun dispatch(
        ref: GitRepoRef,
        pipelineId: String,
        branch: String,
        inputs: Map<String, String> = emptyMap()
    ): GitResult<Unit>

    suspend fun jobs(ref: GitRepoRef, runId: Long): GitResult<List<GitRunJob>>

    suspend fun runLogs(ref: GitRepoRef, runId: Long): GitResult<String>

    suspend fun runArtifacts(ref: GitRepoRef, runId: Long): GitResult<List<GitArtifact>>

    suspend fun downloadArtifact(artifact: GitArtifact): GitResult<ByteArray>

    /**
     * Streams an artifact zip into [target] without holding it in memory.
     *
     * The default writes the buffered result, which keeps test doubles working; real
     * drivers override it so a large artifact never becomes a `ByteArray` on a phone.
     */
    suspend fun downloadArtifactTo(artifact: GitArtifact, target: File): GitResult<Unit> =
        when (val result = downloadArtifact(artifact)) {
            is GitResult.Failure -> result
            is GitResult.Success -> runCatching {
                target.writeBytes(result.value)
                GitResult.Success(Unit) as GitResult<Unit>
            }.getOrElse {
                target.delete()
                GitResult.Failure(GitFailureKind.NETWORK, message = it.message)
            }
        }

    // ----------------------------------------------------------------- releases

    suspend fun releases(ref: GitRepoRef, perPage: Int = 20): GitResult<List<GitRelease>>

    suspend fun downloadReleaseAsset(asset: GitReleaseAsset): GitResult<ByteArray>

    /** Streams one release asset into [target]; see [downloadArtifactTo]. */
    suspend fun downloadReleaseAssetTo(asset: GitReleaseAsset, target: File): GitResult<Unit> =
        when (val result = downloadReleaseAsset(asset)) {
            is GitResult.Failure -> result
            is GitResult.Success -> runCatching {
                target.writeBytes(result.value)
                GitResult.Success(Unit) as GitResult<Unit>
            }.getOrElse {
                target.delete()
                GitResult.Failure(GitFailureKind.NETWORK, message = it.message)
            }
        }

    // ------------------------------------------------------------------- writes

    /**
     * Downloads the source archive of [branch] into [target] as a zip, reporting progress
     * in 0..1 when the host sends a content length.
     *
     * This is how the importer gets files from a host it cannot reach with a git client.
     * The caller owns [target] and deletes it afterwards.
     */
    suspend fun downloadArchive(
        ref: GitRepoRef,
        branch: String,
        target: File,
        onProgress: (Float) -> Unit = {}
    ): GitResult<Unit>

    /**
     * The writer used to publish a reviewed change set, or null when this provider
     * cannot write at all (a read-only or unverified server).
     */
    fun commitWriter(): GitCommitWriter?
}
