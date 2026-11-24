package com.sherwood.wta.core.backup

import android.content.Context
import android.net.Uri
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sherwood.wta.data.converter.Converters
import com.sherwood.wta.data.database.AppDatabase
import com.sherwood.wta.data.model.AppCategory
import com.sherwood.wta.data.model.WebApp
import com.sherwood.wta.data.repository.WebAppRepository
import com.sherwood.wta.core.stats.AppUsageStats
import com.sherwood.wta.util.threadLocalCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Time-based gate for high-frequency progress callbacks. A thousand-file backup
 * must not recompose the progress UI a thousand times; milestones (0/50/60/100)
 * bypass the gate and always emit. Pure logic, unit-tested on plain JVM.
 */
internal class ProgressThrottle(
    private val minIntervalMs: Long = 150,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private var lastEmitMs: Long = -minIntervalMs

    fun shouldEmit(force: Boolean = false): Boolean {
        if (force) {
            lastEmitMs = clockMs()
            return true
        }
        val now = clockMs()
        if (now - lastEmitMs < minIntervalMs) return false
        lastEmitMs = now
        return true
    }
}

class DataBackupManager(private val context: Context) {

    companion object {
        private const val TAG = "DataBackupManager"

        /**
         * v6: every archive carries `manifest.json` (size + sha256 per entry), and a
         * restore writes nothing live until the whole archive has been verified.
         * v1-v5 archives stay restorable — see [BackupIntegrity.verify].
         */
        private const val BACKUP_VERSION = 6
        private const val APPS_JSON = "apps.json"
        private const val MANIFEST_JSON = "manifest.json"
        private const val RESOURCES_DIR = "resources/"
        private const val ICONS_DIR = "resources/icons/"
        private const val SPLASH_DIR = "resources/splash/"
        private const val BGM_DIR = "resources/bgm/"
        private const val BGM_LRC_DIR = "resources/bgm_lrc/"
        private const val BGM_COVER_DIR = "resources/bgm_cover/"
        private const val HTML_DIR = "resources/html/"
        private const val MEDIA_DIR = "resources/media/"
        private const val STATUSBAR_DIR = "resources/statusbar/"
        private const val GALLERY_DIR = "resources/gallery/"
        private const val CERTS_DIR = "resources/certs/"
        private const val MULTI_WEB_DIR = "resources/multi_web/"
        private const val EXTENSION_DIR = "extensions/"
        private const val EXTENSION_MODULES_FILE = "${EXTENSION_DIR}modules.json"
        private const val EXTENSION_BUILTIN_STATES_FILE = "${EXTENSION_DIR}builtin_states.json"
        private const val LOCAL_FILES_DIR = "local/files/"
        private const val LOCAL_EXTERNAL_FILES_DIR = "local/external_files/"
        private const val DATASTORE_DIR = "local/datastore/"

        /**
         * API keys in `ai_config` are encrypted with the exporting install's
         * AndroidKeyStore key, which does not exist on the importing device. Copying
         * that ciphertext over the local file would leave AI settings permanently
         * unable to decrypt — and therefore unable to accept a new key.
         */
        internal const val AI_CONFIG_DATASTORE_ENTRY = "${DATASTORE_DIR}ai_config.preferences_pb"
        private const val SHARED_PREFS_DIR = "local/shared_prefs/"
        private const val KEYSTORE_DIR = "local/keystores/"
        private const val ADBLOCK_DIR = "local/adblock/"

        private val MANAGED_FILES_DIRS = listOf(

            "extension_modules",
            "extensions",
            "plugins",
            "plugin_config",
            "html_projects",
            "splash_media",
            "website_icons",
            "custom_ca",
            "aicoding",

            "nodejs_projects",
            "php_projects",
            "python_projects",
            "go_projects",
            "wordpress_projects",

            // Added as the app grew: frontend build sandboxes, offline-pack
            // docs output, website-scraper output. All user content with no
            // other backup path (regenerable caches stay excluded below).
            "frontend_builds",
            "docs_projects",
            "scraped_sites"
        )

        private val MANAGED_FILES_EXCLUDED_SEGMENTS = setOf(
            ".git",
            ".cache",
            ".changes",
            "__pycache__",
            ".pytest_cache",
            ".mypy_cache",
            "tmp"
        )

        private val MANAGED_EXTERNAL_DIRS = emptyList<String>()

        private val SAFE_DATASTORE_NAMES = listOf(
            "language_settings",
            "theme_settings",
            "announcement",
            "activation",
            "ai_config",
            "aicoding_sessions_v1",
            "aicoding_prefs"
        )

        private val SAFE_SHARED_PREF_NAMES = listOf(
            "installed_store_items",
            "config_presets",
            "permission_presets",
            "floating_window_prefs",
            "isolation_prefs",
            "chrome_extension_storage",
            "chrome_extension_content_scripts",
            "gallery_positions"
        )

        /**
         * Signing identity lives as loose files in filesDir root (JarSigner):
         * losing them means published apps can never be updated again.
         * Restored verbatim by exact file name (no traversal possible).
         *
         * NOTE: the password sidecars (custom_keystore_password.txt,
         * custom_keystore_keypass.txt, .ks_credential) are deliberately NOT backed up:
         * a user-shared backup zip would carry the private key AND its plaintext
         * password together. Restore keeps the keystores; passwords are re-entered
         * the next time a build signs with them.
         *
         * webtoapp_keystore.p12 (PKCS12_AUTO) is also NOT backed up: backups are a
         * plain zip, and the auto keystore cannot be restored without its
         * unexportable-adjacent .ks_credential sidecar anyway, so restoring it would
         * only leak a live private key into a plaintext archive.
         */
        private val KEYSTORE_FILES = listOf(
            "custom_keystore.p12",
            "custom_keystore_alias.txt",
            "signing_scheme_options.json"
        )

        /** Live adblock lists (custom rules + subscription registry). */
        private val ADBLOCK_FILES = listOf(
            "adblock_hosts.txt",
            "adblock_hosts_sources.txt"
        )

        // NOTE: "_rt_prot" (DEX CRC anti-tamper evidence) is deliberately NEVER
        // backed up: restoring it onto a different install would trip tamper
        // detection against itself. It regenerates on first launch.

        private val SAFE_SHARED_PREF_PREFIXES = listOf(
            "gm_storage_"
        )

        private const val BUFFER_SIZE = 8192

        /** Hard cap for apps.json / extension JSON entries read fully into memory. */
        private const val MAX_JSON_ENTRY_BYTES = 64L * 1024 * 1024

        private val backupDateFormat = threadLocalCompat {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        }

        private val gson: Gson by lazy {
            Converters.gson
        }
    }

    data class BackupData(
        val version: Int = BACKUP_VERSION,
        val exportTime: Long = System.currentTimeMillis(),
        val appCount: Int = 0,
        val apps: List<WebApp> = emptyList(),
        val categories: List<AppCategory> = emptyList(),

        val usageStats: List<AppUsageStats> = emptyList(),

        /** Added in backup v5; absent (empty) in older backups. */
        val healthRecords: List<com.sherwood.wta.core.stats.AppHealthRecord> = emptyList()
    )

