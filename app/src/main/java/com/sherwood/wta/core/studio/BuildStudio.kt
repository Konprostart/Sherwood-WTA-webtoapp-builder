package com.sherwood.wta.core.studio

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

data class BuildRunDetails(
    val run: GitHubWorkflowRun,
    val jobs: List<GitHubJob>
)

data class ArtifactDownloadResult(
    val records: List<VaultArtifact>,
    val skipped: List<String>
)

/**
 * GitHub Actions Build Studio.
 *
 * Everything is read from GitHub's REST API: workflow list, manual dispatch, runs,
 * jobs, logs and artifacts. Downloaded artifact zips are unpacked in memory (with a
 * hard size cap), and every `.apk`/`.aab` inside is stored in the local
 * [ArtifactVault] together with its SHA-256, source repository, branch, commit and
 * run id — so the file on the phone can always be traced to the exact build.
 *
 * Artifacts are built by the repository's own workflows. No author-owned build
 * server is involved; the app never calls any infrastructure other than GitHub.
 */
class BuildStudio(private val context: Context) {

    private val api = GitHubApiClient(context)
    private val vault = ArtifactVault.getInstance(context)

    suspend fun workflows(owner: String, repo: String): Result<List<GitHubWorkflow>> =
        withContext(Dispatchers.IO) {
            when (val result = api.listWorkflows(owner, repo)) {
                is GitHubResult.Success -> Result.success(result.value)
                is GitHubResult.Failure -> Result.failure(
                    WorkspaceException.GitHub(result.kind, result.message)
                )
            }
        }

    suspend fun runs(
        owner: String,
        repo: String,
        branch: String? = null,
        perPage: Int = 20
    ): Result<List<GitHubWorkflowRun>> = withContext(Dispatchers.IO) {
        when (val result = api.listWorkflowRuns(owner, repo, perPage, branch)) {
            is GitHubResult.Success -> Result.success(result.value)
            is GitHubResult.Failure -> Result.failure(
                WorkspaceException.GitHub(result.kind, result.message)
            )
        }
    }

    suspend fun runDetails(owner: String, repo: String, runId: Long): Result<BuildRunDetails> =
        withContext(Dispatchers.IO) {
            when (val run = api.getWorkflowRun(owner, repo, runId)) {
                is GitHubResult.Failure -> Result.failure(
                    WorkspaceException.GitHub(run.kind, run.message)
                )
                is GitHubResult.Success -> when (val jobs = api.listJobs(owner, repo, runId)) {
                    is GitHubResult.Failure -> Result.failure(
                        WorkspaceException.GitHub(jobs.kind, jobs.message)
                    )
                    is GitHubResult.Success -> Result.success(
                        BuildRunDetails(run.value, jobs.value)
                    )
                }
            }
        }

    suspend fun logs(owner: String, repo: String, runId: Long): Result<String> =
        withContext(Dispatchers.IO) {
            when (val result = api.downloadRunLogs(owner, repo, runId)) {
                is GitHubResult.Success -> Result.success(result.value)
                is GitHubResult.Failure -> Result.failure(
                    WorkspaceException.GitHub(result.kind, result.message)
                )
            }
        }

    suspend fun artifacts(owner: String, repo: String, runId: Long): Result<List<GitHubArtifact>> =
        withContext(Dispatchers.IO) {
            when (val result = api.listRunArtifacts(owner, repo, runId)) {
                is GitHubResult.Success -> Result.success(result.value)
                is GitHubResult.Failure -> Result.failure(
                    WorkspaceException.GitHub(result.kind, result.message)
                )
            }
        }

    suspend fun dispatch(
        owner: String,
        repo: String,
        workflowIdOrFile: String,
        ref: String,
        inputs: Map<String, String> = emptyMap()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        when (val result = api.dispatchWorkflow(owner, repo, workflowIdOrFile, ref, inputs)) {
            is GitHubResult.Success -> Result.success(Unit)
            is GitHubResult.Failure -> Result.failure(
                WorkspaceException.GitHub(result.kind, result.message)
            )
        }
    }

    /**
     * Downloads every non-expired artifact of [run], unpacks it, and imports each APK
     * and AAB into the vault. An artifact containing neither is reported in [skipped]
     * rather than treated as an error (Actions often attach a log bundle too).
     */
    suspend fun downloadRunArtifacts(
        owner: String,
        repo: String,
        run: GitHubWorkflowRun
    ): Result<ArtifactDownloadResult> = withContext(Dispatchers.IO) {
        val artifacts = when (val listed = api.listRunArtifacts(owner, repo, run.id)) {
            is GitHubResult.Failure -> return@withContext Result.failure(
                WorkspaceException.GitHub(listed.kind, listed.message)
            )
            is GitHubResult.Success -> listed.value.filterNot { it.expired }
        }
        if (artifacts.isEmpty()) {
            return@withContext Result.success(ArtifactDownloadResult(emptyList(), emptyList()))
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
            val bytes = when (val downloaded = api.downloadArtifact(artifact)) {
                is GitHubResult.Failure -> return@withContext Result.failure(
                    WorkspaceException.GitHub(downloaded.kind, downloaded.message)
                )
                is GitHubResult.Success -> downloaded.value
            }
            val unpacked = unpackArtifacts(bytes, artifact.name, source)
            if (unpacked.isEmpty()) {
                skipped += artifact.name
            } else {
                records += unpacked
            }
        }
        Result.success(ArtifactDownloadResult(records, skipped))
    }

    /** Imports a single artifact without a run context (e.g. re-download from the vault). */
    suspend fun downloadArtifact(
        owner: String,
        repo: String,
        artifact: GitHubArtifact,
        run: GitHubWorkflowRun? = null
    ): Result<List<VaultArtifact>> = withContext(Dispatchers.IO) {
        val bytes = when (val downloaded = api.downloadArtifact(artifact)) {
            is GitHubResult.Failure -> return@withContext Result.failure(
                WorkspaceException.GitHub(downloaded.kind, downloaded.message)
            )
            is GitHubResult.Success -> downloaded.value
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

    companion object {
        private const val TAG = "BuildStudio"

        /** 400 MB per unpacked file: larger than any real APK/AAB, small enough for a phone. */
        const val MAX_ENTRY_BYTES = 400L * 1024 * 1024

        /** Imports an APK/AAB the user picked from storage into the vault. */
        suspend fun importLocalFile(
            context: Context,
            file: File,
            source: ArtifactSource = ArtifactSource()
        ): Result<VaultArtifact> = withContext(Dispatchers.IO) {
            ArtifactVault.getInstance(context).importFile(file, file.name, source)
        }
    }
}
