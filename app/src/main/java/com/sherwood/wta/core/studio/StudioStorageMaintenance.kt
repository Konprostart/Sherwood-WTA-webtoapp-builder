package com.sherwood.wta.core.studio

import android.content.Context
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.session.SessionStore
import com.sherwood.wta.core.frontend.RepoBridgeMetadata
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.sidekick.input.SidekickWavRecorder
import com.sherwood.wta.core.studio.git.GitProviderId
import com.sherwood.wta.core.studio.git.RepoImportStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What one maintenance pass removed. Reported so a pass can be logged and asserted in
 * tests; nothing here is user-facing.
 */
data class StorageReport(
    val tempFilesDeleted: Int = 0,
    val importsDeleted: Int = 0,
    val sessionsDeleted: Int = 0,
    val bytesFreed: Long = 0L,
    val failures: Int = 0
) {
    val isEmpty: Boolean
        get() = tempFilesDeleted == 0 && importsDeleted == 0 && sessionsDeleted == 0

    operator fun plus(other: StorageReport) = StorageReport(
        tempFilesDeleted = tempFilesDeleted + other.tempFilesDeleted,
        importsDeleted = importsDeleted + other.importsDeleted,
        sessionsDeleted = sessionsDeleted + other.sessionsDeleted,
        bytesFreed = bytesFreed + other.bytesFreed,
        failures = failures + other.failures
    )
}

/**
 * Housekeeping for Studio's on-device files.
 *
 * Deliberately conservative: only files that are provably disposable are removed.
 *
 *  - Interrupted downloads and unzipped artifacts leave `*.tmp` / `*.part` files behind
 *    when the process dies; they are deleted after [TEMP_MAX_AGE_MS].
 *  - A working copy without readable provenance is an incomplete import and is deleted
 *    after [ORPHAN_MAX_AGE_MS].
 *  - Repeated imports of the same repository are capped at [MAX_IMPORTS_PER_REPO], but a
 *    copy is only deleted when it is *also* older than [IMPORT_RETENTION_MS] and shows no
 *    local edits (no non-empty `.changes` snapshot directory).
 *  - Session directories whose session no longer exists in the store are deleted after
 *    [ORPHAN_SESSION_MAX_AGE_MS].
 *
 * Never touched: the vault (user-managed), commit draft metadata, and any working copy
 * that might hold local edits.
 */
object StudioStoragePruner {

    const val TEMP_MAX_AGE_MS = 24L * 60 * 60 * 1000
    const val ORPHAN_MAX_AGE_MS = 24L * 60 * 60 * 1000
    const val IMPORT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    const val ORPHAN_SESSION_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_IMPORTS_PER_REPO = 3