    suspend fun exportAllData(
        repository: WebAppRepository,
        outputUri: Uri,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        try {
            onProgress(0, 100, Strings.backupReadingData)
            coroutineContext.ensureActive()

            val apps = repository.allWebApps.first()
            // Zero apps is valid: settings, extensions, keystores and prefs
            // still deserve a backup.
            AppLogger.i(TAG, "准备导出 ${apps.size} 个应用")

            val categories = AppDatabase.getInstance(context).appCategoryDao().getAllCategories().first()
            val usageStats = runCatching {
                AppDatabase.getInstance(context).appUsageStatsDao().getAllStats().first()
            }.getOrElse {
                AppLogger.w(TAG, "读取使用统计失败，本次备份不含统计数据", it)
                emptyList()
            }
            val healthRecords = runCatching {
                AppDatabase.getInstance(context).appUsageStatsDao().getAllHealthRecords()
            }.getOrElse {
                AppLogger.w(TAG, "读取健康记录失败，本次备份不含健康数据", it)
                emptyList<com.sherwood.wta.core.stats.AppHealthRecord>()
            }

            val resourceFiles = mutableMapOf<String, String>()
            val collectThrottle = ProgressThrottle()

            apps.forEachIndexed { index, app ->
                coroutineContext.ensureActive()
                if (collectThrottle.shouldEmit()) {
                    onProgress(10 + (index * 30 / apps.size), 100, Strings.backupCollectingResources.format(app.name))
                }
                collectAppResources(app, resourceFiles)
            }

            AppLogger.i(TAG, "收集到 ${resourceFiles.size} 个资源文件")

            val appsWithRelativePaths = apps.map { app ->
                updateAppPathsToRelative(app, resourceFiles)
            }

            val backupData = BackupData(
                version = BACKUP_VERSION,
                exportTime = System.currentTimeMillis(),
                appCount = apps.size,
                apps = appsWithRelativePaths,
                categories = categories,
                usageStats = usageStats,
                healthRecords = healthRecords
            )
            val localFiles = collectLocalBackupFiles()

            onProgress(50, 100, Strings.backupCreatingFile)
            coroutineContext.ensureActive()

            // Entry fingerprints collected while writing. They become manifest.json,
            // which is why the manifest is the archive's LAST entry: the digests are
            // only all known once every other entry has been written.
            var writtenEntries = 0

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream, BUFFER_SIZE)).use { zipOut ->

                    val manifestEntries = mutableListOf<BackupEntryFingerprint>()
                    val totalFileCount = resourceFiles.size + localFiles.size
                    val writtenZipPaths = HashSet<String>(totalFileCount * 2)
                    var processedFiles = 0
                    val packThrottle = ProgressThrottle()

                    /**
                     * Streams one entry and records its (size, sha256) in the same
                     * pass. A failed mid-entry write is propagated instead of logged:
                     * silently omitting a user's file produced an archive that
                     * *looked* complete, and the manifest would then disagree with
                     * the bytes it was written from.
                     */
                    fun writeEntry(zipPath: String, body: (OutputStream) -> Unit) {
                        if (!writtenZipPaths.add(zipPath)) return
                        zipOut.putNextEntry(ZipEntry(zipPath))
                        val sink = Sha256Sink(zipOut)
                        try {
                            body(sink)
                            sink.flush()
                            manifestEntries.add(BackupEntryFingerprint(zipPath, sink.size, sink.sha256()))
                        } catch (t: Throwable) {
                            // Leave the entry closed so the failure is not followed by a
                            // second, unrelated one from the stream itself.
                            runCatching { zipOut.closeEntry() }
                            throw t
                        }
                        zipOut.closeEntry()
                    }

                    fun writeFileEntry(zipPath: String, file: File) {
                        // Vanished or unreadable since collection: skip it entirely
                        // (no entry, no manifest line) so the archive stays coherent.
                        if (!file.exists() || !file.canRead()) return
                        writeEntry(zipPath) { sink ->
                            file.inputStream().buffered(BUFFER_SIZE).use { input ->
                                input.copyTo(sink, BUFFER_SIZE)
                            }
                        }
                    }

                    fun reportPackaging() {
                        processedFiles++
                        if (!packThrottle.shouldEmit()) return
                        val pct = if (totalFileCount > 0) {
                            50 + (processedFiles * 45 / totalFileCount)
                        } else 95
                        onProgress(pct, 100, Strings.backupPackaging)
                    }

                    val jsonBytes = gson.toJson(backupData).toByteArray(Charsets.UTF_8)
                    writeEntry(APPS_JSON) { it.write(jsonBytes) }

                    resourceFiles.forEach { (zipPath, localPath) ->
                        coroutineContext.ensureActive()
                        reportPackaging()
                        writeFileEntry(zipPath, File(localPath))
                    }

                    localFiles.forEach { (zipPath, file) ->
                        coroutineContext.ensureActive()
                        reportPackaging()
                        writeFileEntry(zipPath, file)
                    }

                    val manifest = BackupManifest(
                        backupVersion = BACKUP_VERSION,
                        createdAt = System.currentTimeMillis(),
                        entries = manifestEntries
                    )
                    zipOut.putNextEntry(ZipEntry(MANIFEST_JSON))
                    zipOut.write(manifest.toJson().toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    writtenEntries = manifestEntries.size
                }
            } ?: return@withContext Result.failure(Exception("无法创建输出文件"))

            AppLogger.i(TAG, "写入 $writtenEntries 个条目（含校验清单）")
            onProgress(100, 100, Strings.backupExportComplete)

