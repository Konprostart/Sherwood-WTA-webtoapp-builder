package com.sherwood.wta.core.studio

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.git.GitArtifact
import com.sherwood.wta.core.studio.git.GitPipeline
import com.sherwood.wta.core.studio.git.GitPipelineRun
import com.sherwood.wta.core.studio.git.GitProvider
import com.sherwood.wta.core.studio.git.GitProviderLookup
import com.sherwood.wta.core.studio.git.GitProviderRegistry
import com.sherwood.wta.core.studio.git.GitRelease
import com.sherwood.wta.core.studio.git.GitReleaseAsset
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.git.GitRunJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

data class BuildRunDetails(
    val run: GitPipelineRun,
    val jobs: List<GitRunJob>
)

data class ArtifactDownloadResult(
    val records: List<VaultArtifact>,
    val skipped: List<String>
)

/** What a branch build is doing right now, so the UI can show progress instead of a spinner. */
enum class BuildPhase {
    DISPATCHING,
    WAITING_FOR_RUN,
    RUNNING,
    DOWNLOADING,
    FINISHED
}

data class BuildProgress(
    val phase: BuildPhase,
    val run: GitPipelineRun? = null
)

/**
 * Result of dispatching a workflow on an existing branch and waiting for it.
 *
 * [succeeded] is the workflow's own conclusion, not the success of this call: the
 * app successfully observed a build that failed, and the UI can say which.
 */
data class BranchBuildResult(
    val run: GitPipelineRun,
    val succeeded: Boolean,
    val artifacts: ArtifactDownloadResult,
    /** Set when the run succeeded but its artifacts could not be imported. */
    val downloadError: String? = null
)

/** The build was still queued or running when the caller's deadline passed. */
class BuildTimeoutException : Exception("The build did not finish in time")

/**
 * CI Build Studio.
 *
 * Everything is read through the active [GitProvider]: pipeline list, manual dispatch,
 * runs, jobs, logs and artifacts. Downloaded artifact zips are unpacked in memory (with a
 * hard size cap), and every `.apk`/`.aab` inside is stored in the local [ArtifactVault]
 * together with its SHA-256, source repository, branch, commit and run id — so the file on
 * the phone can always be traced to the exact build.
 *
 * Artifacts are built by the repository's own workflows. No author-owned build server is
 * involved; the app never calls any infrastructure other than the connected git host.
 */
class BuildStudio(private val context: Context) {

    private val vault = ArtifactVault.getInstance(context)

    suspend fun workflows(owner: String, repo: String): Result<List<GitPipeline>> =
        withProvider(owner, repo) { provider, ref -> provider.pipelines(ref).toResult() }

    suspend fun runs(
        owner: String,
        repo: String,
        branch: String? = null,
        perPage: Int = 20
    ): Result<List<GitPipelineRun>> = withProvider(owner, repo) { provider, ref ->
        provider.runs(ref, branch, perPage).toResult()
    }

    suspend fun runDetails(owner: String, repo: String, runId: Long): Result<BuildRunDetails> =
        withProvider(owner, repo) { provider, ref ->
            when (val run = provider.run(ref, runId)) {
                is GitResult.Failure -> Result.failure(
                    WorkspaceException.Provider(run.kind, run.message)
                )
                is GitResult.Success -> when (val jobs = provider.jobs(ref, runId)) {
                    is GitResult.Failure -> Result.failure(
                        WorkspaceException.Provider(jobs.kind, jobs.message)
                    )
                    is GitResult.Success -> Result.success(
                        BuildRunDetails(run.value, jobs.value)
                    )
                }
            }
        }

    suspend fun logs(owner: String, repo: String, runId: Long): Result<String> =
        withProvider(owner, repo) { provider, ref -> provider.runLogs(ref, runId).toResult() }

    suspend fun artifacts(owner: String, repo: String, runId: Long): Result<List<GitArtifact>> =
        withProvider(owner, repo) { provider, ref ->
            provider.runArtifacts(ref, runId).toResult()
        }

    suspend fun dispatch(
        owner: String,
        repo: String,
        workflowIdOrFile: String,
        ref: String,
        inputs: Map<String, String> = emptyMap()
    ): Result<Unit> = withProvider(owner, repo) { provider, repoRef ->
        provider.dispatch(repoRef, workflowIdOrFile, ref, inputs).toResult()
    }

