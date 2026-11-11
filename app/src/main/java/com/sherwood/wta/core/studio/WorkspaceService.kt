package com.sherwood.wta.core.studio

import android.content.Context
import com.sherwood.wta.core.frontend.GitHubFetchResult
import com.sherwood.wta.core.frontend.GitHubRepoFetcher
import com.sherwood.wta.core.frontend.RepoBridgeChangeSet
import com.sherwood.wta.core.frontend.RepoBridgeCommitFailureKind
import com.sherwood.wta.core.frontend.RepoBridgeCommitUseCase
import com.sherwood.wta.core.frontend.RepoBridgeMetadata
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import com.sherwood.wta.core.frontend.RepoBridgePrepareResult
import com.sherwood.wta.core.frontend.RepoBridgeReview
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class WorkspaceStatus(
    val owner: String,
    val repo: String,
    val branch: String,
    val baseSha: String?,
    val changes: RepoBridgeChangeSet,
    val review: RepoBridgeReview?
) {
    val isClean: Boolean get() = changes.isEmpty
}

/** The remote head of the branch a working copy tracks, without touching files. */
data class RemoteHead(
    val branch: String,
    val sha: String,
    val baseSha: String?,
    val remoteMoved: Boolean
)

data class CheckoutResult(
    val projectDir: String,
    val metadata: RepoBridgeMetadata,
    val previousBranch: String,
    val newBranch: String,
    val newBaseSha: String?
)

/** One changed path with its local and remote text and a unified diff. */
data class FileDiff(
    val repoPath: String,
    val localPath: String,
    val binary: Boolean,
    val unified: String,
    val localSize: Long,
    val remoteSize: Long
)

/**
 * Branch-level operations for an imported working copy.
 *
 * Why there is no local `.git`:
 *
 * JGit cannot run below API 26. It relies on `java.nio.file` (API 26) and on
 * symbolic-link semantics that Android's app sandbox does not provide; the
 * usually suggested workaround is raising `minSdk`, which this project refuses
 * because it would drop Android 6/7 users. The alternative already shipped here
 * is the GitHub Git Data API: objects are built server-side and the working copy
 * is a plain directory diffed against a recorded base tree. Branch, checkout,
 * merge, history and diff therefore go through the REST API, not through JGit,
 * and `minSdk` stays 24.
 *
 * Checkout/pull are destructive to the working copy by nature, so both require a
 * clean working copy first (no local changes that would be silently overwritten)
 * and both keep a backup until the copy completes.
 */
class WorkspaceService(private val context: Context) {

    private val api = GitHubApiClient(context)
    private val fetcher = GitHubRepoFetcher(context)
    private val metadataStore = RepoBridgeMetadataStore()

    fun metadata(projectDir: File): RepoBridgeMetadata? = metadataStore.read(projectDir)

    /**
     * Every imported working copy of [owner]/[repo]. The importer writes each copy to
     * `filesDir/github_imports/<repo>_<timestamp>`, so scanning that one directory is
     * enough and stays independent of the app database.
     */
    fun importedProjects(owner: String, repo: String): List<File> {
        val root = File(context.filesDir, IMPORT_ROOT)
        val children = root.listFiles() ?: return emptyList()
        return children.filter { it.isDirectory }
            .mapNotNull { dir ->
                val metadata = metadataStore.read(dir) ?: return@mapNotNull null
                if (metadata.owner.equals(owner, ignoreCase = true) &&
                    metadata.repo.equals(repo, ignoreCase = true)
                ) {
                    dir
                } else {
                    null
                }
            }
            .sortedByDescending { it.lastModified() }
    }

