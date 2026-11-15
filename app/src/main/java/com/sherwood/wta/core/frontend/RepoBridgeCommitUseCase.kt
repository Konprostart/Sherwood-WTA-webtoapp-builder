package com.sherwood.wta.core.frontend

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.git.GitBaseTree
import com.sherwood.wta.core.studio.git.GitCommitWriter
import com.sherwood.wta.core.studio.git.GitFailureKind
import com.sherwood.wta.core.studio.git.GitFileChange
import com.sherwood.wta.core.studio.git.GitHubCommitWriter
import com.sherwood.wta.core.studio.git.GitCommitWriterResolver
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitWriterResolution
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Why a commit cannot be prepared or pushed. */
enum class RepoBridgeCommitFailureKind {
    NO_METADATA,
    NO_BASE,
    NO_TOKEN,
    NOT_FOUND,
    NO_COMMIT_MESSAGE,
    NO_CHANGES,
    TOO_MANY_FILES,
    FILE_TOO_LARGE,
    MASS_DELETION_UNCONFIRMED,
    /** The working copy's host has no write support in this build. */
    PROVIDER_READ_ONLY,
    /** The working copy changed after it was reviewed; review it again. */
    REVIEW_STALE,
    /** A publish was asked for a branch that may not be created (base, protected, invalid). */
    INVALID_BRANCH,
    /** A file contains a credential format that is never pushed. */
    SECRET_BLOCKED,
    /** A file may contain a secret and the user has not confirmed it. */
    SECRET_WARNINGS_UNACCEPTED,
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

/**
 * A push that removes more than half of the remote files (or all of them) is a
 * mass deletion. An empty working copy against a non-empty remote is one as well.
 */
fun RepoBridgeChangeSet.isMassDeletion(remoteFileCount: Int): Boolean =
    deleted.isNotEmpty() && deleted.size * 2 > remoteFileCount

sealed class RepoBridgePrepareResult {
    data class Ready(
        val owner: String,
        val repo: String,
        val branch: String,
        val baseSha: String,
        val remoteFileCount: Int,
        val changes: RepoBridgeChangeSet,
        val review: RepoBridgeReview
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
 * Coordinates "Commit & push" for an imported working copy.
 *
 * The workflow is deliberately server-side only: the working copy is diffed
 * against the tree of the commit recorded in `repo_bridge.json`, changed files
 * become a new commit, and the branch ref is advanced with `force=false` and the
 * imported commit as the expected base.
 *
 * Publishing goes through a [GitCommitWriter] rather than a concrete host client, so the
 * review, the secret scanner and the "new branch, never forced" rule are written once and
 * apply to every hosting platform. When no writer is supplied, the working copy's own
 * provenance decides which one is used; a copy whose host has no write support in this
 * build fails with [RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY] instead of falling
 * back to a different host's writer.
 *
 * There is no local git repository and no manifest file, so a user can edit files
 * with any tool and still get an accurate diff. The size limit below only applies to
 * files that actually changed, and the file limit keeps one mis-click from uploading
 * a huge tree one request at a time.
 */
class RepoBridgeCommitUseCase(
    context: Context,
    private val projectDir: File,
    writer: GitCommitWriter? = null,
    private val metadataStore: RepoBridgeMetadataStore = RepoBridgeMetadataStore()
) {

    /**
     * An explicitly supplied writer always wins (the tests use that); otherwise the
     * working copy's provenance decides.
     */
    private val resolution: GitWriterResolution = writer
        ?.let { GitWriterResolution.Ok(it) }
        ?: GitCommitWriterResolver.resolve(context, projectDir)

    private val writer: GitCommitWriter?
        get() = (resolution as? GitWriterResolution.Ok)?.writer

    private var cachedPlan: Plan? = null

    /** Serializes prepare and commit: two overlapping calls would race on cachedPlan. */
    private val commitMutex = Mutex()

    /** Reads the remote base tree and reports what a commit would upload. */
    suspend fun prepare(): RepoBridgePrepareResult = withContext(Dispatchers.IO) {
        if (writer == null) {
            return@withContext RepoBridgePrepareResult.Failure(
                RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY
            )
        }
        try {
            commitMutex.withLock { prepareInternal() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.w(TAG, "Prepare failed: ${e.javaClass.simpleName}")
            cachedPlan = null
            RepoBridgePrepareResult.Failure(RepoBridgeCommitFailureKind.UNEXPECTED)
        }
    }

    private suspend fun prepareInternal(): RepoBridgePrepareResult {
        val loaded = loadPlan()
        if (loaded is PlanLoad.Error) {
            cachedPlan = null
            return RepoBridgePrepareResult.Failure(loaded.reason, loaded.detail)
        }
        val plan = (loaded as PlanLoad.Ok).plan
        val scanned = scanLocal(plan.prefix)
        if (scanned is ScanResult.Error) {
            cachedPlan = null
            return RepoBridgePrepareResult.Failure(scanned.reason, scanned.detail)
        }
        val files = (scanned as ScanResult.Ok).files
        val changes = diff(files, plan.remoteBlobs)
        val limits = writer?.publishLimits ?: GitCommitWriter.DEFAULT_PUBLISH_LIMITS
        val oversized = oversizedChange(changes, files, limits.maxFileBytes)
        if (oversized != null) {
            cachedPlan = null
            return RepoBridgePrepareResult.Failure(
                RepoBridgeCommitFailureKind.FILE_TOO_LARGE,
                oversized
            )
        }
        val review = when (val built = buildReview(changes, files, plan, limits.maxFileBytes)) {
            is ReviewBuild.Error -> {
                cachedPlan = null
                return RepoBridgePrepareResult.Failure(built.reason, built.detail)
            }
            is ReviewBuild.Ok -> built.review
        }
        cachedPlan = plan
        return RepoBridgePrepareResult.Ready(
            owner = plan.metadata.owner,
            repo = plan.metadata.repo,
            branch = plan.metadata.ref,
            baseSha = plan.baseSha,
            remoteFileCount = plan.remoteBlobs.size,
            changes = changes,
            review = review
        )
    }

    /**
     * Uploads the working copy on top of the imported commit and advances the branch.
     *
     * [decision] is the user's review. When present, its fingerprint must match the working
     * copy at send time, and its exclusions and accepted warnings are applied. Blocking
     * findings are refused in every case.
     *
     * [confirmedDeletions] is the number of deletions the user saw and accepted. A mass
     * deletion is refused unless the push removes no more files than that.
     */
    suspend fun commit(
        message: String,
        confirmedDeletions: Int = 0,
        decision: ReviewDecision? = null
    ): RepoBridgeCommitResult = withContext(Dispatchers.IO) {
        if (writer == null) {
            return@withContext RepoBridgeCommitResult.Failure(
                RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY
            )
        }
        try {
            commitMutex.withLock { commitInternal(message, confirmedDeletions, decision) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.w(TAG, "Commit failed: ${e.javaClass.simpleName}")
            cachedPlan = null
            RepoBridgeCommitResult.Failure(RepoBridgeCommitFailureKind.UNEXPECTED)
        }
    }

    /**
     * Publishes the working copy as one commit on a brand-new branch [branch].
     *
     * This is the only path the agent's publish tool uses, and it keeps the rules the
     * publisher states: the branch is new, it is never the branch the copy was imported
     * from, and no ref is forced. On a single-step host (Gitea/Forgejo) the branch and
     * the commit are created by the same request, so a refused change set leaves nothing
     * behind; on a staged host (GitHub) the branch is created at the imported commit and
     * removed again if the commit cannot be built.
     *
     * The working copy's provenance is updated to the new branch only after the host
     * accepted the commit, so a failure leaves the copy exactly as it was.
     */
    suspend fun commitNewBranch(
        message: String,
        branch: String,
        decision: ReviewDecision? = null
    ): RepoBridgeCommitResult = withContext(Dispatchers.IO) {
        if (writer == null) {
            return@withContext RepoBridgeCommitResult.Failure(
                RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY
            )
        }
        try {
            commitMutex.withLock { commitNewBranchInternal(message, branch, decision) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.w(TAG, "New-branch commit failed: ${e.javaClass.simpleName}")
            cachedPlan = null
            RepoBridgeCommitResult.Failure(RepoBridgeCommitFailureKind.UNEXPECTED)
        }
    }

    private suspend fun commitNewBranchInternal(
        message: String,
        branch: String,
        decision: ReviewDecision?
    ): RepoBridgeCommitResult {
        val writer = writer ?: return RepoBridgeCommitResult.Failure(
            RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY
        )
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return RepoBridgeCommitResult.Failure(RepoBridgeCommitFailureKind.NO_COMMIT_MESSAGE)
        }
        val plan = cachedPlan ?: when (val loaded = loadPlan()) {
            is PlanLoad.Error -> return RepoBridgeCommitResult.Failure(loaded.reason, loaded.detail)
            is PlanLoad.Ok -> loaded.plan.also { cachedPlan = it }
        }
        newBranchProblem(branch, plan.metadata.ref)?.let { problem ->
            return RepoBridgeCommitResult.Failure(
                RepoBridgeCommitFailureKind.INVALID_BRANCH,
                problem
            )
        }
        val prepared = when (val result = preparePush(plan, confirmedDeletions = 0, decision)) {
            is PushPreparation.Error -> return RepoBridgeCommitResult.Failure(
                result.reason,
                result.detail
            )
            is PushPreparation.Ok -> result
        }
        val metadata = plan.metadata
        val repo = GitRepoRef.of(metadata.owner, metadata.repo)
            ?: return RepoBridgeCommitResult.Failure(RepoBridgeCommitFailureKind.NO_METADATA)

        return when (
            val published = writer.publishNewBranch(
                ref = repo,
                baseBranch = metadata.ref,
                branch = branch,
                expectedBaseSha = plan.baseSha,
                message = trimmed,
                changes = prepared.fileChanges
            )
        ) {
            is GitResult.Success -> {
                cachedPlan = null
                // The copy now tracks the branch it just produced, so the next status and
                // commit compare against the pushed state instead of re-offering the diff.
                val saved = saveMetadata(
                    metadata.copy(
                        ref = branch,
                        baseSha = published.value.commitSha,
                        pendingBaseSha = null
                    )
                )
                RepoBridgeCommitResult.Success(
                    commitSha = published.value.commitSha,
                    changedFiles = prepared.changes.total,
                    metadataSaved = saved
                )
            }
            is GitResult.Failure -> {
                // Nothing was published; the cached diff may describe an older base.
                cachedPlan = null
                RepoBridgeCommitResult.Failure(
                    published.kind.toCommitFailure(),
                    published.message
                )
            }
        }
    }

    /**
     * The structural rule for a task branch: a valid ref, not the branch the copy was
     * imported from and not one of the shared branches a task must never write to.
     */
    private fun newBranchProblem(branch: String, baseBranch: String): String? {
        val name = branch.trim()
        if (name.isEmpty()) return "the branch name is empty"
        if (!WorkspaceService.isValidBranchName(name)) {
            return "'$name' is not a valid Git branch name"
        }
        if (name.equals(baseBranch.trim(), ignoreCase = true)) {
            return "'$name' is the branch the folder was imported from; a task always " +
                "gets a new one"
        }
        if (name.lowercase() in PROTECTED_BRANCHES) {
            return "'$name' is a shared branch a task must never write to"
        }
        return null
    }

    private fun saveMetadata(metadata: RepoBridgeMetadata): Boolean =
        runCatching { metadataStore.write(projectDir, metadata) }
            .onFailure {
                AppLogger.w(TAG, "Commit pushed but metadata was not saved: ${it.javaClass.simpleName}")
            }
            .isSuccess

    private suspend fun commitInternal(
        message: String,
        confirmedDeletions: Int,
        decision: ReviewDecision?
    ): RepoBridgeCommitResult {
        val writer = writer ?: return RepoBridgeCommitResult.Failure(
            RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY
        )
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return RepoBridgeCommitResult.Failure(
                RepoBridgeCommitFailureKind.NO_COMMIT_MESSAGE
            )
        }

        val plan = cachedPlan ?: when (val loaded = loadPlan()) {
            is PlanLoad.Error -> return RepoBridgeCommitResult.Failure(
                loaded.reason,
                loaded.detail
            )
            is PlanLoad.Ok -> loaded.plan.also { cachedPlan = it }
        }

        val prepared = when (val result = preparePush(plan, confirmedDeletions, decision)) {
            is PushPreparation.Error -> return RepoBridgeCommitResult.Failure(
                result.reason,
                result.detail
            )
            is PushPreparation.Ok -> result
        }

        val metadata = plan.metadata
        val repo = GitRepoRef.of(metadata.owner, metadata.repo)
            ?: return RepoBridgeCommitResult.Failure(RepoBridgeCommitFailureKind.NO_METADATA)

        // A single-step host writes the commit and the branch together, so there is no
        // intermediate state to recover: `pendingBaseSha` is only meaningful for the
        // staged sequence below.
        if (writer.commitsInOneStep) {
            return when (
                val published = writer.publishToBranch(
                    ref = repo,
                    branch = metadata.ref,
                    expectedBaseSha = plan.baseSha,
                    message = trimmed,
                    changes = prepared.fileChanges
                )
            ) {
                is GitResult.Success -> {
                    cachedPlan = null
                    RepoBridgeCommitResult.Success(
                        commitSha = published.value.commitSha,
                        changedFiles = prepared.changes.total,
                        metadataSaved = saveMetadata(
                            metadata.copy(
                                baseSha = published.value.commitSha,
                                pendingBaseSha = null
                            )
                        )
                    )
                }
                is GitResult.Failure -> {
                    cachedPlan = null
                    RepoBridgeCommitResult.Failure(
                        published.kind.toCommitFailure(),
                        published.message
                    )
                }
            }
        }

        val commitSha = when (val commit = writer.createCommit(
            ref = repo,
            parentSha = plan.baseSha,
            baseTreeSha = plan.baseTreeSha,
            message = trimmed,
            changes = prepared.fileChanges
        )) {
            is GitResult.Success -> commit.value
            is GitResult.Failure -> return RepoBridgeCommitResult.Failure(
                commit.kind.toCommitFailure(),
                commit.message
            )
        }

        // Record the commit we are about to publish. If the process dies or the metadata
        // write fails after the ref moves, the next prepare can still find it.
        try {
            metadataStore.write(projectDir, metadata.copy(pendingBaseSha = commitSha))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pending base not recorded: ${e.javaClass.simpleName}")
            return RepoBridgeCommitResult.Failure(RepoBridgeCommitFailureKind.IO_ERROR, FILE_METADATA)
        }

        when (val moved = writer.moveBranch(
            ref = repo,
            branch = metadata.ref,
            commitSha = commitSha,
            expectedBaseSha = plan.baseSha
        )) {
            is GitResult.Success -> Unit
            is GitResult.Failure -> {
                // Any failure leaves the cached plan stale: the base commit may no longer
                // describe the remote.
                cachedPlan = null
                return RepoBridgeCommitResult.Failure(
                    moved.kind.toCommitFailure(),
                    moved.message
                )
            }
        }

        cachedPlan = null
        return RepoBridgeCommitResult.Success(
            commitSha = commitSha,
            changedFiles = prepared.changes.total,
            metadataSaved = saveMetadata(
                metadata.copy(baseSha = commitSha, pendingBaseSha = null)
            )
        )
    }

    /** The change set a commit would send, after every local gate has passed. */
    private sealed class PushPreparation {
        data class Ok(
            val changes: RepoBridgeChangeSet,
            val fileChanges: List<GitFileChange>
        ) : PushPreparation()

        data class Error(
            val reason: RepoBridgeCommitFailureKind,
            val detail: String? = null
        ) : PushPreparation()
    }

    /**
     * Runs every gate between the working copy and the host: scan, diff, size, review,
     * secret findings and mass deletion. Nothing is sent from here.
     *
     * The size limits come from the writer, because they bound the request body a host
     * accepts and therefore differ per platform. An oversized set is refused, never
     * truncated: a partial push would look like a complete one.
     */
    private fun preparePush(
        plan: Plan,
        confirmedDeletions: Int,
        decision: ReviewDecision?
    ): PushPreparation {
        val limits = writer?.publishLimits ?: GitCommitWriter.DEFAULT_PUBLISH_LIMITS
        val scanned = scanLocal(plan.prefix)
        if (scanned is ScanResult.Error) {
            return PushPreparation.Error(scanned.reason, scanned.detail)
        }
        val files = (scanned as ScanResult.Ok).files
        val allChanges = diff(files, plan.remoteBlobs)
        if (allChanges.isEmpty) {
            return PushPreparation.Error(RepoBridgeCommitFailureKind.NO_CHANGES)
        }

        // Size, count and exclusion gates run before any file is read, so a huge file
        // that slipped in after Prepare fails fast instead of exhausting memory.
        val excluded = decision?.excluded.orEmpty()
        val changes = allChanges.without(excluded)
        if (changes.isEmpty) {
            return PushPreparation.Error(RepoBridgeCommitFailureKind.NO_CHANGES)
        }
        if (changes.total > limits.maxFiles) {
            return PushPreparation.Error(
                RepoBridgeCommitFailureKind.TOO_MANY_FILES,
                "${changes.total} (the server accepts ${limits.maxFiles} per commit)"
            )
        }
        oversizedChange(changes, files, limits.maxFileBytes)?.let { detail ->
            return PushPreparation.Error(RepoBridgeCommitFailureKind.FILE_TOO_LARGE, detail)
        }
        totalBytesProblem(changes, files, limits.maxBytes)?.let { detail ->
            return PushPreparation.Error(RepoBridgeCommitFailureKind.FILE_TOO_LARGE, detail)
        }

        val review = when (val built = buildReview(allChanges, files, plan, limits.maxFileBytes)) {
            is ReviewBuild.Error -> return PushPreparation.Error(built.reason, built.detail)
            is ReviewBuild.Ok -> built.review
        }
        if (decision != null && decision.fingerprint != review.fingerprint) {
            cachedPlan = null
            return PushPreparation.Error(RepoBridgeCommitFailureKind.REVIEW_STALE)
        }

        val pushed = review.items.filter { it.path !in excluded }
        pushed.firstOrNull { it.hasBlocking }?.let { blocked ->
            return PushPreparation.Error(
                RepoBridgeCommitFailureKind.SECRET_BLOCKED,
                blocked.path
            )
        }
        val acceptedWarnings = decision?.acceptedWarnings.orEmpty()
        pushed.firstOrNull { it.hasWarning && it.path !in acceptedWarnings }?.let { warned ->
            return PushPreparation.Error(
                RepoBridgeCommitFailureKind.SECRET_WARNINGS_UNACCEPTED,
                warned.path
            )
        }

        if (changes.isMassDeletion(plan.remoteBlobs.size) &&
            changes.deleted.size > confirmedDeletions
        ) {
            return PushPreparation.Error(
                RepoBridgeCommitFailureKind.MASS_DELETION_UNCONFIRMED,
                "${changes.deleted.size} of ${plan.remoteBlobs.size}"
            )
        }

        val fileChanges = ArrayList<GitFileChange>(changes.total)

        for (path in changes.added + changes.modified) {
            val local = files[path] ?: continue
            // The bytes uploaded must be the bytes that were reviewed: re-read and re-hash.
            val content = try {
                local.file.readBytes()
            } catch (e: IOException) {
                AppLogger.w(TAG, "Cannot read ${local.file.name}: ${e.javaClass.simpleName}")
                return PushPreparation.Error(RepoBridgeCommitFailureKind.IO_ERROR, path)
            }
            if (blobShaOf(content) != local.blobSha) {
                cachedPlan = null
                return PushPreparation.Error(RepoBridgeCommitFailureKind.REVIEW_STALE, path)
            }
            fileChanges += GitFileChange(
                path = path,
                mode = modeFor(plan, path),
                content = content,
                // The blob the change was computed against. A host that validates it
                // refuses the write when someone else changed the same file first.
                baseSha = plan.remoteBlobs[path]
            )
        }

        // Deletions are explicit tree entries, never implied by omission.
        for (path in changes.deleted) {
            fileChanges += GitFileChange(
                path = path,
                mode = FILE_MODE,
                content = null,
                baseSha = plan.remoteBlobs[path]
            )
        }

        return PushPreparation.Ok(changes = changes, fileChanges = fileChanges)
    }

    private sealed class ReviewBuild {
        data class Ok(val review: RepoBridgeReview) : ReviewBuild()
        data class Error(
            val reason: RepoBridgeCommitFailureKind,
            val detail: String? = null
        ) : ReviewBuild()
    }

    /**
     * Builds the review of [changes]. Each added or modified file is read once and must hash
     * to the blob sha recorded by [scanLocal]; otherwise it changed since the scan and the
     * review is stale. Secret findings come from those same bytes.
     */
    private fun buildReview(
        changes: RepoBridgeChangeSet,
        files: Map<String, LocalFile>,
        plan: Plan,
        maxFileBytes: Long
    ): ReviewBuild {
        val items = mutableListOf<ReviewItem>()
        val entries = mutableListOf<Triple<ReviewStatus, String, String>>()

        val uploaded = listOf(
            ReviewStatus.ADDED to changes.added,
            ReviewStatus.MODIFIED to changes.modified
        )
        for ((status, paths) in uploaded) {
            for (path in paths) {
                val local = files[path] ?: continue
                if (local.size > maxFileBytes) {
                    return ReviewBuild.Error(RepoBridgeCommitFailureKind.FILE_TOO_LARGE, path)
                }
                val content = try {
                    local.file.readBytes()
                } catch (e: IOException) {
                    return ReviewBuild.Error(RepoBridgeCommitFailureKind.IO_ERROR, path)
                }
                if (blobShaOf(content) != local.blobSha) {
                    return ReviewBuild.Error(RepoBridgeCommitFailureKind.REVIEW_STALE, path)
                }
                items += ReviewItem(
                    path = path,
                    status = status,
                    sizeBytes = local.size,
                    findings = SecretScanner.scan(path, content)
                )
                entries += Triple(status, path, local.blobSha)
            }
        }
        for (path in changes.deleted) {
            val remoteSha = plan.remoteBlobs[path] ?: continue
            items += ReviewItem(path, ReviewStatus.DELETED, 0L, emptyList())
            entries += Triple(ReviewStatus.DELETED, path, remoteSha)
        }
        return ReviewBuild.Ok(
            RepoBridgeReview(
                items = items.sortedBy { it.path },
                fingerprint = reviewFingerprint(entries)
            )
        )
    }

    private fun RepoBridgeChangeSet.without(excluded: Set<String>): RepoBridgeChangeSet =
        RepoBridgeChangeSet(
            added = added.filterNot { it in excluded },
            modified = modified.filterNot { it in excluded },
            deleted = deleted.filterNot { it in excluded }
        )

    /** Git blob sha of in-memory content: sha1("blob <size>\0" + content), lowercase hex. */
    private fun blobShaOf(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(content)
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private suspend fun loadPlan(): PlanLoad {
        val writer = writer ?: return PlanLoad.Error(
            RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY
        )
        if (!projectDir.isDirectory) {
            return PlanLoad.Error(RepoBridgeCommitFailureKind.NO_METADATA)
        }
        var metadata = metadataStore.read(projectDir)
            ?: return PlanLoad.Error(RepoBridgeCommitFailureKind.NO_METADATA)

        // A push that reached the host but could not record its base leaves a pending sha.
        // If the branch already points at it, adopt it; otherwise the pending sha is stale.
        metadata.pendingBaseSha?.let { pending ->
            val repo = GitRepoRef.of(metadata.owner, metadata.repo)
            if (repo == null) return PlanLoad.Error(RepoBridgeCommitFailureKind.NO_METADATA)
            val remoteHead = when (val head = writer.branchHead(repo, metadata.ref)) {
                is GitResult.Success -> head.value
                is GitResult.Failure ->
                    return PlanLoad.Error(head.kind.toCommitFailure(), head.message)
            }
            metadata = if (remoteHead == pending) {
                AppLogger.i(TAG, "Adopting the pushed base commit left pending by an earlier run")
                metadata.copy(baseSha = pending, pendingBaseSha = null)
            } else {
                metadata.copy(pendingBaseSha = null)
            }
            runCatching { metadataStore.write(projectDir, metadata) }
                .onFailure { AppLogger.w(TAG, "Could not persist recovered base: ${it.javaClass.simpleName}") }
        }

        val baseSha = metadata.baseSha
            ?: return PlanLoad.Error(RepoBridgeCommitFailureKind.NO_BASE)

        val repo = GitRepoRef.of(metadata.owner, metadata.repo)
            ?: return PlanLoad.Error(RepoBridgeCommitFailureKind.NO_METADATA)

        val baseTree: GitBaseTree = when (val result = writer.baseTree(repo, baseSha)) {
            is GitResult.Success -> result.value
            is GitResult.Failure ->
                return PlanLoad.Error(result.kind.toCommitFailure(), result.message)
        }

        if (baseTree.snapshot.truncated) {
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

        val remoteBlobs = linkedMapOf<String, String>()
        val remoteModes = linkedMapOf<String, String>()
        for (entry in baseTree.snapshot.entries) {
            if (entry.type != "blob" || entry.sha == null) continue
            if (prefix != null && !entry.path.startsWith(prefix)) continue
            if (isSkippedRemotePath(entry.path)) continue
            remoteBlobs[entry.path] = entry.sha
            remoteModes[entry.path] = entry.mode
        }

        return PlanLoad.Ok(
            Plan(
                metadata = metadata,
                baseSha = baseSha,
                baseTreeSha = baseTree.treeSha,
                prefix = prefix,
                remoteBlobs = remoteBlobs,
                remoteModes = remoteModes
            )
        )
    }

    /** Matches GitHubRepoFetcher's skip list so a repo that tracks these never reports them deleted. */
    private fun isSkippedRemotePath(path: String): Boolean =
        path.split('/').any { part ->
            part.equals("__MACOSX", ignoreCase = true) ||
                part.equals(".DS_Store", ignoreCase = true) ||
                part.equals("Thumbs.db", ignoreCase = true)
        }

    /**
     * The mode a path keeps on push. The base tree is the source of truth: a script that
     * was 100755 on GitHub stays 100755 after an edit, and a symlink stays 120000 whose
     * content is its target. New paths get a regular file.
     */
    private fun modeFor(plan: Plan, path: String): String =
        plan.remoteModes[path]?.takeIf { it in ALLOWED_MODES } ?: FILE_MODE

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

        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            // A directory we cannot list must not look like an empty one: remote files
            // under it would otherwise be reported as DELETED and pushed as deletions.
            val children = dir.listFiles()
                ?: return ScanResult.Error(RepoBridgeCommitFailureKind.IO_ERROR, dir.name)
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name == GIT_DIR) continue
                    if (isSymlink(child)) continue
                    pending.addLast(child)
                    continue
                }
                if (!child.isFile) {
                    return ScanResult.Error(RepoBridgeCommitFailureKind.IO_ERROR, child.name)
                }

                val canonical = try {
                    child.canonicalFile
                } catch (e: IOException) {
                    return ScanResult.Error(RepoBridgeCommitFailureKind.IO_ERROR, child.name)
                }
                if (!canonical.path.startsWith(rootPath)) continue

                val relativePath = canonical.relativeTo(root).invariantSeparatorsPath
                if (relativePath == RepoBridgeMetadataStore.FILE_NAME ||
                    relativePath == "${RepoBridgeMetadataStore.FILE_NAME}.tmp"
                ) {
                    continue
                }

                val size = canonical.length()
                val blobSha = try {
                    gitBlobSha(canonical)
                } catch (e: IOException) {
                    return ScanResult.Error(RepoBridgeCommitFailureKind.IO_ERROR, relativePath)
                }

                val repoPath = prefix?.let { "$it$relativePath" } ?: relativePath
                files[repoPath] = LocalFile(canonical, blobSha, size)
            }
        }

        return ScanResult.Ok(files)
    }

