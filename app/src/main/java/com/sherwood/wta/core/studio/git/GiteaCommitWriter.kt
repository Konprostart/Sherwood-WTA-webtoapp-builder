package com.sherwood.wta.core.studio.git

/**
 * Publishes commits through Gitea / Forgejo's `change-files` endpoint.
 *
 * Gitea has no API that creates a commit object separately from its branch: every write
 * creates the commit *and* moves (or creates) the branch in one call. That is why
 * [commitsInOneStep] is true and why [createCommit] / [moveBranch] report
 * [GitFailureKind.UNSUPPORTED] instead of being emulated with two requests — a fake
 * two-step would either move the branch before the commit exists or need a temporary
 * branch that the caller never asked for.
 *
 * The single call is also the safety property: [publishNewBranch] sends `new_branch`, so
 * the host creates the branch and attaches the commit to it together. If the change set
 * is refused (the branch already exists, a file's blob sha no longer matches), nothing is
 * created. `force_push` is never sent, so an existing branch is never overwritten.
 *
 * Two limitations are real and are reported rather than hidden:
 *
 * - A new file's executable bit cannot be set: the endpoint has no mode field, so a
 *   script arrives as a regular file. An existing executable file keeps its mode,
 *   because the host preserves the old entry's mode on update.
 * - A symlink (mode 120000) is created as a regular file containing the link target.
 */
internal class GiteaCommitWriter(private val client: GiteaApiClient) : GitCommitWriter {

    override val commitsInOneStep: Boolean = true

    /**
     * One request carries the whole change set. The body is streamed, but each file's
     * content is already in memory, so the ceiling is what one publish may hold rather
     * than what one HTTP body may be.
     */
    override val publishLimits: GitPublishLimits = GitPublishLimits(
        maxFiles = 500,
        maxBytes = 32L * 1024 * 1024,
        maxFileBytes = 8L * 1024 * 1024
    )

    override suspend fun baseTree(ref: GitRepoRef, commitSha: String): GitResult<GitBaseTree> {
        val commit = when (val info = client.getCommitTree(ref.owner, ref.name, commitSha)) {
            is GitResult.Success -> info.value
            is GitResult.Failure -> return info
        }
        val tree = when (val snapshot = client.getTree(ref.owner, ref.name, commit.treeSha)) {
            is GitResult.Success -> snapshot.value
            is GitResult.Failure -> return snapshot
        }
        return GitResult.Success(
            GitBaseTree(commitSha = commit.commitSha, treeSha = commit.treeSha, snapshot = tree)
        )
    }

    override suspend fun createCommit(
        ref: GitRepoRef,
        parentSha: String,
        baseTreeSha: String,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<String> = oneStepOnly()

    override suspend fun moveBranch(
        ref: GitRepoRef,
        branch: String,
        commitSha: String,
        expectedBaseSha: String
    ): GitResult<Unit> = oneStepOnly()

    override suspend fun branchHead(ref: GitRepoRef, branch: String): GitResult<String> =
        when (val head = client.getBranch(ref.owner, ref.name, branch)) {
            is GitResult.Success -> GitResult.Success(head.value.sha)
            is GitResult.Failure -> head
        }

    override suspend fun publishToBranch(
        ref: GitRepoRef,
        branch: String,
        expectedBaseSha: String,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<GitPublishOutcome> =
        changeFiles(
            ref = ref,
            baseBranch = branch,
            newBranch = null,
            expectedBaseSha = expectedBaseSha,
            message = message,
            changes = changes
        )

    override suspend fun publishNewBranch(
        ref: GitRepoRef,
        baseBranch: String,
        branch: String,
        expectedBaseSha: String,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<GitPublishOutcome> =
        changeFiles(
            ref = ref,
            baseBranch = baseBranch,
            newBranch = branch,
            expectedBaseSha = expectedBaseSha,
            message = message,
            changes = changes
        )

    /**
     * One atomic change set.
     *
     * [expectedBaseSha] is not sent as a whole-tree precondition — Gitea has no such
     * field — but every update and delete carries the blob sha it was diffed against, so
     * a file that changed remotely is refused by the host. A base branch that moved
     * without touching any changed path is safe to append to: those commits are kept and
     * the new commit sits on top of them.
     */
    private suspend fun changeFiles(
        ref: GitRepoRef,
        baseBranch: String,
        newBranch: String?,
        expectedBaseSha: String,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<GitPublishOutcome> {
        if (changes.isEmpty()) {
            return GitResult.Failure(
                GitFailureKind.UNPROCESSABLE,
                message = "No changes to publish"
            )
        }
        val limitProblem = limitProblem(changes)
        if (limitProblem != null) {
            return GitResult.Failure(GitFailureKind.UNPROCESSABLE, message = limitProblem)
        }
        return when (
            val commit = client.changeFiles(
                owner = ref.owner,
                repo = ref.name,
                baseBranch = baseBranch,
                newBranch = newBranch,
                message = message,
                changes = changes
            )
        ) {
            is GitResult.Success -> GitResult.Success(GitPublishOutcome(commit.value))
            is GitResult.Failure -> commit
        }
    }

    /** Refuses an oversized set before a single byte is sent; never truncates it. */
    private fun limitProblem(changes: List<GitFileChange>): String? {
        val limits = publishLimits
        if (changes.size > limits.maxFiles) {
            return "${changes.size} files exceed the ${limits.maxFiles} this server accepts " +
                "in one commit"
        }
        var total = 0L
        for (change in changes) {
            val size = change.content?.size?.toLong() ?: 0L
            if (size > limits.maxFileBytes) {
                return "'${change.path}' is ${size / (1024 * 1024)} MB; at most " +
                    "${limits.maxFileBytes / (1024 * 1024)} MB fits in one commit"
            }
            total += size
        }
        if (total > limits.maxBytes) {
            return "the change set is ${total / (1024 * 1024)} MB; at most " +
                "${limits.maxBytes / (1024 * 1024)} MB fits in one commit"
        }
        return null
    }

    /**
     * Kept in one place so the message names the reason. A caller that reaches this has
     * asked for a staged publish the host cannot express; the use case never does,
     * because it checks [commitsInOneStep] first.
     */
    private fun <T> oneStepOnly(): GitResult<T> = GitResult.Failure(
        GitFailureKind.UNSUPPORTED,
        message = "Gitea/Forgejo write a commit and its branch in one call, so a commit " +
            "cannot be created without publishing it"
    )
}