    /**
     * Builds an already-imported branch end to end: dispatch → wait for the run →
     * wait for it to finish → import its APK/AAB artifacts into the vault.
     *
     * The dispatch call returns no run id, so the run is identified by snapshotting the
     * branch's runs *before* dispatching and taking the first new run of the same
     * pipeline afterwards. That is why the pipeline id is passed separately from the
     * dispatch identifier.
     *
     * Artifacts are imported only when the run concluded successfully: a failed run may
     * still upload a partial bundle, and putting that in the vault under the same
     * provenance as a real build would be misleading. The failed run itself is returned
     * so the UI can name the conclusion and link to it.
     */
    suspend fun buildBranch(
        owner: String,
        repo: String,
        workflow: GitPipeline,
        ref: String,
        timeoutMs: Long = DEFAULT_BUILD_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        onProgress: suspend (BuildProgress) -> Unit = {}
    ): Result<BranchBuildResult> = withProvider(owner, repo) { provider, repoRef ->
        if (ref.isBlank()) {
            return@withProvider Result.failure(IllegalArgumentException("Branch is required"))
        }
        onProgress(BuildProgress(BuildPhase.DISPATCHING))

        val knownRunIds = when (val before = provider.runs(repoRef, ref, 30)) {
            is GitResult.Success -> before.value.map { it.id }.toSet()
            // A listing failure must not silently turn into "any run is ours": abort.
            is GitResult.Failure -> return@withProvider Result.failure(
                WorkspaceException.Provider(before.kind, before.message)
            )
        }

        when (
            val dispatched = provider.dispatch(repoRef, workflow.id, ref)
        ) {
            is GitResult.Failure -> return@withProvider Result.failure(
                WorkspaceException.Provider(dispatched.kind, dispatched.message)
            )
            is GitResult.Success -> Unit
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var interval = pollIntervalMs
        var run: GitPipelineRun? = null
        onProgress(BuildProgress(BuildPhase.WAITING_FOR_RUN))

        while (run == null) {
            if (System.currentTimeMillis() >= deadline) {
                return@withProvider Result.failure(BuildTimeoutException())
            }
            delay(interval)
            val listed = provider.runs(repoRef, ref, 30)
            if (listed is GitResult.Success) {
                run = selectDispatchedRun(listed.value, knownRunIds, workflow)
            }
            interval = (interval + POLL_BACKOFF_MS).coerceAtMost(MAX_POLL_INTERVAL_MS)
        }

        // The wait loop only exits after assigning a run. Making that explicit keeps
        // the nullable type out of the rest of the function.
        val started = run ?: return@withProvider Result.failure(BuildTimeoutException())
        onProgress(BuildProgress(BuildPhase.RUNNING, started))
        var current = started
        while (current.status != COMPLETED_STATUS) {
            if (System.currentTimeMillis() >= deadline) {
                return@withProvider Result.failure(BuildTimeoutException())
            }
            delay(interval)
            when (val refreshed = provider.run(repoRef, current.id)) {
                is GitResult.Success -> current = refreshed.value
                // Transient failures while polling are not fatal; keep trying until
                // the deadline, otherwise a single hiccup would abandon a real build.
                is GitResult.Failure -> Unit
            }
            onProgress(BuildProgress(BuildPhase.RUNNING, current))
        }

        val succeeded = current.conclusion == SUCCESS_CONCLUSION
        var downloadError: String? = null
        val artifacts = if (succeeded) {
            onProgress(BuildProgress(BuildPhase.DOWNLOADING, current))
            downloadArtifacts(provider, repoRef, owner, repo, current)
                .onFailure { downloadError = it.message }
                .getOrElse { ArtifactDownloadResult(emptyList(), emptyList()) }
        } else {
            ArtifactDownloadResult(emptyList(), emptyList())
        }
        onProgress(BuildProgress(BuildPhase.FINISHED, current))
        Result.success(
            BranchBuildResult(
                run = current,
                succeeded = succeeded,
                artifacts = artifacts,
                downloadError = downloadError
            )
        )
    }

    // ----------------------------------------------------------------- releases

    /** Recent releases, newest first; drafts appear when the token is allowed to see them. */
    suspend fun releases(
        owner: String,
        repo: String,
        perPage: Int = 20
    ): Result<List<GitRelease>> = withProvider(owner, repo) { provider, ref ->
        provider.releases(ref, perPage).toResult()
    }

    /**
     * Imports one release asset into the vault. Release assets are stored as-is (they
     * are not zipped), so this is the only path that turns a published tag build into
     * a file the user can sign, export or install.
     */
    suspend fun downloadReleaseAsset(
        owner: String,
        repo: String,
        release: GitRelease,
        asset: GitReleaseAsset
    ): Result<VaultArtifact> = withProvider(owner, repo) { provider, _ ->
        val bytes = when (val downloaded = provider.downloadReleaseAsset(asset)) {
            is GitResult.Failure -> return@withProvider Result.failure(
                WorkspaceException.Provider(downloaded.kind, downloaded.message)
            )
            is GitResult.Success -> downloaded.value
        }
        vault.importBytes(
            bytes = bytes,
            originalName = asset.name,
            artifactSource = ArtifactSource(
                owner = owner,
                repo = repo,
                branch = null,
                commitSha = null,
                workflowName = release.name ?: release.tagName,
                releaseTag = release.tagName
            )
        )
    }

    /**
     * Downloads every non-expired artifact of [run], unpacks it, and imports each APK
     * and AAB into the vault. An artifact containing neither is reported in [skipped]
     * rather than treated as an error (Actions often attach a log bundle too).
     */
    suspend fun downloadRunArtifacts(
        owner: String,
        repo: String,
        run: GitPipelineRun
    ): Result<ArtifactDownloadResult> = withProvider(owner, repo) { provider, ref ->
        downloadArtifacts(provider, ref, owner, repo, run)
    }

    /** Imports a single artifact without a run context (e.g. re-download from the vault). */
    suspend fun downloadArtifact(
        owner: String,
        repo: String,
        artifact: GitArtifact,
        run: GitPipelineRun? = null
    ): Result<List<VaultArtifact>> = withProvider(owner, repo) { provider, _ ->
        val bytes = when (val downloaded = provider.downloadArtifact(artifact)) {
            is GitResult.Failure -> return@withProvider Result.failure(
                WorkspaceException.Provider(downloaded.kind, downloaded.message)
            )
            is GitResult.Success -> downloaded.value
        }
        val source = ArtifactSource(
            owner = owner,
            repo = repo,
            branch = run?.branch,
            commitSha = run?.headSha,
            workflowName = run?.name,
            runId = run?.id ?: artifact.workflowRunId,
            runNumber = run?.runNumber
        )
        Result.success(unpackArtifacts(bytes, artifact.name, source))
    }

    private suspend fun downloadArtifacts(
        provider: GitProvider,
        ref: GitRepoRef,
        owner: String,
        repo: String,
        run: GitPipelineRun
    ): Result<ArtifactDownloadResult> {
        val artifacts = when (val listed = provider.runArtifacts(ref, run.id)) {
            is GitResult.Failure -> return Result.failure(
                WorkspaceException.Provider(listed.kind, listed.message)
            )
            is GitResult.Success -> listed.value.filterNot { it.expired }
        }
        if (artifacts.isEmpty()) {
            return Result.success(ArtifactDownloadResult(emptyList(), emptyList()))
        }
        val records = mutableListOf<VaultArtifact>()
        val skipped = mutableListOf<String>()
        val source = ArtifactSource(
            owner = owner,
            repo = repo,
            branch = run.branch,
            commitSha = run.headSha,
            workflowName = run.name,
            runId = run.id,
            runNumber = run.runNumber
        )
        for (artifact in artifacts) {
            val bytes = when (val downloaded = provider.downloadArtifact(artifact)) {
                is GitResult.Failure -> return Result.failure(
                    WorkspaceException.Provider(downloaded.kind, downloaded.message)
                )
                is GitResult.Success -> downloaded.value
            }
            val unpacked = unpackArtifacts(bytes, artifact.name, source)
            if (unpacked.isEmpty()) {
                skipped += artifact.name
            } else {
                records += unpacked
            }
        }
        return Result.success(ArtifactDownloadResult(records, skipped))
    }

    private fun unpackArtifacts(
        zipBytes: ByteArray,
        artifactName: String,
        source: ArtifactSource
    ): List<VaultArtifact> {
        val records = mutableListOf<VaultArtifact>()
        try {
            ZipInputStream(zipBytes.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/')
                        val extension = name.substringAfterLast('.', "").lowercase()
                        if (extension == "apk" || extension == "aab") {
                            val bytes = readEntryBounded(zip, name)
                            if (bytes != null) {
                                vault.importBytes(
                                    bytes = bytes,
                                    originalName = name,
                                    artifactSource = source
                                ).onSuccess { records += it }
                                    .onFailure {
                                        AppLogger.w(
                                            TAG,
                                            "Could not import ${name}: ${it.javaClass.simpleName}"
                                        )
                                    }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not unpack $artifactName: ${e.javaClass.simpleName}")
        }
        return records
    }

    /** Returns null when one entry exceeds the per-file cap (never allocates past it). */
    private fun readEntryBounded(zip: ZipInputStream, name: String): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) {
                AppLogger.w(TAG, "$name exceeds the artifact size cap")
                return null
            }
            output.write(buffer, 0, read)
        }
        return if (total == 0L) null else output.toByteArray()
    }

    /**
     * Resolves the active provider and the repository identity once, so every public
     * method reports "no provider configured" or "invalid repository" the same way.
     */
    private suspend fun <T> withProvider(
        owner: String,
        repo: String,
        block: suspend (GitProvider, GitRepoRef) -> Result<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        when (val lookup = GitProviderRegistry.active(context)) {
            is GitProviderLookup.Unavailable -> Result.failure(
                WorkspaceException.UnavailableProvider(lookup.id, lookup.reason)
            )
            is GitProviderLookup.Available -> {
                val ref = GitRepoRef.of(owner, repo)
                    ?: return@withContext Result.failure(
                        WorkspaceException.InvalidRepository("$owner/$repo")
                    )
                block(lookup.provider, ref)
            }
        }
    }

    private fun <T> GitResult<T>.toResult(): Result<T> = when (this) {
        is GitResult.Success -> Result.success(value)
        is GitResult.Failure -> Result.failure(
            WorkspaceException.Provider(kind, message)
        )
    }

    companion object {
        private const val TAG = "BuildStudio"

        /** 400 MB per unpacked file: larger than any real APK/AAB, small enough for a phone. */
        const val MAX_ENTRY_BYTES = 400L * 1024 * 1024

        /** A queued Actions job can wait a long time behind other runners. */
        const val DEFAULT_BUILD_TIMEOUT_MS = 45L * 60 * 1000
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L
        private const val POLL_BACKOFF_MS = 2_000L
        private const val MAX_POLL_INTERVAL_MS = 20_000L
        private const val COMPLETED_STATUS = "completed"
        private const val SUCCESS_CONCLUSION = "success"

        /** Imports an APK/AAB the user picked from storage into the vault. */
        suspend fun importLocalFile(
            context: Context,
            file: File,
            source: ArtifactSource = ArtifactSource()
        ): Result<VaultArtifact> = withContext(Dispatchers.IO) {
            ArtifactVault.getInstance(context).importFile(file, file.name, source)
        }

        /**
         * Picks the run a dispatch just created.
         *
         * The dispatch endpoint returns no run id, so the only reliable identification
         * is "a run that did not exist before the dispatch, belonging to the workflow
         * that was dispatched". Any other rule (newest run, newest by workflow) would
         * latch onto a run started by somebody else in the meantime.
         *
         * Matching uses the workflow id when the host reports one on the run and the
         * workflow path otherwise: Gitea/Forgejo name the workflow file on the run and
         * do not repeat an id, so an id-only rule would never match there.
         */
        fun selectDispatchedRun(
            candidates: List<GitPipelineRun>,
            knownRunIds: Set<Long>,
            workflow: GitPipeline
        ): GitPipelineRun? = candidates.firstOrNull { candidate ->
            candidate.id !in knownRunIds && belongsTo(candidate, workflow)
        }

        private fun belongsTo(run: GitPipelineRun, workflow: GitPipeline): Boolean {
            if (run.workflowId.isNotBlank() && run.workflowId == workflow.id) return true
            val runPath = run.workflowPath?.takeIf { it.isNotBlank() } ?: return false
            val workflowPath = workflow.path.takeIf { it.isNotBlank() } ?: return false
            return runPath == workflowPath
        }
    }
}
