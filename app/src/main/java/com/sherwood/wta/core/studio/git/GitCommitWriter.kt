package com.sherwood.wta.core.studio.git

import com.sherwood.wta.core.frontend.GitTreeSnapshot

/**
 * One path of a publish.
 *
 * [content] null means "delete this path". [mode] is the git file mode and is kept
 * from the base tree when the path already existed, so an executable script stays
 * executable and a symlink stays a symlink.
 */
data class GitFileChange(
    val path: String,
    val mode: String = "100644",
    val content: ByteArray?
)

/** The commit a working copy was derived from, together with the tree it points at. */
data class GitBaseTree(
    val commitSha: String,
    val treeSha: String,
    val snapshot: GitTreeSnapshot
)

/**
 * Publishes a reviewed change set without a local git repository.
 *
 * The split into [createCommit] and [moveBranch] is deliberate: it reproduces the order
 * the publisher has always used — build the commit object first, record it as the
 * "pending base" so an interrupted run can recover, then advance the ref. That order is
 * the contract of this interface, and it is what every host supported today can serve:
 * the commit is built server-side and no branch moves until [moveBranch] is called.
 *
 * A host whose API can only create a commit and move a branch in one call cannot be
 * expressed by this contract. Adding one — a single-step write, with no pending-base
 * recovery — belongs to adding that driver, so no capability flag here pretends a caller
 * could request it.
 *
 * Implementations must never force a ref. [moveBranch] takes the commit the caller
 * believes the branch points at and has to refuse when the branch has moved, so a
 * concurrent push is a conflict rather than a silent overwrite.
 */
interface GitCommitWriter {

    /** Reads [commitSha]'s tree so a publish can be diffed and layered on top of it. */
    suspend fun baseTree(ref: GitRepoRef, commitSha: String): GitResult<GitBaseTree>

    /**
     * Creates a commit on top of [parentSha] carrying [changes], without moving any
     * branch. Returns the new commit sha.
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
     */
    suspend fun moveBranch(
        ref: GitRepoRef,
        branch: String,
        commitSha: String,
        expectedBaseSha: String
    ): GitResult<Unit>

    /** Current head of [branch], used to recover an interrupted publish. */
    suspend fun branchHead(ref: GitRepoRef, branch: String): GitResult<String>
}