            Result.success(ExportResult(
                appCount = apps.size,
                // Everything except apps.json; the manifest itself is not an asset.
                resourceCount = (writtenEntries - 1).coerceAtLeast(0)
            ))

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Export failed", e)
            Result.failure(e)
        }
    }

    /**
     * Restores an archive as a transaction.
     *
     * ```
     *  1. read     stage every entry into temp files (no live write)
     *  2. verify   manifest + sha256 of every staged entry
     *  3. database apps/categories/stats, tracking what was created for compensation
     *  4. local    snapshot -> journal -> publish settings, prefs, keystores, extensions
     * ```
     *
     * Steps 1–2 cannot damage anything. Step 3 is undone by deleting the rows it
     * created. Step 4 is undone by replaying its journal. A failure anywhere before
     * the end leaves the install exactly as it was — previously local files were
     * overwritten before the database import had even started, so any later failure
     * left them replaced with no way back.
     */
    suspend fun importAllData(
        repository: WebAppRepository,
        inputUri: Uri,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        val extractedFiles = mutableListOf<File>()
        val pendingLocalEntries = mutableListOf<PendingLocalEntry>()
        val observedEntries = mutableListOf<BackupEntryFingerprint>()
        val createdAppIds = mutableListOf<Long>()
        val createdCategoryIds = mutableListOf<Long>()
        val transaction = LocalFileTransaction(context)
        var pluginStoreReleased = false

        try {
            onProgress(0, 100, Strings.backupReadingBackup)
            coroutineContext.ensureActive()

            // A restore killed mid-commit left its journal behind. Undo it before this
            // archive stages anything, so we never commit on top of a half-applied one.
            val recoveredInterruptedRestore = transaction.recoverInterrupted()
            if (recoveredInterruptedRestore) {
                AppLogger.w(TAG, "上次恢复被中断，已回滚到先前状态")
            }

            // ---- Phase 1: read + stage. Nothing live is written. ----
            var backupData: BackupData? = null
            var manifest: BackupManifest? = null
            val extractedResources = mutableMapOf<String, String>()

            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream, BUFFER_SIZE)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    var totalEntries = 0
                    // User-picked backup archive: cap entries + extracted size so a
                    // crafted "backup" cannot fill app storage.
                    val entryGuard = com.sherwood.wta.util.SafeZip.EntryGuard()
                    val extractThrottle = ProgressThrottle()

                    while (entry != null) {
                        coroutineContext.ensureActive()
                        totalEntries++
                        entryGuard.onEntry()
                        if (extractThrottle.shouldEmit()) {
                            onProgress(10 + (totalEntries % 40), 100, Strings.backupExtracting.format(entry.name))
                        }

                        when {
                            entry.name == APPS_JSON -> {
                                val jsonBytes = readJsonEntry(zipIn, entryGuard)
                                observedEntries.add(fingerprintOf(APPS_JSON, jsonBytes))
                                backupData = parseBackupData(String(jsonBytes, Charsets.UTF_8))
                                AppLogger.i(TAG, "读取到 ${backupData?.appCount} 个应用")
                            }
                            entry.name == MANIFEST_JSON -> {
                                val jsonBytes = readJsonEntry(zipIn, entryGuard)
                                manifest = BackupManifest.fromJson(String(jsonBytes, Charsets.UTF_8))
                            }
                            entry.name == EXTENSION_MODULES_FILE ||
                                entry.name == EXTENSION_BUILTIN_STATES_FILE -> {
                                val jsonBytes = readJsonEntry(zipIn, entryGuard)
                                observedEntries.add(fingerprintOf(entry.name, jsonBytes))
                                stageExtensionEntry(entry.name, jsonBytes, pendingLocalEntries)
                            }
                            entry.name.startsWith(RESOURCES_DIR) && !entry.isDirectory -> {
                                val staged = extractResourceFile(entry.name, zipIn, entryGuard)
                                if (staged != null) {
                                    extractedResources[entry.name] = staged.file.absolutePath
                                    extractedFiles.add(staged.file)
                                    observedEntries.add(staged.fingerprint)
                                }
                            }
                            isLocalBackupEntry(entry.name) && !entry.isDirectory -> {
                                val staged = stageLocalBackupEntry(entry.name, zipIn, entryGuard)
                                if (staged != null) {
                                    if (keepLocalAiConfig(staged)) {
                                        staged.tempFile.delete()
                                    } else {
                                        pendingLocalEntries.add(staged)
                                    }
                                    // The entry was still read and hashed, so the
                                    // archive's manifest must keep matching it.
                                    observedEntries.add(staged.fingerprint)
                                }
                            }
                        }

                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(
                BackupImportException(false, IOException("无法读取备份文件"))
            )

            val data = backupData
            if (data == null) {
                return@withContext Result.failure(
                    BackupImportException(false, IOException("备份文件格式无效"))
                )
            }

            // ---- Phase 2: prove the archive before writing a single live byte. ----
            onProgress(55, 100, BackupText.verifyingArchive)
            coroutineContext.ensureActive()
            BackupIntegrity.verify(
                manifest = manifest,
                observed = observedEntries,
                manifestRequired = data.version >= BACKUP_VERSION
            )
            AppLogger.i(TAG, "完整性校验通过：${observedEntries.size} 个条目")

            // ---- Phase 3: database. Compensation = delete the rows we created. ----
            onProgress(60, 100, Strings.backupImportingData)
            coroutineContext.ensureActive()
            val imported = restoreDatabase(
                repository = repository,
                data = data,
                extractedResources = extractedResources,
                createdAppIds = createdAppIds,
                createdCategoryIds = createdCategoryIds,
                onProgress = onProgress
            )

            // ---- Phase 4: local files. Snapshot -> journal -> publish. ----
            var localsRestored = false
            if (pendingLocalEntries.isNotEmpty()) {
                onProgress(95, 100, BackupText.restoringSettings)
                coroutineContext.ensureActive()

                // Release before the files are replaced: PluginStore cancels its own
                // startup load, and that load could otherwise rewrite modules.json on
                // top of the bytes just restored.
                com.sherwood.wta.core.plugin.PluginStore.release()
                pluginStoreReleased = true
                transaction.commit(pendingLocalEntries)
                localsRestored = transaction.anyCommitted
                com.sherwood.wta.core.plugin.PluginStore.getInstance(context)
                pluginStoreReleased = false
            }

            transaction.cleanup()
            cleanupExtractedFiles(extractedFiles)

            onProgress(100, 100, Strings.backupImportComplete)

            Result.success(ImportResult(
                totalCount = data.appCount,
                importedCount = imported.importedCount,
                skippedCount = imported.skippedCount,
                duplicateCount = imported.duplicateCount,
                localFilesRestored = localsRestored,
                interruptedRestoreRecovered = recoveredInterruptedRestore
            ))

        } catch (e: CancellationException) {
            // Teardown of a cancelled import must itself be uncancellable: a cancelled
            // rollback would leave exactly the half-restored state this prevents.
            withContext(NonCancellable) {
                rollbackRestore(
                    repository, transaction, createdAppIds, createdCategoryIds, pluginStoreReleased
                )
                cleanupExtractedFiles(extractedFiles)
            }
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "导入失败，正在回滚", e)
            val complete = withContext(NonCancellable) {
                val rolledBack = rollbackRestore(
                    repository, transaction, createdAppIds, createdCategoryIds, pluginStoreReleased
                )
                cleanupExtractedFiles(extractedFiles)
                rolledBack
            }
            Result.failure(BackupImportException(rolledBack = complete, cause = e))
        }
    }

    /** Result of the database phase, plus the progress counters the UI reports. */
    private class DatabaseRestore(
        val importedCount: Int,
        val skippedCount: Int,
        val duplicateCount: Int
    )

    /**
     * Writes the database half of a restore, recording every row it creates so a
     * failure later in the transaction can delete exactly those rows — never the
     * pre-existing ones a dedup skipped or a category name reused.
     */
    private suspend fun restoreDatabase(
        repository: WebAppRepository,
        data: BackupData,
        extractedResources: Map<String, String>,
        createdAppIds: MutableList<Long>,
        createdCategoryIds: MutableList<Long>,
        onProgress: (Int, Int, String) -> Unit
    ): DatabaseRestore {
        var importedCount = 0
        var skippedCount = 0
        var duplicateCount = 0

        val categoryIdMap = restoreCategories(data.categories, createdCategoryIds)

        // Dedup against what's already installed so re-importing the same backup
        // (or a backup overlapping it) doesn't double the library.
        val existingAppKeys = repository.allWebApps.first()
            .mapTo(HashSet()) { backupDedupKey(it) }

        val appIdMap = mutableMapOf<Long, Long>()
        val importThrottle = ProgressThrottle()

        data.apps.forEachIndexed { index, app ->
            coroutineContext.ensureActive()
            if (importThrottle.shouldEmit()) {
                onProgress(
                    60 + (index * 35 / data.apps.size),
                    100,
                    Strings.backupImportingApp.format(app.name)
                )
            }

            try {
                val appWithLocalPaths = updateAppPathsToLocal(app, extractedResources)

                if (!existingAppKeys.add(backupDedupKey(appWithLocalPaths))) {
                    AppLogger.i(TAG, "跳过重复应用: ${app.name}")
                    duplicateCount++
                    return@forEachIndexed
                }

                val newApp = appWithLocalPaths.copy(
                    id = 0,
                    categoryId = remapCategoryId(appWithLocalPaths.categoryId, categoryIdMap),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                val newId = repository.createWebApp(newApp)
                if (newId > 0L) createdAppIds.add(newId)
                if (app.id != 0L && newId > 0L) {
                    appIdMap[app.id] = newId
                }
                importedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "导入应用失败: ${app.name}", e)
                skippedCount++
            }
        }

        restoreUsageStats(data.usageStats, appIdMap)
        restoreHealthRecords(data.healthRecords, appIdMap)

        return DatabaseRestore(importedCount, skippedCount, duplicateCount)
    }

    /**
     * Undoes a partly applied restore: local files first (from their snapshots), then
     * the database rows this import created. Runs under [NonCancellable].
     *
     * @return true when every step completed, so the UI may state that the previous
     *         data was put back rather than claiming a rollback that partly failed.
     */
    private suspend fun rollbackRestore(
        repository: WebAppRepository,
        transaction: LocalFileTransaction,
        createdAppIds: List<Long>,
        createdCategoryIds: List<Long>,
        pluginStoreReleased: Boolean
    ): Boolean {
        var complete = true

        if (transaction.hasWork) {
            val failures = transaction.rollback()
            if (failures > 0) complete = false
            AppLogger.w(TAG, "已回滚本地文件恢复（失败 $failures 项）")
        }

        if (pluginStoreReleased) {
            // The originals are back on disk; reload the plugin state from them.
            runCatching {
                com.sherwood.wta.core.plugin.PluginStore.release()
                com.sherwood.wta.core.plugin.PluginStore.getInstance(context)
            }.onFailure { e ->
                complete = false
                AppLogger.w(TAG, "回滚后重新加载插件失败", e)
            }
        }

        createdAppIds.asReversed().forEach { appId ->
            runCatching {
                // Usage stats and health records cascade with the app row; the
                // explicit delete keeps stats from outliving it on any schema path.
                AppDatabase.getInstance(context).appUsageStatsDao().deleteByAppId(appId)
                repository.deleteWebAppById(appId)
            }.onFailure { e ->
                complete = false
                AppLogger.w(TAG, "回滚已导入应用失败: $appId", e)
            }
        }

        createdCategoryIds.asReversed().forEach { categoryId ->
            runCatching {
                AppDatabase.getInstance(context).appCategoryDao().deleteById(categoryId)
            }.onFailure { e ->
                complete = false
                AppLogger.w(TAG, "回滚已导入分类失败: $categoryId", e)
            }
        }

        return complete
    }

    private fun fingerprintOf(zipPath: String, bytes: ByteArray): BackupEntryFingerprint =
        BackupEntryFingerprint(zipPath, bytes.size.toLong(), BackupIntegrity.hex(bytes))

    /**
     * Reads a JSON entry fully, bounded by the shared zip-bomb guard and a hard
     * per-entry cap — a corrupt archive must not OOM the importer mid-read.
     *
     * The cap is enforced *while* reading: checking it after copying meant the whole
     * entry (up to the guard's 2 GB total) was already buffered before being rejected.
     */
    private fun readJsonEntry(
        zipIn: ZipInputStream,
        guard: com.sherwood.wta.util.SafeZip.EntryGuard
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = zipIn.read(buffer)
            if (read < 0) break
            guard.onBytes(read)
            if (out.size().toLong() + read > MAX_JSON_ENTRY_BYTES) {
                throw com.sherwood.wta.util.SafeZip.ZipBombException(
                    com.sherwood.wta.util.SafeZip.ZipBombException.Kind.TOTAL_SIZE,
                    "JSON entry exceeds ${MAX_JSON_ENTRY_BYTES / 1024 / 1024} MB"
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Identity used to skip re-imported duplicates. Remote apps dedup on
     * type+name+normalized URL; local-content apps (HTML/media/multi-web) have
     * no stable URL across export — resource paths get rewritten to
     * resources/... and back — so they dedup on type+name.
     */
    internal fun backupDedupKey(app: WebApp): String {
        val url = app.url.trim()
        val urlKey = if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)) {
            url.lowercase().removeSuffix("/")
        } else ""
        return "${app.appType.name}|${app.name.trim().lowercase()}|$urlKey"
    }

    /** Visible for unit tests (backward-compat parsing). */
    internal fun parseBackupData(jsonStr: String): BackupData {
        val root = JsonParser.parseString(jsonStr).asJsonObject
        val version = root.get("version")?.asInt ?: 1
        val exportTime = root.get("exportTime")?.asLong ?: System.currentTimeMillis()
        val appCount = root.get("appCount")?.asInt ?: 0
        val appsArray = root.getAsJsonArray("apps")

        val apps = appsArray?.mapNotNull { element ->
            runCatching {
                parseWebAppWithDefaults(element.asJsonObject)
            }.getOrElse {
                AppLogger.w(TAG, "解析应用配置失败，已跳过一项", it)
                null
            }
        } ?: emptyList()

        val categories = root.getAsJsonArray("categories")?.mapNotNull { element ->
            runCatching {
                gson.fromJson(element, AppCategory::class.java)
            }.getOrElse {
                AppLogger.w(TAG, "解析分类失败，已跳过一项", it)
                null
            }
        } ?: emptyList()

        val usageStats = root.getAsJsonArray("usageStats")?.mapNotNull { element ->
            runCatching {
                gson.fromJson(element, AppUsageStats::class.java)
            }.getOrElse {
                AppLogger.w(TAG, "解析使用统计失败，已跳过一项", it)
                null
            }
        } ?: emptyList()

        // v5+: absent in older backups -> empty, restore skips silently.
        val healthRecords = root.getAsJsonArray("healthRecords")?.mapNotNull { element ->
            runCatching {
                gson.fromJson(element, com.sherwood.wta.core.stats.AppHealthRecord::class.java)
            }.getOrElse {
                AppLogger.w(TAG, "解析健康记录失败，已跳过一项", it)
                null
            }
        } ?: emptyList()

        return BackupData(
            version = version,
            exportTime = exportTime,
            appCount = if (appCount > 0) appCount else apps.size,
            apps = apps,
            categories = categories,
            usageStats = usageStats,
            healthRecords = healthRecords
        )
    }

    /**
     * @param createdCategoryIds collects only the rows this import inserted, so a
     *        later rollback deletes exactly those and never a pre-existing category
     *        that was merely reused by name.
     */
    private suspend fun restoreCategories(
        categories: List<AppCategory>,
        createdCategoryIds: MutableList<Long>
    ): Map<Long, Long> {
        if (categories.isEmpty()) return emptyMap()
        val dao = AppDatabase.getInstance(context).appCategoryDao()
        // Same-name categories are reused instead of re-created: re-importing a
        // backup must not double the category list. New inserts are tracked too
        // so duplicate names inside the backup itself collapse to one row.
        val idByName = HashMap<String, Long>()
        dao.getAllCategories().first().forEach {
            idByName[it.name.trim().lowercase()] = it.id
        }
        val idMap = mutableMapOf<Long, Long>()
        categories.forEach { category ->
            val nameKey = category.name.trim().lowercase()
            val existingId = idByName[nameKey]
            if (existingId != null) {
                idMap[category.id] = existingId
                return@forEach
            }
            runCatching {
                val newId = dao.insert(category.copy(id = 0))
                if (newId > 0L) createdCategoryIds.add(newId)
                idMap[category.id] = newId
                idByName[nameKey] = newId
            }.onFailure { e ->
                AppLogger.w(TAG, "导入分类失败: ${category.name}", e)
            }
        }
        return idMap
    }

    private fun remapCategoryId(categoryId: Long?, categoryIdMap: Map<Long, Long>): Long? {
        if (categoryId == null) return null
        return categoryIdMap[categoryId] ?: categoryId
    }

    private suspend fun restoreUsageStats(
        usageStats: List<AppUsageStats>,
        appIdMap: Map<Long, Long>
    ) {
        if (usageStats.isEmpty() || appIdMap.isEmpty()) return
        val dao = AppDatabase.getInstance(context).appUsageStatsDao()
        var restored = 0
        usageStats.forEach { stats ->
            val newAppId = appIdMap[stats.appId] ?: return@forEach
            runCatching {
                dao.insert(stats.copy(id = 0, appId = newAppId))
                restored++
            }.onFailure { e ->
                AppLogger.w(TAG, "恢复使用统计失败: appId=${stats.appId}", e)
            }
        }
        AppLogger.i(TAG, "恢复使用统计 $restored/${usageStats.size} 项")
    }

    private suspend fun restoreHealthRecords(
        healthRecords: List<com.sherwood.wta.core.stats.AppHealthRecord>,
        appIdMap: Map<Long, Long>
    ) {
        if (healthRecords.isEmpty() || appIdMap.isEmpty()) return
        val dao = AppDatabase.getInstance(context).appUsageStatsDao()
        var restored = 0
        healthRecords.forEach { record ->
            val newAppId = appIdMap[record.appId] ?: return@forEach
            runCatching {
                dao.insertHealthRecord(record.copy(id = 0, appId = newAppId))
                restored++
            }.onFailure { e ->
                AppLogger.w(TAG, "恢复健康记录失败: appId=${record.appId}", e)
            }
        }
        AppLogger.i(TAG, "恢复健康记录 $restored/${healthRecords.size} 项")
    }

    private fun parseWebAppWithDefaults(appObject: JsonObject): WebApp {
        val defaults = gson.toJsonTree(WebApp(name = "", url = ""))
        val merged = Converters.mergeMissingDefaults(defaults, appObject)
        return runCatching {
            gson.fromJson(merged, WebApp::class.java)
        }.getOrNull() ?: throw com.google.gson.JsonParseException("Failed to parse WebApp with defaults")
    }

    private fun cleanupExtractedFiles(files: List<File>) {
        files.forEach { file ->
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "清理文件失败: ${file.absolutePath}", e)
            }
        }
    }

    fun cleanupBackupTempFiles() {
        val backupDirs = listOf(
            "backup_icons", "backup_splash", "backup_bgm",
            "backup_bgm_lrc", "backup_bgm_cover",
            "backup_html", "backup_media", "backup_statusbar",
            "backup_gallery", "backup_certs", "backup_multi_web", "backup_other"
        )

        backupDirs.forEach { dirName ->
            try {
                val dir = File(context.filesDir, dirName)
                if (dir.exists() && dir.isDirectory) {
                    dir.deleteRecursively()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "清理备份目录失败: $dirName", e)
            }
        }
    }

    fun getBackupTempSize(): Long {
        val backupDirs = listOf(
            "backup_icons", "backup_splash", "backup_bgm",
            "backup_bgm_lrc", "backup_bgm_cover",
            "backup_html", "backup_media", "backup_statusbar",
            "backup_gallery", "backup_certs", "backup_multi_web", "backup_other"
        )

        return backupDirs.sumOf { dirName ->
            val dir = File(context.filesDir, dirName)
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else 0L
        }
    }

    private fun collectAppResources(app: WebApp, resources: MutableMap<String, String>) {
        val appId = app.id.toString()

        app.iconPath?.let { path ->
            if (File(path).exists()) {
                val ext = path.substringAfterLast('.', "png")
                resources["${ICONS_DIR}${appId}_icon.$ext"] = path
            }
        }

        app.splashConfig?.mediaPath?.let { path ->
            if (File(path).exists()) {
                val ext = path.substringAfterLast('.', "png")
                resources["${SPLASH_DIR}${appId}_splash.$ext"] = path
            }
        }

        app.bgmConfig?.playlist?.forEachIndexed { index, bgmItem ->

            if (!bgmItem.isAsset && File(bgmItem.path).exists()) {
                val ext = bgmItem.path.substringAfterLast('.', "mp3")
                resources["${BGM_DIR}${appId}_bgm_$index.$ext"] = bgmItem.path
            }

            bgmItem.lrcPath?.let { lrcPath ->
                if (File(lrcPath).exists()) {
                    resources["${BGM_LRC_DIR}${appId}_bgm_$index.lrc"] = lrcPath
                }
            }

            bgmItem.coverPath?.let { coverPath ->
                if (File(coverPath).exists()) {
                    val ext = coverPath.substringAfterLast('.', "jpg")
                    resources["${BGM_COVER_DIR}${appId}_bgm_cover_$index.$ext"] = coverPath
                }
            }
        }

        app.webViewConfig.statusBarBackgroundImage?.let { path ->
            if (File(path).exists()) {
                val ext = path.substringAfterLast('.', "png")
                resources["${STATUSBAR_DIR}${appId}_statusbar.$ext"] = path
            }
        }

        app.webViewConfig.statusBarBackgroundImageDark?.let { path ->
            if (File(path).exists()) {
                val ext = path.substringAfterLast('.', "png")
                resources["${STATUSBAR_DIR}${appId}_statusbar_dark.$ext"] = path
            }
        }

        app.htmlConfig?.files?.forEach { htmlFile ->
            if (File(htmlFile.path).exists()) {
                resources["${HTML_DIR}${appId}/${htmlFile.name}"] = htmlFile.path
            }
        }

        if (app.appType == com.sherwood.wta.data.model.AppType.IMAGE ||
            app.appType == com.sherwood.wta.data.model.AppType.VIDEO) {
            val mediaPath = app.url
            if (mediaPath.isNotBlank() && File(mediaPath).exists()) {
                val ext = mediaPath.substringAfterLast('.', "mp4")
                resources["${MEDIA_DIR}${appId}_media.$ext"] = mediaPath
            }
        }

        app.mediaConfig?.mediaPath?.let { path ->
            if (File(path).exists()) {
                val ext = path.substringAfterLast('.', "mp4")
                resources["${MEDIA_DIR}${appId}_media_config.$ext"] = path
            }
        }

        app.galleryConfig?.items?.forEachIndexed { index, item ->
            if (File(item.path).exists()) {
                val ext = item.path.substringAfterLast('.', "jpg")
                resources["${GALLERY_DIR}${appId}_gallery_$index.$ext"] = item.path
            }
            item.thumbnailPath?.let { path ->
                if (File(path).exists()) {
                    val ext = path.substringAfterLast('.', "jpg")
                    resources["${GALLERY_DIR}${appId}_gallery_thumb_$index.$ext"] = path
                }
            }
        }

        app.apkExportConfig?.networkTrustConfig?.customCaCertificates?.forEachIndexed { index, cert ->
            if (File(cert.filePath).exists()) {
                val ext = cert.filePath.substringAfterLast('.', "cer")
                resources["${CERTS_DIR}${appId}_custom_ca_$index.$ext"] = cert.filePath
            }
        }

        app.multiWebConfig?.sites?.forEachIndexed { index, site ->
            val path = site.localFilePath
            if (path.isNotBlank() && File(path).exists()) {
                val ext = path.substringAfterLast('.', "html")
                resources["${MULTI_WEB_DIR}${appId}_site_$index.$ext"] = path
            }
        }
    }

    private fun collectLocalBackupFiles(): Map<String, File> {
        val files = linkedMapOf<String, File>()

        MANAGED_FILES_DIRS.forEach { dirName ->
            collectDirectoryFiles(
                sourceDir = File(context.filesDir, dirName),
                zipRoot = "$LOCAL_FILES_DIR$dirName/",
                files = files
            )
        }

        // Plugin index/overlay lives as a loose file in filesDir root; it rides
        // the generic local/files restore path (resolveSafeChild → filesDir).
        File(context.filesDir, com.sherwood.wta.core.plugin.PluginStore.STATE_FILE)
            .takeIf { it.isFile && it.canRead() }
            ?.let { files["$LOCAL_FILES_DIR${it.name}"] = it }

        val externalFilesDir = context.getExternalFilesDir(null)
        if (externalFilesDir != null) {
            MANAGED_EXTERNAL_DIRS.forEach { dirName ->
                collectDirectoryFiles(
                    sourceDir = File(externalFilesDir, dirName),
                    zipRoot = "$LOCAL_EXTERNAL_FILES_DIR$dirName/",
                    files = files
                )
            }
        }

        SAFE_DATASTORE_NAMES.forEach { name ->
            val file = File(context.filesDir, "datastore/$name.preferences_pb")
            if (file.exists() && file.isFile && file.canRead()) {
                files["$DATASTORE_DIR${file.name}"] = file
            }
        }

        // Signing identity + adblock lists live as loose files in filesDir root.
        // Exact names only: nothing else in the root is user state worth copying.
        (KEYSTORE_FILES.map { it to KEYSTORE_DIR } + ADBLOCK_FILES.map { it to ADBLOCK_DIR })
            .forEach { (name, zipDir) ->
                val file = File(context.filesDir, name)
                if (file.exists() && file.isFile && file.canRead()) {
                    files["$zipDir$name"] = file
                }
            }

        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        SAFE_SHARED_PREF_NAMES.forEach { name ->
            val file = File(sharedPrefsDir, "$name.xml")
            if (file.exists() && file.isFile && file.canRead()) {
                files["$SHARED_PREFS_DIR${file.name}"] = file
            }
        }

        if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
            sharedPrefsDir.listFiles { f ->
                f.isFile && f.name.endsWith(".xml") && f.canRead() &&
                    SAFE_SHARED_PREF_PREFIXES.any { f.name.startsWith(it) }
            }?.forEach { file ->
                files["$SHARED_PREFS_DIR${file.name}"] = file
            }
        }

        return files
    }

    private fun collectDirectoryFiles(
        sourceDir: File,
        zipRoot: String,
        files: MutableMap<String, File>
    ) {
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        sourceDir.walkTopDown()
            .filter { it.isFile && it.canRead() }
            .forEach { file ->
                val relativePath = file.relativeTo(sourceDir).invariantSeparatorsPath
                if (isExcludedManagedPath(relativePath)) return@forEach
                files["$zipRoot$relativePath"] = file
            }
    }

    private fun isExcludedManagedPath(relativePath: String): Boolean {
        return relativePath.split('/').any { segment ->
            segment in MANAGED_FILES_EXCLUDED_SEGMENTS
        }
    }

    private fun updateAppPathsToRelative(
        app: WebApp,
        resources: Map<String, String>
    ): WebApp {
        val appId = app.id.toString()

        fun findZipPath(localPath: String?): String? {
            if (localPath == null) return null
            return resources.entries.find { it.value == localPath }?.key
        }

        return app.copy(
            iconPath = findZipPath(app.iconPath),
            splashConfig = app.splashConfig?.copy(
                mediaPath = findZipPath(app.splashConfig?.mediaPath)
            ),
            bgmConfig = app.bgmConfig?.copy(
                playlist = app.bgmConfig?.playlist?.mapIndexed { index, item ->
                    if (!item.isAsset) {
                        item.copy(
                            path = findZipPath(item.path) ?: item.path,
                            lrcPath = findZipPath(item.lrcPath),
                            coverPath = findZipPath(item.coverPath)
                        )
                    } else item
                } ?: emptyList()
            ),
            htmlConfig = app.htmlConfig?.copy(
                files = app.htmlConfig?.files?.map { file ->
                    file.copy(path = "${HTML_DIR}${appId}/${file.name}")
                } ?: emptyList()
            ),
            mediaConfig = app.mediaConfig?.copy(
                mediaPath = findZipPath(app.mediaConfig.mediaPath) ?: app.mediaConfig.mediaPath
            ),
            galleryConfig = app.galleryConfig?.copy(
                items = app.galleryConfig.items.map { item ->
                    item.copy(
                        path = findZipPath(item.path) ?: item.path,
                        thumbnailPath = item.thumbnailPath?.let { findZipPath(it) ?: it }
                    )
                }
            ),
            apkExportConfig = app.apkExportConfig?.copy(
                networkTrustConfig = app.apkExportConfig.networkTrustConfig.copy(
                    customCaCertificates = app.apkExportConfig.networkTrustConfig.customCaCertificates.map { cert ->
                        cert.copy(filePath = findZipPath(cert.filePath) ?: cert.filePath)
                    }
                )
            ),
            multiWebConfig = app.multiWebConfig?.copy(
                sites = app.multiWebConfig.sites.map { site ->
                    site.copy(
                        localFilePath = findZipPath(site.localFilePath) ?: site.localFilePath
                    )
                }
            ),
            webViewConfig = app.webViewConfig.copy(
                statusBarBackgroundImage = findZipPath(app.webViewConfig.statusBarBackgroundImage),
                statusBarBackgroundImageDark = findZipPath(app.webViewConfig.statusBarBackgroundImageDark)
            ),
            url = if (app.appType == com.sherwood.wta.data.model.AppType.IMAGE ||
                      app.appType == com.sherwood.wta.data.model.AppType.VIDEO) {
                findZipPath(app.url) ?: app.url
            } else app.url
        )
    }

    private fun updateAppPathsToLocal(
        app: WebApp,
        extractedResources: Map<String, String>
    ): WebApp {
        return app.copy(
            iconPath = extractedResources[app.iconPath] ?: app.iconPath,
            splashConfig = app.splashConfig?.copy(
                mediaPath = extractedResources[app.splashConfig?.mediaPath] ?: app.splashConfig?.mediaPath
            ),
            bgmConfig = app.bgmConfig?.copy(
                playlist = app.bgmConfig?.playlist?.map { item ->
                    if (!item.isAsset) {
                        item.copy(
                            path = extractedResources[item.path] ?: item.path,
                            lrcPath = item.lrcPath?.let { extractedResources[it] ?: it },
                            coverPath = item.coverPath?.let { extractedResources[it] ?: it }
                        )
                    } else item
                } ?: emptyList()
            ),
            htmlConfig = app.htmlConfig?.copy(
                files = app.htmlConfig?.files?.map { file ->
                    file.copy(path = extractedResources[file.path] ?: file.path)
                } ?: emptyList()
            ),
            mediaConfig = app.mediaConfig?.copy(
                mediaPath = extractedResources[app.mediaConfig.mediaPath] ?: app.mediaConfig.mediaPath
            ),
            galleryConfig = app.galleryConfig?.copy(
                items = app.galleryConfig.items.map { item ->
                    item.copy(
                        path = extractedResources[item.path] ?: item.path,
                        thumbnailPath = item.thumbnailPath?.let { extractedResources[it] ?: it }
                    )
                }
            ),
            apkExportConfig = app.apkExportConfig?.copy(
                networkTrustConfig = app.apkExportConfig.networkTrustConfig.copy(
                    customCaCertificates = app.apkExportConfig.networkTrustConfig.customCaCertificates.map { cert ->
                        cert.copy(filePath = extractedResources[cert.filePath] ?: cert.filePath)
                    }
                )
            ),
            multiWebConfig = app.multiWebConfig?.copy(
                sites = app.multiWebConfig.sites.map { site ->
                    site.copy(
                        localFilePath = extractedResources[site.localFilePath] ?: site.localFilePath
                    )
                }
            ),
            webViewConfig = app.webViewConfig.copy(
                statusBarBackgroundImage = app.webViewConfig.statusBarBackgroundImage?.let {
                    extractedResources[it] ?: it
                },
                statusBarBackgroundImageDark = app.webViewConfig.statusBarBackgroundImageDark?.let {
                    extractedResources[it] ?: it
                }
            ),
            url = if (app.appType == com.sherwood.wta.data.model.AppType.IMAGE ||
                      app.appType == com.sherwood.wta.data.model.AppType.VIDEO) {
                extractedResources[app.url] ?: app.url
            } else app.url
        )
    }

    /** A resource entry extracted to a staging directory, with its archive digest. */
    private class StagedResource(
        val file: File,
        val fingerprint: BackupEntryFingerprint
    )

    private fun extractResourceFile(
        zipPath: String,
        zipIn: ZipInputStream,
        guard: com.sherwood.wta.util.SafeZip.EntryGuard? = null
    ): StagedResource? {
        return try {
            val (targetDir, prefix) = when {
                zipPath.startsWith(ICONS_DIR) -> File(context.filesDir, "backup_icons") to ICONS_DIR
                zipPath.startsWith(SPLASH_DIR) -> File(context.filesDir, "backup_splash") to SPLASH_DIR
                zipPath.startsWith(BGM_LRC_DIR) -> File(context.filesDir, "backup_bgm_lrc") to BGM_LRC_DIR
                zipPath.startsWith(BGM_COVER_DIR) -> File(context.filesDir, "backup_bgm_cover") to BGM_COVER_DIR
                zipPath.startsWith(BGM_DIR) -> File(context.filesDir, "backup_bgm") to BGM_DIR
                zipPath.startsWith(HTML_DIR) -> File(context.filesDir, "backup_html") to HTML_DIR
                zipPath.startsWith(MEDIA_DIR) -> File(context.filesDir, "backup_media") to MEDIA_DIR
                zipPath.startsWith(STATUSBAR_DIR) -> File(context.filesDir, "backup_statusbar") to STATUSBAR_DIR
                zipPath.startsWith(GALLERY_DIR) -> File(context.filesDir, "backup_gallery") to GALLERY_DIR
                zipPath.startsWith(CERTS_DIR) -> File(context.filesDir, "backup_certs") to CERTS_DIR
                zipPath.startsWith(MULTI_WEB_DIR) -> File(context.filesDir, "backup_multi_web") to MULTI_WEB_DIR
                else -> File(context.filesDir, "backup_other") to RESOURCES_DIR
            }

            targetDir.mkdirs()

            val relativePath = zipPath.removePrefix(prefix).ifBlank { zipPath.substringAfterLast('/') }
            val targetFile = resolveSafeChild(targetDir, relativePath) ?: return null
            targetFile.parentFile?.mkdirs()

            // Digest as the bytes are written: verification must not need a second
            // read of a file that could have been hundreds of megabytes.
            val fingerprint = FileOutputStream(targetFile).use { output ->
                val sink = Sha256Sink(output)
                if (guard != null) guard.copyTo(zipIn, sink) else zipIn.copyTo(sink)
                sink.flush()
                BackupEntryFingerprint(zipPath, sink.size, sink.sha256())
            }

            StagedResource(targetFile, fingerprint)
        } catch (e: com.sherwood.wta.util.SafeZip.ZipBombException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "解压资源文件失败: $zipPath", e)
            null
        }
    }

    private fun isLocalBackupEntry(zipPath: String): Boolean {
        return zipPath.startsWith(LOCAL_FILES_DIR) ||
            zipPath.startsWith(LOCAL_EXTERNAL_FILES_DIR) ||
            zipPath.startsWith(DATASTORE_DIR) ||
            zipPath.startsWith(SHARED_PREFS_DIR) ||
            zipPath.startsWith(KEYSTORE_DIR) ||
            zipPath.startsWith(ADBLOCK_DIR)
    }

    /**
     * A live target with its bytes staged in a temp file: the unit of the restore
     * transaction.
     *
     * Entries are collected during zip streaming but never written then. Writing live
     * config files before the archive was even parsed meant a corrupt/truncated backup
     * aborted AFTER already overwriting presets, extension storage and theme prefs.
     * Now [LocalFileTransaction] snapshots, journals and only then publishes them.
     *
     * [size]/[sha256] are the staged bytes exactly as read from the archive, which is
     * what the manifest check compares against.
     */
    internal data class PendingLocalEntry(
        val zipPath: String,
        val targetFile: File,
        val tempFile: File,
        val size: Long,
        val sha256: String
    ) {
        val fingerprint: BackupEntryFingerprint
            get() = BackupEntryFingerprint(zipPath, size, sha256)
    }

    /**
     * True when the staged entry must not replace the live file.
     *
     * Only the AI config qualifies today: its content is encrypted with a key that
     * belongs to the install that wrote the backup, so importing it would make the
     * API keys on this device permanently unreadable. When the device has no local
     * AI config yet the file is still imported — there is nothing to protect, and
     * [com.sherwood.wta.core.ai.AiConfigManager] reads the unreadable keys as empty.
     */
    internal fun keepLocalAiConfig(entry: PendingLocalEntry): Boolean =
        entry.zipPath == AI_CONFIG_DATASTORE_ENTRY && entry.targetFile.exists()

    /** Visible for unit tests (zip-slip staging rules). */
    internal fun stageLocalBackupEntry(
        zipPath: String,
        zipIn: ZipInputStream,
        guard: com.sherwood.wta.util.SafeZip.EntryGuard? = null
    ): PendingLocalEntry? {
        val targetFile = when {
            zipPath.startsWith(LOCAL_FILES_DIR) -> {
                val relativePath = zipPath.removePrefix(LOCAL_FILES_DIR)
                resolveSafeChild(context.filesDir, relativePath)
            }
            zipPath.startsWith(LOCAL_EXTERNAL_FILES_DIR) -> {
                val externalFilesDir = context.getExternalFilesDir(null) ?: return null
                val relativePath = zipPath.removePrefix(LOCAL_EXTERNAL_FILES_DIR)
                resolveSafeChild(externalFilesDir, relativePath)
            }
            zipPath.startsWith(DATASTORE_DIR) -> {
                val fileName = zipPath.removePrefix(DATASTORE_DIR)
                val allowed = SAFE_DATASTORE_NAMES.map { "$it.preferences_pb" }.contains(fileName)
                if (allowed) {
                    resolveSafeChild(File(context.filesDir, "datastore"), fileName)
                } else null
            }
            zipPath.startsWith(SHARED_PREFS_DIR) -> {
                val fileName = zipPath.removePrefix(SHARED_PREFS_DIR)
                val allowed = SAFE_SHARED_PREF_NAMES.map { "$it.xml" }.contains(fileName) ||
                    (fileName.endsWith(".xml") &&
                        SAFE_SHARED_PREF_PREFIXES.any { fileName.startsWith(it) })
                // resolveSafeChild is load-bearing here: a crafted name like
                // "gm_storage_/../evil.xml" passes the prefix/suffix checks but
                // must never escape shared_prefs.
                if (allowed) {
                    resolveSafeChild(File(context.applicationInfo.dataDir, "shared_prefs"), fileName)
                } else null
            }
            zipPath.startsWith(KEYSTORE_DIR) -> {
                val fileName = zipPath.removePrefix(KEYSTORE_DIR)
                if (KEYSTORE_FILES.contains(fileName)) {
                    resolveSafeChild(context.filesDir, fileName)
                } else null
            }
            zipPath.startsWith(ADBLOCK_DIR) -> {
                val fileName = zipPath.removePrefix(ADBLOCK_DIR)
                if (ADBLOCK_FILES.contains(fileName)) {
                    resolveSafeChild(context.filesDir, fileName)
                } else null
            }
            else -> null
        } ?: return null

        val tempFile = File.createTempFile("wta_backup_", ".part", context.cacheDir)
        return try {
            val fingerprint = FileOutputStream(tempFile).use { output ->
                val sink = Sha256Sink(output)
                if (guard != null) guard.copyTo(zipIn, sink) else zipIn.copyTo(sink, BUFFER_SIZE)
                sink.flush()
                BackupEntryFingerprint(zipPath, sink.size, sink.sha256())
            }
            PendingLocalEntry(zipPath, targetFile, tempFile, fingerprint.size, fingerprint.sha256)
        } catch (e: com.sherwood.wta.util.SafeZip.ZipBombException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "暂存本地备份数据失败: $zipPath", e)
            tempFile.delete()
            null
        }
    }

    /**
     * Stages a legacy extensions JSON entry as a normal local write.
     *
     * These archives predate the `local/files/extension_modules/` path, and the files
     * are the same two plugin-state files. Routing them through the transaction means
     * they get the same snapshot/journal/rollback as every other local entry instead
     * of being written after the commit with no way back.
     */
    private fun stageExtensionEntry(
        zipPath: String,
        bytes: ByteArray,
        into: MutableList<PendingLocalEntry>
    ) {
        val fileName = zipPath.removePrefix(EXTENSION_DIR)
        val targetFile = resolveSafeChild(
            File(context.filesDir, "extension_modules").apply { mkdirs() },
            fileName
        ) ?: return
        val tempFile = File.createTempFile("wta_backup_", ".part", context.cacheDir)
        try {
            tempFile.writeBytes(bytes)
        } catch (e: Exception) {
            AppLogger.w(TAG, "暂存插件配置失败: $zipPath", e)
            tempFile.delete()
            return
        }
        into.add(
            PendingLocalEntry(
                zipPath = zipPath,
                targetFile = targetFile,
                tempFile = tempFile,
                size = bytes.size.toLong(),
                sha256 = BackupIntegrity.hex(bytes)
            )
        )
    }

    /** Visible for unit tests (traversal rules). */
    internal fun resolveSafeChild(baseDir: File, relativePath: String): File? {
        // Lexical rejection first: deterministic on every runtime (backup unit
        // tests run under Robolectric, whose File resolution must not weaken
        // this). Legitimate entries come from relativeTo() walks and fixed
        // names — they never contain ".." segments or absolute paths.
        if (relativePath.isBlank()) return null
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
            AppLogger.w(TAG, "跳过不安全的备份路径: $relativePath")
            return null
        }
        val targetFile = File(baseDir, normalized)
        val baseCanonical = baseDir.canonicalFile
        val targetCanonical = targetFile.canonicalFile
        return if (targetCanonical.path.startsWith(baseCanonical.path + File.separator)) {
            targetCanonical
        } else {
            AppLogger.w(TAG, "跳过不安全的备份路径: $relativePath")
            null
        }
    }

    fun generateBackupFileName(): String {
        return "Sherwood_Studio_Backup_${backupDateFormat.get()!!.format(Date())}.zip"
    }

    /**
     * Restarts the host process so restored files actually take effect.
     *
     * DataStore and SharedPreferences both cache state in memory: files swapped
     * in by import would otherwise be ignored until restart — and worse, the
     * next write from stale memory would clobber the restored values. Call
     * after a successful import (the UI shows its success toast first).
     */
    fun scheduleAppRestart(delayMs: Long = 800) {
        try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                } ?: return
            val pending = android.app.PendingIntent.getActivity(
                context, 0, launchIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = context.getSystemService(android.content.Context.ALARM_SERVICE)
                as android.app.AlarmManager
            // set(), not setExact(): no exact-alarm permission needed for a sub-second
            // nudge. RTC_WAKEUP so a dozing device still restarts promptly.
            alarm.set(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pending)
            AppLogger.i(TAG, "应用将在 ${delayMs}ms 后重启以应用恢复的数据")
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            AppLogger.e(TAG, "计划重启失败，请手动重启应用", e)
        }
    }
}

data class ExportResult(
    val appCount: Int,
    val resourceCount: Int
)

data class ImportResult(
    val totalCount: Int,
    val importedCount: Int,
    val skippedCount: Int,
    /** Backup apps already installed (same type+name+URL), skipped not duplicated. */
    val duplicateCount: Int = 0,
    /** True when any local (prefs/datastore/shared/file) entry was committed. */
    val localFilesRestored: Boolean = false,
    /** True when an earlier, interrupted restore had to be rolled back first. */
    val interruptedRestoreRecovered: Boolean = false
)

/**
 * Failure of a restore, carrying whether the transaction was undone.
 *
 * The UI needs that distinction: "nothing changed" and "the rollback itself failed"
 * are very different messages, and reporting either one unconditionally would be a
 * lie in the other case. The original failure is kept as [cause] for the report.
 */
class BackupImportException(
    val rolledBack: Boolean,
    cause: Throwable
) : Exception(cause.message ?: "Backup import failed", cause)
