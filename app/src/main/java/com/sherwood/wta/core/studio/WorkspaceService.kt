package com.sherwood.wta.core.studio

import android.content.Context
import com.sherwood.wta.core.frontend.RepoBridgeChangeSet
import com.sherwood.wta.core.frontend.RepoBridgeCommitFailureKind
import com.sherwood.wta.core.frontend.RepoBridgeCommitUseCase
import com.sherwood.wta.core.frontend.RepoBridgeMetadata
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import com.sherwood.wta.core.frontend.RepoBridgePrepareResult
import com.sherwood.wta.core.frontend.RepoBridgeReview
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.git.GitBranch
import com.sherwood.wta.core.studio.git.GitCommitSummary
import com.sherwood.wta.core.studio.git.GitFailureKind
import com.sherwood.wta.core.studio.git.GitMergeResult
import com.sherwood.wta.core.studio.git.GitProvider
import com.sherwood.wta.core.studio.git.GitProviderId
import com.sherwood.wta.core.studio.git.GitProviderLookup
import com.sherwood.wta.core.studio.git.GitProviderRegistry
import com.sherwood.wta.core.studio.git.GitProviderUnavailableReason
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.git.RepoImportStorage
import com.sherwood.wta.core.studio.git.RepoImporter
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
    val remoteSize: Long,
    /**
     * One side is too large to diff (or the host withheld the content).
     *
     * Reported explicitly instead of as an empty diff, because an empty diff reads as
     * "the files are identical" and a whole-file-added diff reads as a real change.
     */
    val tooLarge: Boolean = false
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
 * is the host's server-side git API: objects are built server-side and the working copy
 * is a plain directory diffed against a recorded base tree. Branch, checkout,
 * merge, history and diff therefore go through the provider, not through JGit,
 * and `minSdk` stays 24.
 *
 * Checkout/pull are destructive to the working copy by nature, so both require a
 * clean working copy first (no local changes that would be silently overwritten)
 * and both keep a backup until the copy completes.
 *
 * Import goes through [RepoImporter], which asks the working copy's provider for the
 * source archive instead of knowing one host. The on-disk provenance
 * (`repo_bridge.json`) records that provider and its host, so a copy imported from one
 * server keeps using it after the active provider setting changes.
 */
class WorkspaceService(private val context: Context) {

    private val importer = RepoImporter(context)
    private val metadataStore = RepoBridgeMetadataStore()

    fun metadata(projectDir: File): RepoBridgeMetadata? = metadataStore.read(projectDir)

    /**
     * Every imported working copy of [owner]/[repo]. Imports go to
     * `filesDir/git_imports/<repo>_<timestamp>`; copies written by releases before
     * multi-provider support stay in `github_imports` and are still found there.
     */
    fun importedProjects(owner: String, repo: String): List<File> =
        allImportedProjects()
            .filter { (_, metadata) ->
                metadata.owner.equals(owner, ignoreCase = true) &&
                    metadata.repo.equals(repo, ignoreCase = true)
            }
            .map { it.first }

    fun allImportedProjects(): List<Pair<File, RepoBridgeMetadata>> =
        RepoImportStorage.roots(context.filesDir)
            .flatMap { root -> root.listFiles().orEmpty().filter { it.isDirectory } }
            .mapNotNull { dir -> metadataStore.read(dir)?.let { dir to it } }
            .sortedByDescending { it.first.lastModified() }