    fun allImportedProjects(): List<Pair<File, RepoBridgeMetadata>> {
        val root = File(context.filesDir, IMPORT_ROOT)
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> metadataStore.read(dir)?.let { dir to it } }
            .sortedByDescending { it.first.lastModified() }
    }

    suspend fun status(projectDir: File): Result<WorkspaceStatus> = withContext(Dispatchers.IO) {
        val metadata = metadataStore.read(projectDir)
            ?: return@withContext Result.failure(IllegalStateException("Project is not linked to GitHub"))
        try {
            when (val prepared = RepoBridgeCommitUseCase(context, projectDir).prepare()) {
                is RepoBridgePrepareResult.Ready -> Result.success(
                    WorkspaceStatus(
                        owner = prepared.owner,
                        repo = prepared.repo,
                        branch = prepared.branch,
                        baseSha = prepared.baseSha,
                        changes = prepared.changes,
                        review = prepared.review
                    )
                )
                is RepoBridgePrepareResult.Failure -> Result.failure(
                    WorkspaceException.Commit(prepared.reason, prepared.detail)
                )
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Status failed: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    suspend fun branches(owner: String, repo: String): List<GitHubBranch> =
        when (val result = api.listBranches(owner, repo)) {
            is GitHubResult.Success -> result.value
            is GitHubResult.Failure -> throw WorkspaceException.GitHub(result.kind, result.message)
        }

    suspend fun history(
        owner: String,
        repo: String,
        branch: String?,
        perPage: Int = 50
    ): List<GitHubCommitSummary> = when (val result = api.listCommits(owner, repo, branch, perPage)) {
        is GitHubResult.Success -> result.value
        is GitHubResult.Failure -> throw WorkspaceException.GitHub(result.kind, result.message)
    }

    suspend fun createBranch(
        owner: String,
        repo: String,
        name: String,
        fromBranch: String
    ): Result<GitHubBranch> = withContext(Dispatchers.IO) {
        if (!isValidBranchName(name)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid branch name"))
        }
        when (val source = api.getBranch(owner, repo, fromBranch)) {
            is GitHubResult.Failure -> Result.failure(
                WorkspaceException.GitHub(source.kind, source.message)
            )
            is GitHubResult.Success -> {
                when (val created = api.createBranch(owner, repo, name, source.value.sha)) {
                    is GitHubResult.Success -> Result.success(created.value)
                    is GitHubResult.Failure -> Result.failure(
                        WorkspaceException.GitHub(created.kind, created.message)
                    )
                }
            }
        }
    }

    suspend fun merge(
        owner: String,
        repo: String,
        base: String,
        head: String,
        message: String? = null
    ): Result<GitHubMergeResult> = withContext(Dispatchers.IO) {
        when (val result = api.mergeBranches(owner, repo, base, head, message)) {
            is GitHubResult.Success -> Result.success(result.value)
            is GitHubResult.Failure -> Result.failure(
                WorkspaceException.GitHub(result.kind, result.message)
            )
        }
    }

    /**
     * Fetch without modifying the working copy: resolves the remote head and reports
     * whether it moved past the recorded base commit. A pull/checkout is a separate,
     * explicit action.
     */
    suspend fun fetch(projectDir: File): Result<RemoteHead> = withContext(Dispatchers.IO) {
        val metadata = metadataStore.read(projectDir)
            ?: return@withContext Result.failure(
                IllegalStateException("Project is not linked to GitHub")
            )
        when (val branch = api.getBranch(metadata.owner, metadata.repo, metadata.ref)) {
            is GitHubResult.Success -> Result.success(
                RemoteHead(
                    branch = metadata.ref,
                    sha = branch.value.sha,
                    baseSha = metadata.baseSha,
                    remoteMoved = metadata.baseSha != null && metadata.baseSha != branch.value.sha
                )
            )
            is GitHubResult.Failure -> Result.failure(
                WorkspaceException.GitHub(branch.kind, branch.message)
            )
        }
    }

    /** Imports a repository as a new working copy (the Studio's "clone"). */
    suspend fun clone(
        owner: String,
        repo: String,
        branch: String? = null,
        subPath: String? = null
    ): Result<com.sherwood.wta.core.frontend.GitHubFetchResult> = withContext(Dispatchers.IO) {
        fetcher.fetch("$owner/$repo", explicitBranch = branch, explicitSubPath = subPath)
    }

    /** Pull = move the working copy to the remote head of the branch it already tracks. */
    suspend fun pull(projectDir: File): Result<CheckoutResult> {
        val metadata = metadataStore.read(projectDir)
            ?: return Result.failure(IllegalStateException("Project is not linked to GitHub"))
        return checkout(projectDir, metadata.ref)
    }

    /**
     * Switches the working copy to [branch].
     *
     * 1. Refuses when local changes exist (they would be lost).
     * 2. Downloads the branch through the same importer the app already uses.
     * 3. Copies it over the project directory, keeping a backup until done.
     */
    suspend fun checkout(projectDir: File, branch: String): Result<CheckoutResult> =
        withContext(Dispatchers.IO) {
            val metadata = metadataStore.read(projectDir)
                ?: return@withContext Result.failure(
                    IllegalStateException("Project is not linked to GitHub")
                )
            if (!projectDir.isDirectory) {
                return@withContext Result.failure(IllegalStateException("Project directory is missing"))
            }
            if (!isValidBranchName(branch)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid branch name"))
            }
            // A clean working copy is a hard requirement: checkout replaces files.
            val statusResult = status(projectDir)
            val status = statusResult.getOrElse { return@withContext Result.failure(it) }
            if (!status.isClean) {
                return@withContext Result.failure(
                    WorkspaceException.CleanRequired(status.changes.total)
                )
            }

            val fetched = fetcher.fetch(
                input = "${metadata.owner}/${metadata.repo}",
                explicitBranch = branch,
                explicitSubPath = metadata.subPath
            ).getOrElse { return@withContext Result.failure(it) }

            val sourceDir = File(fetched.localPath)
            val sourceMeta = metadataStore.read(sourceDir)
            if (sourceMeta == null) {
                deleteQuietly(sourceDir)
                return@withContext Result.failure(IOException("Imported branch has no repository metadata"))
            }
            try {
                replaceWorkingCopy(projectDir, sourceDir)
            } catch (e: Exception) {
                deleteQuietly(sourceDir)
                AppLogger.w(TAG, "Checkout replace failed: ${e.javaClass.simpleName}")
                return@withContext Result.failure(e)
            }
            deleteQuietly(sourceDir)
            val updated = metadataStore.read(projectDir)
                ?: return@withContext Result.failure(IOException("Repository metadata was not written"))
            Result.success(
                CheckoutResult(
                    projectDir = projectDir.absolutePath,
                    metadata = updated,
                    previousBranch = metadata.ref,
                    newBranch = updated.ref,
                    newBaseSha = updated.baseSha
                )
            )
        }

    /**
     * Reads the remote version of one changed path (from the recorded base commit) and
     * returns a unified diff against the local file. Binary files report sizes only.
     */
    suspend fun diff(projectDir: File, repoPath: String): Result<FileDiff> =
        withContext(Dispatchers.IO) {
            val metadata = metadataStore.read(projectDir)
                ?: return@withContext Result.failure(IllegalStateException("Project is not linked to GitHub"))
            val baseSha = metadata.baseSha
                ?: return@withContext Result.failure(IllegalStateException("No base commit recorded"))
            val localRelative = normalizeRepoPath(repoPath) ?: return@withContext Result.failure(
                WorkspaceException.InvalidPath
            )
            val localFile = File(projectDir, localRelative)
            if (!isInside(projectDir, localFile)) {
                return@withContext Result.failure(WorkspaceException.InvalidPath)
            }
            val remotePath = listOfNotNull(metadata.subPath, localRelative).joinToString("/")
            val remote = api.getFileContent(metadata.owner, metadata.repo, remotePath, baseSha)
            val remoteBytes = when (remote) {
                is GitHubResult.Success -> remote.value.content
                is GitHubResult.Failure -> {
                    if (remote.kind != GitHubFailureKind.NOT_FOUND) {
                        return@withContext Result.failure(
                            WorkspaceException.GitHub(remote.kind, remote.message)
                        )
                    }
                    null
                }
            }
            val localBytes = if (localFile.isFile) localFile.readBytes() else null
            if (localBytes == null && remoteBytes == null) {
                return@withContext Result.failure(IOException("File does not exist locally or remotely"))
            }
            val binary = isBinary(localBytes) || isBinary(remoteBytes)
            val unified = if (binary) {
                ""
            } else {
                UnifiedDiff.diff(
                    oldText = remoteBytes?.toString(Charsets.UTF_8).orEmpty(),
                    newText = localBytes?.toString(Charsets.UTF_8).orEmpty(),
                    oldPath = "a/$repoPath",
                    newPath = "b/$repoPath"
                )
            }
            Result.success(
                FileDiff(
                    repoPath = repoPath,
                    localPath = localFile.absolutePath,
                    binary = binary,
                    unified = unified,
                    localSize = localBytes?.size?.toLong() ?: 0L,
                    remoteSize = remoteBytes?.size?.toLong() ?: 0L
                )
            )
        }

    private fun replaceWorkingCopy(target: File, source: File) {
        require(target.isDirectory) { "Target is not a directory" }
        require(source.isDirectory) { "Source is not a directory" }
        require(File(target, RepoBridgeMetadataStore.FILE_NAME).isFile) {
            "Target is not an imported project"
        }
        val backup = File(target.parentFile, "${target.name}.backup-${System.currentTimeMillis()}")
        copyTree(target, backup)
        try {
            clearChildren(target)
            copyTree(source, target)
        } catch (e: Exception) {
            clearChildren(target)
            if (backup.isDirectory) {
                copyTree(backup, target)
                deleteQuietly(backup)
            } else {
                AppLogger.e(TAG, "Checkout backup could not be restored", e)
            }
            throw e
        }
        deleteQuietly(backup)
    }

    private fun copyTree(from: File, to: File) {
        if (!to.exists()) to.mkdirs()
        from.listFiles()?.forEach { child ->
            if (child.isSymbolicLink()) return@forEach
            val destination = File(to, child.name)
            if (child.isDirectory) {
                copyTree(child, destination)
            } else {
                child.copyTo(destination, overwrite = true)
            }
        }
    }

    private fun clearChildren(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory && !child.isSymbolicLink()) child.deleteRecursively() else child.delete()
        }
    }

    /**
     * API 24 has no `java.nio.file.Files`; `Os.lstat` reports the link bit without
     * following it, which is exactly what the copy safety check needs.
     */
    private fun File.isSymbolicLink(): Boolean = runCatching {
        val mode = android.system.Os.lstat(absolutePath).st_mode
        android.system.OsConstants.S_ISLNK(mode)
    }.getOrDefault(false)

    private fun deleteQuietly(file: File) {
        runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }
    }

    private fun isInside(parent: File, child: File): Boolean = runCatching {
        val parentPath = parent.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        childPath.startsWith(parentPath)
    }.getOrDefault(false)

    private fun isBinary(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        val sample = bytes.take(4096)
        if (sample.any { it == 0.toByte() }) return true
        val decoded = runCatching { sample.toByteArray().toString(Charsets.UTF_8) }.getOrNull() ?: return true
        return decoded.count { it == '\uFFFD' } > 0
    }

    companion object {
        private const val TAG = "WorkspaceService"

        private const val IMPORT_ROOT = "github_imports"

        private val BRANCH_RE = Regex("^[A-Za-z0-9._/-]{1,200}$")

        fun isValidBranchName(name: String): Boolean {
            val value = name.trim()
            if (!BRANCH_RE.matches(value)) return false
            if (value.startsWith("/") || value.endsWith("/")) return false
            if (value.startsWith(".") || value.endsWith(".")) return false
            if (value.contains("..") || value.contains("//")) return false
            if (value.endsWith(".lock")) return false
            return true
        }

        /** Rejects absolute paths, `..` traversal and backslashes. */
        fun normalizeRepoPath(raw: String): String? {
            val value = raw.trim().replace('\\', '/')
            if (value.isEmpty() || value.startsWith("/")) return null
            val parts = value.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return parts.joinToString("/")
        }
    }
}

/** Typed failures the Studio UI can turn into a concrete message. */
sealed class WorkspaceException(message: String?) : Exception(message) {
    object InvalidPath : WorkspaceException("Invalid path")

    data class CleanRequired(val changed: Int) :
        WorkspaceException("Working copy has $changed uncommitted changes")

    data class GitHub(val kind: GitHubFailureKind, val detail: String?) :
        WorkspaceException(detail ?: kind.name)

    data class Commit(val kind: RepoBridgeCommitFailureKind, val detail: String?) :
        WorkspaceException(detail ?: kind.name)
}
