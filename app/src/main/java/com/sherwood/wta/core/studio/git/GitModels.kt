package com.sherwood.wta.core.studio.git

/**
 * Provider-neutral domain model for the Studio's git layer.
 *
 * Everything the UI, the services and the agent tools need is described here without
 * naming any hosting platform. A hosting platform is expressed as a [GitProvider]
 * driver; this file only carries data.
 *
 * The field names are the ones the Studio already used before the split, so the
 * existing screens and tools keep reading them unchanged. That is deliberate: this
 * layer replaces a concrete GitHub client, not the vocabulary of the app.
 */

/** Why a git host call failed. */
enum class GitFailureKind {
    /** No token is saved, so nothing was sent. */
    NO_TOKEN,
    /** 401: the saved token is invalid or expired. */
    UNAUTHORIZED,
    /** 403 without a rate-limit signal: the token lacks access. */
    FORBIDDEN,
    /** 429, or 403 with a rate-limit signal. */
    RATE_LIMITED,
    /** The repository, branch or object is missing or invisible to the token. */
    NOT_FOUND,
    /** The remote ref no longer points at the expected commit. */
    CONFLICT,
    /** The host rejected the payload (422), e.g. a malformed tree entry. */
    UNPROCESSABLE,
    NETWORK,
    /** A 2xx response whose body did not carry the expected fields. */
    UNEXPECTED,
    HTTP
}

sealed class GitResult<out T> {
    data class Success<T>(val value: T) : GitResult<T>()

    data class Failure(
        val kind: GitFailureKind,
        val statusCode: Int? = null,
        val message: String? = null
    ) : GitResult<Nothing>()
}

/** The authenticated account behind the stored token. */
data class GitAccount(
    val login: String,
    val name: String?,
    val avatarUrl: String?
)

data class GitRepository(
    val id: Long,
    val name: String,
    val owner: String,
    val fullName: String,
    val privateRepo: Boolean,
    val fork: Boolean,
    val defaultBranch: String,
    val description: String?,
    val pushedAt: String?
) {
    val ownerRepo: String get() = "$owner/$name"
}

data class GitBranch(
    val name: String,
    val sha: String,
    val protectedBranch: Boolean
)

/**
 * A pull request or merge request.
 *
 * [number] is what the host displays and what every endpoint takes: GitHub's pull
 * request number, GitLab's `iid`. The host's internal object id is never used here,
 * because the two differ and only one of them is stable in URLs and in the UI.
 */
data class GitChangeRequest(
    val number: Int,
    val title: String,
    val state: String,
    val draft: Boolean,
    val merged: Boolean,
    val author: String,
    val headRef: String,
    val baseRef: String,
    /** Web URL of the change request; opened in the browser. */
    val htmlUrl: String,
    val updatedAt: String?,
    val body: String?
)

data class GitCommitSummary(
    val sha: String,
    val message: String,
    val authorName: String,
    val authorDate: String?,
    val htmlUrl: String?
)

data class GitMergeResult(
    val merged: Boolean,
    val sha: String?,
    val message: String?
)

/** Aggregate CI state of one commit. */
enum class ChecksState { UNKNOWN, PENDING, PASSING, FAILING }

data class GitCheck(
    val name: String,
    val status: String,
    val conclusion: String?,
    val detailsUrl: String?
)

data class GitCheckSummary(
    val state: ChecksState,
    val total: Int,
    val pending: Int,
    val passing: Int,
    val failing: Int,
    val checks: List<GitCheck>,
    val combinedState: String?
)

data class GitChangeRequestDetails(
    val pull: GitChangeRequest,
    val mergeable: Boolean?,
    val mergeableState: String?,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val commitCount: Int,
    val createdAt: String?,
    val mergedAt: String?,
    val closedAt: String?,
    val headSha: String,
    val baseSha: String?
)

data class GitChangeRequestFile(
    val filename: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val previousFilename: String?,
    /**
     * Unified diff for this file, when the host includes one. It is omitted for binary
     * files and for very large diffs, so no caller may assume it is present.
     */
    val patch: String? = null
)

data class GitChangeRequestReview(
    val id: Long,
    val state: String,
    val author: String,
    val body: String?,
    val submittedAt: String?
)

data class GitChangeRequestReviewComment(
    val id: Long,
    val path: String,
    val line: Int?,
    val body: String,
    val author: String,
    val htmlUrl: String?
)

/** One runnable CI workflow/pipeline definition of a repository. */
data class GitPipeline(
    val id: Long,
    val name: String,
    val path: String,
    val state: String?
)

/** One CI run of a [GitPipeline]. */
data class GitPipelineRun(
    val id: Long,
    val name: String,
    val workflowId: Long,
    val status: String,
    val conclusion: String?,
    val branch: String?,
    val headSha: String?,
    val event: String?,
    val runNumber: Int,
    val displayTitle: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val htmlUrl: String?
)

data class GitRunStep(
    val number: Int,
    val name: String,
    val status: String,
    val conclusion: String?
)

data class GitRunJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String?,
    val completedAt: String?,
    val steps: List<GitRunStep>
)

/** A build artifact a run uploaded; [archiveDownloadUrl] serves a zip. */
data class GitArtifact(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val expired: Boolean,
    val archiveDownloadUrl: String,
    val createdAt: String?,
    val workflowRunId: Long?
)

data class GitReleaseAsset(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val contentType: String?,
    /** REST API URL; download it with an `application/octet-stream` Accept header. */
    val apiDownloadUrl: String,
    val state: String?
) {
    /** Only `uploaded` assets are downloadable; anything else is left over from a failed upload. */
    val isDownloadable: Boolean
        get() = state == null || state == "uploaded"
}

data class GitRelease(
    val id: Long,
    val tagName: String,
    val name: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val publishedAt: String?,
    val htmlUrl: String?,
    val assets: List<GitReleaseAsset>
)

data class GitFileContent(
    val path: String,
    val name: String,
    val sha: String,
    val type: String,
    val size: Long,
    val content: ByteArray?
)