    suspend fun status(projectDir: File): Result<WorkspaceStatus> = withContext(Dispatchers.IO) {
        val metadata = metadataStore.read(projectDir)
            ?: return@withContext Result.failure(
                IllegalStateException("Project is not linked to a git host")
            )
        // Diffing against the base commit needs the host's git-object API. A provider
        // without a writer cannot serve that, and a GitHub writer pointed at a Gitea
        // server would ask the wrong host — refuse instead of guessing.
        val located = locateMetadata(metadata)
        if (located is Located.Failure) return@withContext Result.failure(located.error)
        val writer = (located as Located.Ok).provider.commitWriter()
            ?: return@withContext Result.failure(
                WorkspaceException.UnsupportedProvider(metadata.providerId)
            )
        try {
            when (val prepared = RepoBridgeCommitUseCase(context, projectDir, writer).prepare()) {
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

    suspend fun branches(owner: String, repo: String): List<GitBranch> =
        when (val located = locate(owner, repo)) {
            is Located.Failure -> throw located.error
            is Located.Ok -> when (val result = located.provider.branches(located.ref)) {
                is GitResult.Success -> result.value
                is GitResult.Failure -> throw WorkspaceException.Provider(result.kind, result.message)
            }
        }

    suspend fun history(
        owner: String,
        repo: String,
        branch: String?,
        perPage: Int = 50
    ): List<GitCommitSummary> = when (val located = locate(owner, repo)) {
        is Located.Failure -> throw located.error
        is Located.Ok -> when (val result = located.provider.commits(located.ref, branch, perPage)) {
            is GitResult.Success -> result.value
            is GitResult.Failure -> throw WorkspaceException.Provider(result.kind, result.message)
        }
    }

    suspend fun createBranch(
        owner: String,
        repo: String,
        name: String,
        fromBranch: String
    ): Result<GitBranch> = withContext(Dispatchers.IO) {
        if (!isValidBranchName(name)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid branch name"))
        }
        val located = locate(owner, repo)
        if (located is Located.Failure) return@withContext Result.failure(located.error)
        val ok = located as Located.Ok
        when (val source = ok.provider.branch(ok.ref, fromBranch)) {
            is GitResult.Failure -> Result.failure(
                WorkspaceException.Provider(source.kind, source.message)
            )
            is GitResult.Success -> when (
                val created = ok.provider.createBranch(ok.ref, name, source.value.sha)
            ) {
                is GitResult.Success -> Result.success(created.value)
                is GitResult.Failure -> Result.failure(
                    WorkspaceException.Provider(created.kind, created.message)
                )
            }
        }
    }

    suspend fun merge(
        owner: String,
        repo: String,
        base: String,
        head: String,
        message: String? = null
    ): Result<GitMergeResult> = withContext(Dispatchers.IO) {
        val located = locate(owner, repo)
        if (located is Located.Failure) return@withContext Result.failure(located.error)
        val ok = located as Located.Ok
        when (val result = ok.provider.mergeBranches(ok.ref, base, head, message)) {
            is GitResult.Success -> Result.success(result.value)
            is GitResult.Failure -> Result.failure(
                WorkspaceException.Provider(result.kind, result.message)
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
                IllegalStateException("Project is not linked to a git host")
            )
        val located = locateMetadata(metadata)
        if (located is Located.Failure) return@withContext Result.failure(located.error)
        val ok = located as Located.Ok
        when (val branch = ok.provider.branch(ok.ref, metadata.ref)) {
            is GitResult.Success -> Result.success(
                RemoteHead(
                    branch = metadata.ref,
                    sha = branch.value.sha,
                    baseSha = metadata.baseSha,
                    remoteMoved = metadata.baseSha != null && metadata.baseSha != branch.value.sha
                )
            )
            is GitResult.Failure -> Result.failure(
                WorkspaceException.Provider(branch.kind, branch.message)
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
        importer.import(owner, repo, branch, subPath)
    }

    /** Pull = move the working copy to the remote head of the branch it already tracks. */
    suspend fun pull(projectDir: File): Result<CheckoutResult> {
        val metadata = metadataStore.read(projectDir)
            ?: return Result.failure(IllegalStateException("Project is not linked to a git host"))
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
                    IllegalStateException("Project is not linked to a git host")
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

            val located = locateMetadata(metadata)
            if (located is Located.Failure) return@withContext Result.failure(located.error)
            val fetched = importer.import(
                provider = (located as Located.Ok).provider,
                owner = metadata.owner,
                repo = metadata.repo,
                branch = branch,
                subPath = metadata.subPath
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
                ?: return@withContext Result.failure(IllegalStateException("Project is not linked to a git host"))
            val baseSha = metadata.baseSha
                ?: return@withContext Result.failure(IllegalStateException("No base commit recorded"))
            val localRelative = normalizeRepoPath(repoPath) ?: return@withContext Result.failure(
                WorkspaceException.InvalidPath
            )
            val localFile = File(projectDir, localRelative)
            if (!isInside(projectDir, localFile)) {
                return@withContext Result.failure(WorkspaceException.InvalidPath)
            }
            val located = locateMetadata(metadata)
            if (located is Located.Failure) return@withContext Result.failure(located.error)
            val ok = located as Located.Ok
            val remotePath = listOfNotNull(metadata.subPath, localRelative).joinToString("/")
            val remote = ok.provider.fileContent(ok.ref, remotePath, baseSha)
            var remoteOmitted = false
            var remoteSize = 0L
            val remoteBytes = when (remote) {
                is GitResult.Success -> {
                    remoteSize = remote.value.size
                    // A host omits inline content for large files (GitHub above ~1 MB).
                    // Treating that as "absent" turned the whole local file into an
                    // "added" diff, which is a lie about the change.
                    if (remote.value.content == null) {
                        remoteOmitted = true
                        null
                    } else {
                        remote.value.content
                    }
                }
                is GitResult.Failure -> {
                    if (remote.kind != GitFailureKind.NOT_FOUND) {
                        return@withContext Result.failure(
                            WorkspaceException.Provider(remote.kind, remote.message)
                        )
                    }
                    null
                }
            }
            val localTooLarge = localFile.isFile && localFile.length() > MAX_DIFF_BYTES
            val localBytes = when {
                !localFile.isFile -> null
                // Never read an arbitrary-size asset into the heap just to show a diff.
                localTooLarge -> null
                else -> localFile.readBytes()
            }
            if (localBytes == null && remoteBytes == null) {
                if (remoteOmitted || localTooLarge) {
                    return@withContext Result.success(
                        FileDiff(
                            repoPath = repoPath,
                            localPath = localFile.absolutePath,
                            binary = false,
                            unified = "",
                            localSize = if (localFile.isFile) localFile.length() else 0L,
                            remoteSize = remoteSize,
                            tooLarge = true
                        )
                    )
                }
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

    /** The active provider plus a validated repository identity, or the reason there is none. */
    private sealed class Located {
        data class Ok(val provider: GitProvider, val ref: GitRepoRef) : Located()
        data class Failure(val error: WorkspaceException) : Located()
    }

    private fun locate(owner: String, repo: String): Located {
        val candidate = GitRepoRef.of(owner, repo)
            ?: return Located.Failure(WorkspaceException.InvalidRepository("$owner/$repo"))
        return when (val lookup = GitProviderRegistry.active(context)) {
            is GitProviderLookup.Available -> Located.Ok(lookup.provider, candidate)
            is GitProviderLookup.Unavailable -> Located.Failure(
                WorkspaceException.UnavailableProvider(lookup.id, lookup.reason)
            )
        }
    }

    /**
     * The provider that owns a working copy, from its recorded provenance.
     *
     * This is not the active provider. A copy imported from `git.example.com` must keep
     * talking to that server after the user switches the Studio to GitHub; otherwise a
     * refresh would silently read a different repository with the same name.
     */
    private fun locateMetadata(metadata: RepoBridgeMetadata): Located {
        val candidate = GitRepoRef.of(metadata.owner, metadata.repo)
            ?: return Located.Failure(
                WorkspaceException.InvalidRepository("${metadata.owner}/${metadata.repo}")
            )
        return when (
            val lookup = GitProviderRegistry.forProvenance(
                context,
                metadata.providerId,
                metadata.host
            )
        ) {
            is GitProviderLookup.Available -> Located.Ok(lookup.provider, candidate)
            is GitProviderLookup.Unavailable -> Located.Failure(
                WorkspaceException.UnavailableProvider(lookup.id, lookup.reason)
            )
        }
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

    /**
     * Path containment check that works on every supported API level.
     *
     * `java.io.File.toPath()` is API 26 while `minSdk` is 24: on Android 7.x the
     * previous form threw `NoSuchMethodError`, `runCatching` swallowed it and
     * `diff()` reported `InvalidPath` for every file. Comparing canonical *string*
     * paths keeps the same guarantee — `canonicalFile` resolves `..` and symlinks,
     * so traversal is still rejected — and matches the idiom used elsewhere in
     * this code base (`DataBackupManager`, `AppSourcePackager`, `GitHubRepoFetcher`).
     *
     * `child == parent` is allowed and the separator suffix prevents a sibling
     * such as `/data/pods2` from being treated as inside `/data/pods`.
     */
    private fun isInside(parent: File, child: File): Boolean = isInsidePath(parent, child)

    private fun isBinary(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        val sample = bytes.take(4096)
        if (sample.any { it == 0.toByte() }) return true
        val decoded = runCatching { sample.toByteArray().toString(Charsets.UTF_8) }.getOrNull() ?: return true
        return decoded.count { it == '\uFFFD' } > 0
    }

    companion object {
        private const val TAG = "WorkspaceService"

        /** Above this, a text diff is refused instead of read into memory. */
        private const val MAX_DIFF_BYTES = 2L * 1024 * 1024

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

        /**
         * Canonical-path containment check, API 24 safe.
         *
         * Deliberately uses `java.io.File.canonicalPath` and not `java.nio.file`
         * (API 26). A path is inside its parent when it equals it or starts with
         * the parent path plus a separator, so `…/pods2` is not inside `…/pods`.
         * Both paths are canonicalised first, which collapses `..` segments and
         * resolves symlinks — a traversal attempt cannot escape by string trickery.
         * Any IO failure (unreadable parent, broken link) fails closed.
         */
        fun isInsidePath(parent: File, child: File): Boolean = runCatching {
            val parentPath = parent.canonicalPath.trimEnd(File.separatorChar)
            val childPath = child.canonicalPath
            childPath == parentPath || childPath.startsWith(parentPath + File.separator)
        }.getOrDefault(false)
    }
}

/** Typed failures the Studio UI can turn into a concrete message. */
sealed class WorkspaceException(message: String?) : Exception(message) {
    object InvalidPath : WorkspaceException("Invalid path")

    data class CleanRequired(val changed: Int) :
        WorkspaceException("Working copy has $changed uncommitted changes")

    /** The owner/repository pair is not a valid repository identity. */
    data class InvalidRepository(val value: String) :
        WorkspaceException("Not a repository: $value")

    /** No provider is configured for this operation; the UI explains [reason]. */
    data class UnavailableProvider(
        val id: GitProviderId,
        val reason: GitProviderUnavailableReason
    ) : WorkspaceException("Provider $id is unavailable: $reason")

    data class Provider(val kind: GitFailureKind, val detail: String?) :
        WorkspaceException(detail ?: kind.name)

    /**
     * The working copy's provider does not implement the operation this feature needs.
     * Today that means change tracking and pushing on a read-only Gitea/Forgejo driver.
     */
    data class UnsupportedProvider(val id: GitProviderId) : WorkspaceException(
        "${id.storageId} is read-only in this build for this operation"
    )

    data class Commit(val kind: RepoBridgeCommitFailureKind, val detail: String?) :
        WorkspaceException(detail ?: kind.name)
}
