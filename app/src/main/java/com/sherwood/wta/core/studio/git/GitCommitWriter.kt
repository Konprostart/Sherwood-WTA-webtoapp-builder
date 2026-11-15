package com.sherwood.wta.core.studio.git

import com.sherwood.wta.core.frontend.GitTreeSnapshot

/**
 * One path of a publish.
 *
 * [content] null means "delete this path". [mode] is the git file mode and is kept
 * from the base tree when the path already existed, so an executable script stays
 * executable and a symlink stays a symlink. Hosts that cannot set a mode on a newly
 * created path fall back to a regular file.
 *
 * [baseSha] is the blob sha the change was computed against, when the path already
 * existed. A host whose API validates a per-file precondition uses it to refuse a write
 * that would overwrite content someone else changed after the diff was taken. A host
 * that has no such precondition ignores it, so it is always safe to fill in.
 *
 * [upsert] replaces an existing path without a precondition. It exists for exactly one
 * caller: seeding a repository the caller has just created, where the auto-generated
 * README may share a name with a file from the copied project. Never set it for a
 * change to a repository someone else may have edited.
 */
data class GitFileChange(
    val path: String,
    val mode: String = "100644",
    val content: ByteArray?,
    val baseSha: String? = null,
    val upsert: Boolean = false
)

/** The commit a working copy was derived from, together with the tree it points at. */
data class GitBaseTree(
    val commitSha: String,
    val treeSha: String,
    val snapshot: GitTreeSnapshot
)

/** A publish that reached the host: the tip commit and how many commits it created. */
data class GitPublishOutcome(
    val commitSha: String,
    val commitsCreated: Int = 1
)

/**
 * Hard ceiling for one publish.
 *
 * Exceeding any value refuses the publish instead of sending a partial change set: a
 * host that receives only part of the edits would leave the branch in a state the user
 * never asked for. The limits are per writer because they bound the *request body* a
 * host accepts and the memory the upload path needs on the phone, and those differ.
 */
data class GitPublishLimits(
    val maxFiles: Int,
    val maxBytes: Long,
    val maxFileBytes: Long
)

/**
 * Publishes a reviewed change set without a local git repository.
 *
 * Two shapes exist, and a driver states which one it serves:
 *
 * - **Staged** ([commitsInOneStep] false): [createCommit] builds the commit object
 *   server-side, then [moveBranch] advances the ref. That order lets the publisher record
 *   a pending base between the two calls, so a process killed after the ref moved can
 *   still recover on the next run. GitHub works this way.
 * - **Single-step** ([commitsInOneStep] true): the host's API writes the commit and moves
 *   (or creates) the branch in one call, so [createCommit] and [moveBranch] cannot be
 *   used separately and report [GitFailureKind.UNSUPPORTED]. Callers use
 *   [publishToBranch] / [publishNewBranch], which are atomic on such a host: either the
 *   whole change set lands on the branch or nothing does. Gitea and Forgejo work this way.
 *
 * Implementations must never force a ref. [moveBranch] takes the commit the caller
 * believes the branch points at and has to refuse when the branch has moved, and a
 * single-step driver must refuse when a file it would overwrite has changed remotely.
 * A concurrent push is then a conflict rather than a silent overwrite.
 */
interface GitCommitWriter {

    /** True when the host writes a commit together with its branch in one call. */
    val commitsInOneStep: Boolean get() = false

    /** Ceiling for one publish; a bigger change set is refused, never truncated. */
    val publishLimits: GitPublishLimits get() = DEFAULT_PUBLISH_LIMITS

    /** Reads [commitSha]'s tree so a publish can be diffed and layered on top of it. */
    suspend fun baseTree(ref: GitRepoRef, commitSha: String): GitResult<GitBaseTree>

    /**
     * Creates a commit on top of [parentSha] carrying [changes], without moving any
     * branch. Returns the new commit sha.
     *
     * Only a staged driver can serve this; a single-step driver reports
     * [GitFailureKind.UNSUPPORTED].
     */
    suspend fun createCommit(
        ref: GitRepoRef,
        parentSha: String,
        baseTreeSha: String,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<String>

    /**
     * Advances [branch] to [commitSha], refusing unless the branch still points at
     * [expectedBaseSha].
     *
     * Only a staged driver can serve this; a single-step driver reports
     * [GitFailureKind.UNSUPPORTED].
     */
    suspend fun moveBranch(
        ref: GitRepoRef,
        branch: String,
        commitSha: String,
        expectedBaseSha: String
    ): GitResult<Unit>

    /** Current head of [branch], used to recover an interrupted publish. */
    suspend fun branchHead(ref: GitRepoRef, branch: String): GitResult<String>

    /**
     * Publishes [changes] as one commit on the existing [branch], appended to
     * [expectedBaseSha].
     *
     * The default implementation is the staged sequence; a single-step driver overrides
     * it. Either way a change that no longer applies to the branch's current content is
     * refused rather than overwriting it.
     */
    suspend fun publishToBranch(
        ref: GitRepoRef,
        branch: String,
        expectedBaseSha: String,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<GitPublishOutcome> {
        val base = when (val tree = baseTree(ref, expectedBaseSha)) {
            is GitResult.Success -> tree.value
            is GitResult.Failure -> return tree
        }
        val commit = when (
            val created = createCommit(ref, expectedBaseSha, base.treeSha, message, changes)
        ) {
            is GitResult.Success -> created.value
            is GitResult.Failure -> return created
        }
        return when (val moved = moveBranch(ref, branch, commit, expectedBaseSha)) {
            is GitResult.Success -> GitResult.Success(GitPublishOutcome(commit))
            is GitResult.Failure -> moved
        }
    }

    /**
     * Publishes [changes] as one commit on a **new** branch [branch] started at
     * [expectedBaseSha], leaving [baseBranch] untouched.
     *
     * The caller has already checked that [branch] does not exist. The branch must never
     * be forced into existence, and a host that creates the branch and the commit in one
     * call must leave neither behind when the commit is refused.
     */
    suspend fun publishNewBranch(
        ref: GitRepoRef,
        baseBranch: String,
        branch: String,
        expectedBaseSha: String,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<GitPublishOutcome>

    companion object {
        /**
         * Shared default: 1000 files, 64 MB in total and 10 MB per file. It matches the
         * publisher's own gates, so a change set that passed those is never refused here
         * by surprise.
         */
        val DEFAULT_PUBLISH_LIMITS = GitPublishLimits(
            maxFiles = 1_000,
            maxBytes = 64L * 1024 * 1024,
            maxFileBytes = 10L * 1024 * 1024
        )
    }
}
