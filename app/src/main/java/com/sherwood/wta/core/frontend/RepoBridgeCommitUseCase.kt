package com.sherwood.wta.core.frontend

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Why a commit cannot be prepared or pushed. */
enum class RepoBridgeCommitFailureKind {
    NO_METADATA,
    NO_BASE,
    NO_COMMIT_MESSAGE,
    NO_CHANGES,
    TOO_MANY_FILES,
    FILE_TOO_LARGE,
    IO_ERROR,
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    CONFLICT,
    NETWORK,
    REMOTE_REJECTED,
    UNEXPECTED
}

/** Paths that differ between the working copy and the remote commit, repo-relative. */
data class RepoBridgeChangeSet(
    val added: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val deleted: List<String> = emptyList()
) {
    val total: Int get() = added.size + modified.size + deleted.size
    val isEmpty: Boolean get() = total == 0
}

sealed class RepoBridgePrepareResult {
    data class Ready(
        val owner: String,
        val repo: String,
        val branch: String,
        val baseSha: String,
        val changes: RepoBridgeChangeSet
    ) : RepoBridgePrepareResult()

    data class Failure(
        val reason: RepoBridgeCommitFailureKind,
        val detail: String? = null
    ) : RepoBridgePrepareResult()
}

sealed class RepoBridgeCommitResult {
    data class Success(
        val commitSha: String,
        val changedFiles: Int,
        val metadataSaved: Boolean
    ) : RepoBridgeCommitResult()

    data class Failure(
        val reason: RepoBridgeCommitFailureKind,
        val detail: String? = null
    ) : RepoBridgeCommitResult()
}

/**
 * Coordinates "Commit & push" for an imported GitHub working copy.
 *
 * The workflow is deliberately server-side only: the working copy is diffed
 * against the tree of the commit recorded in `repo_bridge.json`, changed files
 * become blobs, one tree carries additions, updates and deletions, and the branch
 * ref is advanced with `force=false` and the imported commit as the expected base.
 *
 * There is no local git repository and no manifest file, so a user can edit files
 * with any tool and still get an accurate diff. The client applies the size and
 * count limits below so a mis-click cannot upload a huge tree one request at a
 * time.
 */