    /**
     * Deletes stale temp/partial files directly inside [dir] (not recursively).
     *
     * [namePrefix] limits the sweep to one subsystem's scratch files; without it a
     * shared cache directory would let housekeeping delete another component's
     * in-progress temp file.
     */
    fun pruneTempFiles(
        dir: File?,
        now: Long,
        maxAgeMs: Long = TEMP_MAX_AGE_MS,
        suffixes: List<String> = listOf(".tmp", ".part"),
        namePrefix: String? = null
    ): StorageReport {
        if (dir == null || !dir.isDirectory) return StorageReport()
        var count = 0
        var freed = 0L
        var failures = 0
        dir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.lastModified() > now - maxAgeMs) return@forEach
            if (namePrefix != null && !file.name.startsWith(namePrefix)) return@forEach
            if (suffixes.none { file.name.endsWith(it) }) return@forEach
            val size = file.length()
            if (file.delete()) {
                count++
                freed += size
            } else {
                failures++
            }
        }
        return StorageReport(tempFilesDeleted = count, bytesFreed = freed, failures = failures)
    }

    /**
     * Deletes vault `*.part` / `.import-*.tmp` files that no index record references.
     * A file the index still knows about is left alone even if it looks temporary.
     */
    fun pruneVaultPartials(
        vaultDir: File?,
        referencedNames: Set<String>,
        now: Long,
        maxAgeMs: Long = TEMP_MAX_AGE_MS
    ): StorageReport {
        if (vaultDir == null || !vaultDir.isDirectory) return StorageReport()
        var count = 0
        var freed = 0L
        var failures = 0
        vaultDir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.lastModified() > now - maxAgeMs) return@forEach
            val looksTemporary = file.name.endsWith(".part") ||
                (file.name.startsWith(".import-") && file.name.endsWith(".tmp"))
            if (!looksTemporary || file.name in referencedNames) return@forEach
            val size = file.length()
            if (file.delete()) {
                count++
                freed += size
            } else {
                failures++
            }
        }
        return StorageReport(tempFilesDeleted = count, bytesFreed = freed, failures = failures)
    }

    fun keyOf(metadata: RepoBridgeMetadata): String =
        "${metadata.resolvedProvider}|${metadata.owner.lowercase()}/${metadata.repo.lowercase()}"

    /**
     * Deletes incomplete and excess imported working copies.
     *
     * [protectedKeys] holds [keyOf] values for repositories that must never be pruned
     * (the repository the user is currently working on).
     */
    fun pruneImports(
        roots: List<File>,
        metadataStore: RepoBridgeMetadataStore,
        now: Long,
        protectedKeys: Set<String> = emptySet(),
        maxPerRepo: Int = MAX_IMPORTS_PER_REPO,
        retentionMs: Long = IMPORT_RETENTION_MS,
        orphanMaxAgeMs: Long = ORPHAN_MAX_AGE_MS
    ): StorageReport {
        var count = 0
        var freed = 0L
        var failures = 0

        val valid = mutableListOf<Pair<File, RepoBridgeMetadata>>()
        roots.forEach { root ->
            if (!root.isDirectory) return@forEach
            root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
                val metadata = metadataStore.read(dir)
                if (metadata == null) {
                    // No provenance means the import never finished. Very recent
                    // directories are left alone in case the import is still running.
                    if (dir.lastModified() > now - orphanMaxAgeMs) return@forEach
                    val size = directorySize(dir)
                    if (dir.deleteRecursively()) {
                        count++
                        freed += size
                    } else {
                        failures++
                    }
                    return@forEach
                }
                valid += dir to metadata
            }
        }

        valid.groupBy { keyOf(it.second) }.forEach { (key, copies) ->
            // Newest first: the copy a user is most likely to still want.
            val ordered = copies.sortedByDescending { it.second.importedAtEpochMs }
            ordered.forEachIndexed { index, (dir, metadata) ->
                // The newest copy of every repository always survives.
                if (index == 0) return@forEachIndexed
                val withinCap = index < maxPerRepo
                val recent = now - metadata.importedAtEpochMs <= retentionMs
                // The repository the user is working on, or a directory touched in the
                // last day, is never treated as garbage.
                val protectedCopy =
                    key in protectedKeys || dir.lastModified() > now - orphanMaxAgeMs
                if (withinCap || recent || protectedCopy || hasLocalEdits(dir)) {
                    return@forEachIndexed
                }
                val size = directorySize(dir)
                if (dir.deleteRecursively()) {
                    count++
                    freed += size
                } else {
                    failures++
                }
            }
        }

        return StorageReport(importsDeleted = count, bytesFreed = freed, failures = failures)
    }

    /** Deletes session sandboxes that the session store no longer knows about. */
    fun pruneOrphanSessions(
        sessionsRoot: File?,
        knownSessionIds: Set<String>,
        now: Long,
        maxAgeMs: Long = ORPHAN_SESSION_MAX_AGE_MS
    ): StorageReport {
        if (sessionsRoot == null || !sessionsRoot.isDirectory) return StorageReport()
        var count = 0
        var freed = 0L
        var failures = 0
        sessionsRoot.listFiles().orEmpty().forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (dir.name in knownSessionIds) return@forEach
            if (dir.lastModified() > now - maxAgeMs) return@forEach
            val size = directorySize(dir)
            if (dir.deleteRecursively()) {
                count++
                freed += size
            } else {
                failures++
            }
        }
        return StorageReport(sessionsDeleted = count, bytesFreed = freed, failures = failures)
    }

    /**
     * A non-empty `.changes` directory means [com.sherwood.wta.core.agent.files.ProjectFileManager]
     * snapshotted a file before editing it, i.e. the copy carries local work that a
     * remote comparison would be needed to confirm. Such a copy is never pruned.
     */
    fun hasLocalEdits(projectDir: File): Boolean {
        if (File(projectDir, "${RepoBridgeMetadataStore.FILE_NAME}.tmp").isFile) return true
        val changes = File(projectDir, ".changes")
        if (!changes.isDirectory) return false
        return changes.walkTopDown().any { it.isFile }
    }

    fun directorySize(dir: File): Long {
        if (!dir.isDirectory) return if (dir.isFile) dir.length() else 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

/**
 * Android wiring for [StudioStoragePruner].
 *
 * Runs at most once per [MIN_INTERVAL_MS] so a process that is restarted often does not
 * re-walk the filesystem every time. All work happens on [Dispatchers.IO] and a failure
 * is logged, never surfaced: housekeeping must not affect application start.
 */
class StudioStorageMaintenance(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Runs one pass unless a recent one already ran; returns null when skipped. */
    suspend fun runIfDue(now: Long = System.currentTimeMillis()): StorageReport? {
        val last = prefs.getLong(KEY_LAST_RUN, 0L)
        if (last > 0L && now - last < MIN_INTERVAL_MS) return null
        val report = run(now)
        prefs.edit().putLong(KEY_LAST_RUN, now).apply()
        return report
    }

    suspend fun run(now: Long = System.currentTimeMillis()): StorageReport {
        return withContext(Dispatchers.IO) {
            val report = try {
                collectReport(now)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Studio storage maintenance failed: ${e.javaClass.simpleName}")
                StorageReport(failures = 1)
            }
            if (!report.isEmpty || report.failures > 0) {
                AppLogger.i(
                    TAG,
                    "Storage maintenance: temps=${report.tempFilesDeleted}, " +
                        "imports=${report.importsDeleted}, sessions=${report.sessionsDeleted}, " +
                        "freed=${report.bytesFreed}B, failures=${report.failures}"
                )
            }
            report
        }
    }

    private suspend fun collectReport(now: Long): StorageReport {
        val filesDir = context.filesDir
        val metadataStore = RepoBridgeMetadataStore()
        val settings = StudioSettingsStore.getInstance(context)

        val protectedKeys = GitProviderId.entries.mapNotNull { provider ->
            val path = settings.lastRepository(provider)?.takeIf { it.contains('/') }
                ?: return@mapNotNull null
            "${provider.storageId}|${path.lowercase()}"
        }.toSet()

        val knownSessions = runCatching {
            SessionStore(context, ProjectFileManager(context)).sessionsFlow.first().map { it.id }.toSet()
        }.getOrDefault(emptySet())

        val vault = ArtifactVault.getInstance(context)
        val referenced = runCatching { vault.list().map { it.fileName }.toSet() }
            .getOrDefault(emptySet())

        return StudioStoragePruner.pruneTempFiles(
            dir = context.cacheDir,
            now = now,
            // Only this module's scratch files: cacheDir is shared with the whole app.
            namePrefix = "studio-"
        ) +
            StudioStoragePruner.pruneVaultPartials(vault.directory(), referenced, now) +
            StudioStoragePruner.pruneImports(
                roots = RepoImportStorage.roots(filesDir),
                metadataStore = metadataStore,
                now = now,
                protectedKeys = protectedKeys
            ) +
            // Voice takes are deleted by the upload path and camera captures by the
            // decode path; one orphaned by a process death between the two is provably
            // disposable after the same window.
            StudioStoragePruner.pruneTempFiles(
                dir = File(context.cacheDir, SidekickWavRecorder.CACHE_DIR),
                now = now,
                suffixes = listOf(".wav", ".jpg")
            ) +
            StudioStoragePruner.pruneOrphanSessions(
                sessionsRoot = File(filesDir, SESSIONS_DIR),
                knownSessionIds = knownSessions,
                now = now
            )
    }

    companion object {
        private const val TAG = "StudioMaintenance"
        private const val PREFS = "sherwood_studio_maintenance"
        private const val KEY_LAST_RUN = "last_run_ms"
        private const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val SESSIONS_DIR = "aicoding/sessions"

        fun getInstance(context: Context) = StudioStorageMaintenance(context)
    }
}
