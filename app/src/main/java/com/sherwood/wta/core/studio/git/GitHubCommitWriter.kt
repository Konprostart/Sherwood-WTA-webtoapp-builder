package com.sherwood.wta.core.studio.git

import com.sherwood.wta.core.frontend.GitDataApiClient
import com.sherwood.wta.core.frontend.GitDataResult
import com.sherwood.wta.core.frontend.GitTreeEntry

/**
 * Publishes commits through the GitHub Git Data API.
 *
 * The object sequence is the one the Studio has always used and must stay byte-for-byte
 * equivalent: one blob per added or modified path, one tree layered on the recorded base
 * tree (carrying explicit deletions), one commit whose parent is the base commit, and
 * only then a non-forced ref update.
 *
 * Every step is server-side. There is no local repository, which is why this works on
 * API 24 devices where JGit cannot run.
 */
class GitHubCommitWriter(private val client: GitDataApiClient) : GitCommitWriter {

    override suspend fun baseTree(ref: GitRepoRef, commitSha: String): GitResult<GitBaseTree> {
        val commit = when (val result = client.getCommit(ref.owner, ref.name, commitSha)) {
            is GitDataResult.Success -> result.value
            is GitDataResult.Failure -> return result.toGitFailure()
        }
        val tree = when (val result = client.getTree(ref.owner, ref.name, commit.treeSha)) {
            is GitDataResult.Success -> result.value
            is GitDataResult.Failure -> return result.toGitFailure()
        }
        return GitResult.Success(
            GitBaseTree(commitSha = commit.sha, treeSha = commit.treeSha, snapshot = tree)
        )
    }

    override suspend fun createCommit(
        ref: GitRepoRef,
        parentSha: String,
        baseTreeSha: String,
        message: String,
        changes: List<GitFileChange>
    ): GitResult<String> {
        val entries = ArrayList<GitTreeEntry>(changes.size)
        for (change in changes) {
            val content = change.content
            if (content == null) {
                entries += GitTreeEntry(change.path, sha = null, mode = change.mode)
                continue
            }
            when (val blob = client.createBlob(ref.owner, ref.name, content)) {
                is GitDataResult.Success ->
                    entries += GitTreeEntry(change.path, blob.value, mode = change.mode)
                is GitDataResult.Failure -> return blob.toGitFailure()
            }
        }
        val treeSha = when (
            val tree = client.createTree(ref.owner, ref.name, baseTreeSha, entries)
        ) {
            is GitDataResult.Success -> tree.value
            is GitDataResult.Failure -> return tree.toGitFailure()
        }
        return client.createCommit(
            owner = ref.owner,
            repo = ref.name,
            message = message,
            treeSha = treeSha,
            parentSha = parentSha
        ).toGitResult()
    }

    override suspend fun moveBranch(
        ref: GitRepoRef,
        branch: String,
        commitSha: String,
        expectedBaseSha: String
    ): GitResult<Unit> = when (
        val result = client.updateRef(
            owner = ref.owner,
            repo = ref.name,
            branch = branch,
            newSha = commitSha,
            expectedBaseSha = expectedBaseSha,
            // Never forced: a branch that moved under us is a conflict, not an overwrite.
            force = false
        )
    ) {
        is GitDataResult.Success -> GitResult.Success(Unit)
        is GitDataResult.Failure -> result.toGitFailure()
    }

    override suspend fun branchHead(ref: GitRepoRef, branch: String): GitResult<String> =
        when (val result = client.getRef(ref.owner, ref.name, branch)) {
            is GitDataResult.Success -> GitResult.Success(result.value.sha)
            is GitDataResult.Failure -> result.toGitFailure()
        }
}