class RepoBridgeCommitUseCase(
    context: Context,
    private val projectDir: File,
    private val client: GitDataApiClient = GitDataApiClient(context),
    private val metadataStore: RepoBridgeMetadataStore = RepoBridgeMetadataStore()
) {

    private var cachedPlan: Plan? = null

    /** Reads the remote base tree and reports what a commit would upload. */
    suspend fun prepare(): RepoBridgePrepareResult = withContext(Dispatchers.IO) {
        when (val loaded = loadPlan()) {
            is PlanLoad.Error -> {
                cachedPlan = null
                RepoBridgePrepareResult.Failure(loaded.reason, loaded.detail)
            }
            is PlanLoad.Ok -> {
                val scanned = scanLocal(loaded.plan.prefix)
                if (scanned is ScanResult.Error) {
                    cachedPlan = null
                    return@withContext RepoBridgePrepareResult.Failure(
                        scanned.reason,
                        scanned.detail
                    )
                }
                val files = (scanned as ScanResult.Ok).files
                cachedPlan = loaded.plan
                val metadata = loaded.plan.metadata
                RepoBridgePrepareResult.Ready(
                    owner = metadata.owner,
                    repo = metadata.repo,
                    branch = metadata.ref,
                    baseSha = loaded.plan.baseSha,
                    changes = diff(files, loaded.plan.remoteBlobs)
                )
            }
        }
    }

    /** Uploads the working copy on top of the imported commit and advances the branch. */
    suspend fun commit(message: String): RepoBridgeCommitResult = withContext(Dispatchers.IO) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return@withContext RepoBridgeCommitResult.Failure(
                RepoBridgeCommitFailureKind.NO_COMMIT_MESSAGE
            )
        }

        val plan = cachedPlan ?: when (val loaded = loadPlan()) {
            is PlanLoad.Error -> return@withContext RepoBridgeCommitResult.Failure(
                loaded.reason,
                loaded.detail
            )
            is PlanLoad.Ok -> loaded.plan.also { cachedPlan = it }
        }

        val scanned = scanLocal(plan.prefix)
        if (scanned is ScanResult.Error) {
            return@withContext RepoBridgeCommitResult.Failure(
                scanned.reason,
                scanned.detail
            )
        }
        val files = (scanned as ScanResult.Ok).files
        val changes = diff(files, plan.remoteBlobs)

        if (changes.isEmpty) {
            return@withContext RepoBridgeCommitResult.Failure(
                RepoBridgeCommitFailureKind.NO_CHANGES
            )
        }
        if (changes.total > MAX_CHANGED_FILES) {
            return@withContext RepoBridgeCommitResult.Failure(
                RepoBridgeCommitFailureKind.TOO_MANY_FILES,
                "${changes.total}"
            )
        }

        val metadata = plan.metadata
        val entries = mutableListOf<GitTreeEntry>()

        for (path in changes.added + changes.modified) {
            val local = files[path] ?: continue
            if (local.size > MAX_FILE_BYTES) {
                return@withContext RepoBridgeCommitResult.Failure(
                    RepoBridgeCommitFailureKind.FILE_TOO_LARGE,
                    path
                )
            }
            val content = try {
                local.file.readBytes()
            } catch (e: IOException) {
                AppLogger.w(TAG, "Cannot read ${local.file.name}: ${e.javaClass.simpleName}")
                return@withContext RepoBridgeCommitResult.Failure(
                    RepoBridgeCommitFailureKind.IO_ERROR,
                    path
                )
            }
            when (val blob = client.createBlob(metadata.owner, metadata.repo, content)) {
                is GitDataResult.Success ->
                    entries += GitTreeEntry(path, blob.value, mode = FILE_MODE)
                is GitDataResult.Failure ->
                    return@withContext RepoBridgeCommitResult.Failure(
                        blob.kind.toCommitFailure(),
                        blob.message
                    )
            }
        }

        for (path in changes.deleted) {
            entries += GitTreeEntry(path, sha = null, mode = FILE_MODE)
        }

        val treeSha = when (val tree = client.createTree(
            owner = metadata.owner,
            repo = metadata.repo,
            baseTreeSha = plan.baseTreeSha,
            entries = entries
        )) {
            is GitDataResult.Success -> tree.value
            is GitDataResult.Failure -> return@withContext RepoBridgeCommitResult.Failure(
                tree.kind.toCommitFailure(),
                tree.message
            )
        }

        val commitSha = when (val commit = client.createCommit(
            owner = metadata.owner,
            repo = metadata.repo,
            message = trimmed,
            treeSha = treeSha,
            parentSha = plan.baseSha
        )) {
            is GitDataResult.Success -> commit.value
            is GitDataResult.Failure -> return@withContext RepoBridgeCommitResult.Failure(
                commit.kind.toCommitFailure(),
                commit.message
            )
        }

        when (val ref = client.updateRef(
            owner = metadata.owner,
            repo = metadata.repo,
            branch = metadata.ref,
            newSha = commitSha,
            expectedBaseSha = plan.baseSha
        )) {
            is GitDataResult.Success -> Unit
            is GitDataResult.Failure -> {
                if (ref.kind == GitDataFailureKind.CONFLICT) {
                    cachedPlan = null
                }
                return@withContext RepoBridgeCommitResult.Failure(
                    ref.kind.toCommitFailure(),
                    ref.message
                )
            }
        }

        cachedPlan = null
        val metadataSaved = runCatching {
            metadataStore.write(projectDir, metadata.copy(baseSha = commitSha))
        }.onFailure {
            AppLogger.w(TAG, "Commit pushed but metadata was not saved: ${it.javaClass.simpleName}")
        }.isSuccess

        RepoBridgeCommitResult.Success(
            commitSha = commitSha,
            changedFiles = changes.total,
            metadataSaved = metadataSaved
        )
    }

    private suspend fun loadPlan(): PlanLoad {
        if (!projectDir.isDirectory) {
            return PlanLoad.Error(RepoBridgeCommitFailureKind.NO_METADATA)
        }
        val metadata = metadataStore.read(projectDir)
            ?: return PlanLoad.Error(RepoBridgeCommitFailureKind.NO_METADATA)
        val baseSha = metadata.baseSha
            ?: return PlanLoad.Error(RepoBridgeCommitFailureKind.NO_BASE)

        val baseCommit = when (val result = client.getCommit(metadata.owner, metadata.repo, baseSha)) {
            is GitDataResult.Success -> result.value
            is GitDataResult.Failure ->
                return PlanLoad.Error(result.kind.toCommitFailure(), result.message)
        }

        val tree = when (val result = client.getTree(metadata.owner, metadata.repo, baseCommit.treeSha)) {
            is GitDataResult.Success -> result.value
            is GitDataResult.Failure ->
                return PlanLoad.Error(result.kind.toCommitFailure(), result.message)
        }

        if (tree.truncated) {
            return PlanLoad.Error(
                RepoBridgeCommitFailureKind.REMOTE_REJECTED,
                "Remote tree is truncated"
            )
        }

        val prefix = metadata.subPath
            ?.replace('\\', '/')
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "$it/" }

        val remoteBlobs = buildMap {
            for (entry in tree.entries) {
                if (entry.type != "blob" || entry.sha == null) continue
                if (prefix != null && !entry.path.startsWith(prefix)) continue
                put(entry.path, entry.sha)
            }
        }

        return PlanLoad.Ok(
            Plan(
                metadata = metadata,
                baseSha = baseSha,
                baseTreeSha = baseCommit.treeSha,
                prefix = prefix,
                remoteBlobs = remoteBlobs
            )
        )
    }

    private fun scanLocal(prefix: String?): ScanResult {
        val root = try {
            projectDir.canonicalFile
        } catch (e: IOException) {
            return ScanResult.Error(RepoBridgeCommitFailureKind.IO_ERROR)
        }
        if (!root.isDirectory) {
            return ScanResult.Error(RepoBridgeCommitFailureKind.NO_METADATA)
        }

        val rootPath = root.path + File.separator
        val files = LinkedHashMap<String, LocalFile>()

        val paths = root.walkTopDown()
            .onEnter { it == root || it.name != GIT_DIR }
            .filter { it.isFile }
            .toList()

        for (file in paths) {
            val canonical = try {
                file.canonicalFile
            } catch (e: IOException) {
                return ScanResult.Error(RepoBridgeCommitFailureKind.IO_ERROR, file.name)
            }
            if (!canonical.path.startsWith(rootPath)) continue

            val relativePath = canonical.relativeTo(root).invariantSeparatorsPath
            if (relativePath == RepoBridgeMetadataStore.FILE_NAME ||
                relativePath == "${RepoBridgeMetadataStore.FILE_NAME}.tmp"
            ) {
                continue
            }

            val size = canonical.length()
            if (size > MAX_FILE_BYTES) {
                return ScanResult.Error(
                    RepoBridgeCommitFailureKind.FILE_TOO_LARGE,
                    relativePath
                )
            }

            val blobSha = try {
                gitBlobSha(canonical)
            } catch (e: IOException) {
                return ScanResult.Error(RepoBridgeCommitFailureKind.IO_ERROR, relativePath)
            }

            val repoPath = prefix?.let { "$it$relativePath" } ?: relativePath
            files[repoPath] = LocalFile(canonical, blobSha, size)
        }

        return ScanResult.Ok(files)
    }

    private fun diff(
        local: Map<String, LocalFile>,
        remote: Map<String, String>
    ): RepoBridgeChangeSet {
        val added = local.keys.filter { it !in remote }.sorted()
        val modified = local.asSequence()
            .filter { (path, file) -> remote[path]?.let { it != file.blobSha } == true }
            .map { it.key }
            .sorted()
            .toList()
        val deleted = remote.keys.filter { it !in local }.sorted()
        return RepoBridgeChangeSet(added, modified, deleted)
    }

    /** Git object id of a blob: sha1("blob <size>\0" + content), lowercase hex. */
    private fun gitBlobSha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.US_ASCII))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xff
            hex[i * 2] = HEX_CHARS[value ushr 4]
            hex[i * 2 + 1] = HEX_CHARS[value and 0x0f]
        }
        return String(hex)
    }

    private fun GitDataFailureKind.toCommitFailure(): RepoBridgeCommitFailureKind = when (this) {
        GitDataFailureKind.UNAUTHORIZED -> RepoBridgeCommitFailureKind.UNAUTHORIZED
        GitDataFailureKind.FORBIDDEN -> RepoBridgeCommitFailureKind.FORBIDDEN
        GitDataFailureKind.RATE_LIMITED -> RepoBridgeCommitFailureKind.RATE_LIMITED
        GitDataFailureKind.CONFLICT -> RepoBridgeCommitFailureKind.CONFLICT
        GitDataFailureKind.NETWORK -> RepoBridgeCommitFailureKind.NETWORK
        GitDataFailureKind.UNPROCESSABLE -> RepoBridgeCommitFailureKind.REMOTE_REJECTED
        GitDataFailureKind.UNEXPECTED -> RepoBridgeCommitFailureKind.UNEXPECTED
        GitDataFailureKind.HTTP -> RepoBridgeCommitFailureKind.REMOTE_REJECTED
    }

    private data class Plan(
        val metadata: RepoBridgeMetadata,
        val baseSha: String,
        val baseTreeSha: String,
        val prefix: String?,
        val remoteBlobs: Map<String, String>
    )

    private data class LocalFile(
        val file: File,
        val blobSha: String,
        val size: Long
    )

    private sealed class PlanLoad {
        data class Ok(val plan: Plan) : PlanLoad()
        data class Error(
            val reason: RepoBridgeCommitFailureKind,
            val detail: String? = null
        ) : PlanLoad()
    }

    private sealed class ScanResult {
        data class Ok(val files: Map<String, LocalFile>) : ScanResult()
        data class Error(
            val reason: RepoBridgeCommitFailureKind,
            val detail: String? = null
        ) : ScanResult()
    }

    companion object {
        private const val TAG = "RepoBridgeCommit"

        const val MAX_CHANGED_FILES = 1000
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        private const val FILE_MODE = "100644"
        private const val GIT_DIR = ".git"

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
