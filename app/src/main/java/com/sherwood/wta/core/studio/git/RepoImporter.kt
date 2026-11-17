package com.sherwood.wta.core.studio.git

import android.content.Context
import com.sherwood.wta.core.frontend.GitHubFetchResult
import com.sherwood.wta.core.frontend.RepoBridgeMetadata
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imports a branch as a working copy, through whichever provider is active.
 *
 * The four steps are the same for every host: resolve the branch, resolve the commit it
 * points at, download the source archive, extract it and record the provenance. Only the
 * transport differs, which is why it lives on [GitProvider] and not here.
 *
 * [GitHubFetchResult] is still the return type because the Studio, the agent tools and the
 * import screens already pass it around; its fields are provider-neutral (owner, repo,
 * branch, commit, local path). Renaming it is a separate, mechanical change.
 */
class RepoImporter(
    private val context: Context,
    private val metadataStore: RepoBridgeMetadataStore = RepoBridgeMetadataStore(),
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun import(
        owner: String,
        repo: String,
        branch: String? = null,
        subPath: String? = null
    ): Result<GitHubFetchResult> {
        val provider = when (val lookup = GitProviderRegistry.active(context)) {
            is GitProviderLookup.Unavailable -> return Result.failure(
                IllegalStateException(
                    "The active git provider is not available (${lookup.id}: ${lookup.reason})"
                )
            )
            is GitProviderLookup.Available -> lookup.provider
        }
        return import(provider, owner, repo, branch, subPath)
    }

    /**
     * Imports through [provider] explicitly.
     *
     * A checkout of an existing working copy must use the provider recorded in its
     * provenance, not whatever the active setting happens to be, so that an import from
     * one server is never refreshed from another.
     */
    suspend fun import(
        provider: GitProvider,
        owner: String,
        repo: String,
        branch: String? = null,
        subPath: String? = null
    ): Result<GitHubFetchResult> = withContext(io) {
        val ref = GitRepoRef.of(owner, repo)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Not a repository: $owner/$repo")
            )

        val resolved = resolveBranch(provider, ref, branch)
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "Failed to download repository (check the branch name)"
                )
            )

        // A failed lookup here used to become `baseSha = null`, which only surfaces much
        // later as an unexplained `NO_BASE` on every status and publish of the copy.
        // Fail the import now instead: a working copy that can never be published is
        // worse than an import that says why it stopped.
        val baseSha = when (val branchInfo = provider.branch(ref, resolved)) {
            is GitResult.Success -> branchInfo.value.sha
            is GitResult.Failure -> return@withContext Result.failure(
                IllegalStateException(
                    branchInfo.message ?: "Could not resolve the branch commit"
                )
            )
        }
        val host = RepoArchive.hostOf(provider.baseUrl)
            ?: return@withContext Result.failure(
                IllegalStateException("The provider base URL has no host")
            )

        // A unique, `studio-` prefixed name: two imports in the same millisecond must
        // not share one file, and the storage pruner only deletes this module's scratch
        // files by that prefix.
        val temp = File.createTempFile("studio-import-", ".zip", context.cacheDir)
        try {
            when (val downloaded = provider.downloadArchive(ref, resolved, temp)) {
                is GitResult.Failure -> return@withContext Result.failure(
                    IllegalStateException(
                        downloaded.message ?: "Could not download the repository archive"
                    )
                )
                is GitResult.Success -> Unit
            }

            val targetDir = File(
                context.filesDir,
                "${RepoImportStorage.CURRENT}/${sanitize(repo)}_${System.currentTimeMillis()}"
            )
            val extracted = try {
                RepoArchive.extract(temp, targetDir, subPath)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Extract failed: ${e.javaClass.simpleName}")
                return@withContext Result.failure(
                    IllegalStateException(e.message ?: "Failed to extract archive")
                )
            }

            val metadata = RepoBridgeMetadata(
                provider = provider.id.storageId,
                host = host,
                owner = ref.owner,
                repo = ref.name,
                ref = resolved,
                baseSha = baseSha,
                subPath = subPath
            )
            val metadataFile = runCatching { metadataStore.write(extracted, metadata) }
                .onFailure { AppLogger.w(TAG, "Metadata write failed: ${it.javaClass.simpleName}") }
                .getOrNull()

            Result.success(
                GitHubFetchResult(
                    localPath = extracted.absolutePath,
                    owner = ref.owner,
                    repo = ref.name,
                    branch = resolved,
                    subPath = subPath,
                    baseSha = baseSha,
                    metadataFile = metadataFile?.absolutePath
                )
            )
        } finally {
            temp.delete()
        }
    }

    /**
     * The requested branch, the repository default, or the two historic defaults.
     *
     * The fallback order mirrors the GitHub importer that preceded this class, so a
     * repository whose API default cannot be read still imports from `main` or `master`.
     */
    private suspend fun resolveBranch(
        provider: GitProvider,
        ref: GitRepoRef,
        requested: String?
    ): String? {
        val explicit = requested?.takeIf { it.isNotBlank() }?.trim()
        if (explicit != null) return explicit
        val default = (provider.repository(ref) as? GitResult.Success)?.value?.defaultBranch
        if (default != null && (provider.branch(ref, default) is GitResult.Success)) {
            return default
        }
        for (candidate in FALLBACK_BRANCHES) {
            if (provider.branch(ref, candidate) is GitResult.Success) return candidate
        }
        return default
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    companion object {
        private const val TAG = "RepoImporter"
        private val FALLBACK_BRANCHES = listOf("main", "master")
    }
}