    private fun isSymlink(file: File): Boolean = try {
        file.canonicalPath != file.absolutePath
    } catch (e: IOException) {
        true
    }

    /**
     * Reports the first path that changed and is too large to upload. Unchanged
     * files never block a commit, however big they are, because they are not sent.
     */
    private fun oversizedChange(
        changes: RepoBridgeChangeSet,
        files: Map<String, LocalFile>,
        maxFileBytes: Long
    ): String? {
        for (path in changes.added + changes.modified) {
            val size = files[path]?.size ?: continue
            if (size > maxFileBytes) {
                return "$path (${size / (1024 * 1024)} MB)"
            }
        }
        return null
    }

    /**
     * Reports a change set whose bytes alone exceed what one publish may carry.
     *
     * The per-file gate does not catch this: a thousand small files can add up to more
     * than the upload path can hold in memory, and a partially sent change set is worse
     * than a refused one.
     */
    private fun totalBytesProblem(
        changes: RepoBridgeChangeSet,
        files: Map<String, LocalFile>,
        maxBytes: Long
    ): String? {
        var total = 0L
        for (path in changes.added + changes.modified) {
            total += files[path]?.size ?: 0L
            if (total > maxBytes) {
                return "${total / (1024 * 1024)} MB of changes; one commit may carry at " +
                    "most ${maxBytes / (1024 * 1024)} MB"
            }
        }
        return null
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

    private fun GitFailureKind.toCommitFailure(): RepoBridgeCommitFailureKind = when (this) {
        GitFailureKind.NO_TOKEN -> RepoBridgeCommitFailureKind.NO_TOKEN
        GitFailureKind.NOT_FOUND -> RepoBridgeCommitFailureKind.NOT_FOUND
        GitFailureKind.UNAUTHORIZED -> RepoBridgeCommitFailureKind.UNAUTHORIZED
        GitFailureKind.FORBIDDEN -> RepoBridgeCommitFailureKind.FORBIDDEN
        GitFailureKind.RATE_LIMITED -> RepoBridgeCommitFailureKind.RATE_LIMITED
        GitFailureKind.CONFLICT -> RepoBridgeCommitFailureKind.CONFLICT
        GitFailureKind.NETWORK -> RepoBridgeCommitFailureKind.NETWORK
        GitFailureKind.UNPROCESSABLE -> RepoBridgeCommitFailureKind.REMOTE_REJECTED
        GitFailureKind.UNEXPECTED -> RepoBridgeCommitFailureKind.UNEXPECTED
        GitFailureKind.HTTP -> RepoBridgeCommitFailureKind.REMOTE_REJECTED
        GitFailureKind.UNSUPPORTED -> RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY
    }

    private data class Plan(
        val metadata: RepoBridgeMetadata,
        val baseSha: String,
        val baseTreeSha: String,
        val prefix: String?,
        val remoteBlobs: Map<String, String>,
        val remoteModes: Map<String, String>
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

        /**
         * Per-file ceiling for changed files. Kept low because the upload path holds
         * the bytes, the base64 string and the JSON body in memory at once; 10 MB
         * peaks around 50 MB of heap on a phone.
         */
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        private const val FILE_MODE = "100644"
        private const val FILE_METADATA = "repo_bridge.json"

        /** Modes GitHub accepts for blobs: regular, executable, and symlink. */
        private val ALLOWED_MODES = setOf("100644", "100755", "120000")
        private const val GIT_DIR = ".git"

        /** Shared branches a task branch must never write to. */
        private val PROTECTED_BRANCHES = setOf("main", "master", "head", "trunk", "develop")

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
