package com.sherwood.wta.core.apkbuilder

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.data.model.WebApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

enum class ModifyApkMode {
    FULL,
    CONTENT_OVERLAY
}

enum class IncrementalBuildMode {
    FULL,
    CONTENT_OVERLAY,
    REUSE_UNSIGNED
}

data class ApkBuildCacheMeta(
    val schema: Int = SCHEMA_VERSION,
    val cacheKey: String,
    val packageName: String,
    val identityFingerprint: String,
    val contentFingerprint: String,
    val shellTemplateId: String,
    /**
     * SHA-256 of the cached unsigned APK. The cache is the build input of every
     * REUSE_UNSIGNED/CONTENT_OVERLAY run, so a truncated or half-written entry would be
     * repackaged and signed as if it were a complete APK — the user would only find out
     * when the generated app fails to install. Every entry must carry the hash of its
     * own bytes, and a mismatch is a cache miss, never a "probably fine".
     */
    val unsignedSha256: String = "",
    val unsignedSize: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", schema)
        .put("cacheKey", cacheKey)
        .put("packageName", packageName)
        .put("identityFingerprint", identityFingerprint)
        .put("contentFingerprint", contentFingerprint)
        .put("shellTemplateId", shellTemplateId)
        .put("unsignedSha256", unsignedSha256)
        .put("unsignedSize", unsignedSize)
        .put("updatedAt", updatedAt)

    companion object {
        // 3: entries carry unsignedSha256/unsignedSize. A v2 entry cannot be verified,
        // so it is a cache miss — the upgrade costs one FULL rebuild per app and never
        // serves bytes we cannot check.
        const val SCHEMA_VERSION = 3

        fun fromJson(raw: String): ApkBuildCacheMeta? {
            return try {
                val obj = JSONObject(raw)
                if (obj.optInt("schema") != SCHEMA_VERSION) return null
                ApkBuildCacheMeta(
                    schema = obj.getInt("schema"),
                    cacheKey = obj.getString("cacheKey"),
                    packageName = obj.getString("packageName"),
                    identityFingerprint = obj.getString("identityFingerprint"),
                    contentFingerprint = obj.getString("contentFingerprint"),
                    shellTemplateId = obj.optString("shellTemplateId", ""),
                    unsignedSha256 = obj.optString("unsignedSha256", ""),
                    unsignedSize = obj.optLong("unsignedSize", -1L),
                    updatedAt = obj.optLong("updatedAt", 0L)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class IncrementalPlan(
    val mode: IncrementalBuildMode,
    val cachedUnsigned: File? = null,
    val identityFingerprint: String,
    val contentFingerprint: String,
    val shellTemplateId: String,
    val reason: String
)

class ApkBuildCache(private val context: Context) {

    companion object {
        private const val TAG = "ApkBuildCache"
        private const val ROOT_DIR = "apk_build_cache"
        private const val UNSIGNED_NAME = "base_unsigned.apk"
        private const val META_NAME = "meta.json"
        private const val MAX_CACHE_APPS = 20
    }

    private val rootDir: File =
        File(context.filesDir, ROOT_DIR).apply { mkdirs() }

    fun cacheKeyFor(webApp: WebApp, packageName: String): String {
        return if (webApp.id > 0) {
            "app_${webApp.id}"
        } else {
            "pkg_" + packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        }
    }

    fun shellTemplateId(templateApk: File): String {
        return "sha256=${fileSha256(templateApk)}"
    }

    fun plan(
        webApp: WebApp,
        packageName: String,
        config: ApkConfig,
        templateApk: File,
        encryptionEnabled: Boolean,
        abiFilters: List<String>,
        projectDirs: List<File?>,
        mediaContentPath: String?,
        splashMediaPath: String?,
        bgmPlaylistPaths: List<String>,
        htmlFiles: List<com.sherwood.wta.data.model.HtmlFile>,
        galleryItems: List<com.sherwood.wta.data.model.GalleryItem>,
        errorPageMediaPath: String?,
        nativeLibsFingerprint: String? = null,
        hostVersionCode: Int = 0,
        forceFullRebuild: Boolean,
        manifestFingerprint: String? = null,
        perfFingerprint: String? = null,
        signingFingerprint: String? = null,
        multiWebSiteGalleryItems: List<com.sherwood.wta.data.model.GalleryItem> = emptyList(),
        multiWebSiteMediaPaths: List<String> = emptyList(),
        /**
         * Context of the building coroutine. Fingerprinting a large project tree reads and
         * hashes every file, which is the slowest part of planning, so the caller's job is
         * checked between the expensive steps. Defaults to no cancellation (unit tests).
         */
        cancellation: CoroutineContext = EmptyCoroutineContext
    ): IncrementalPlan {
        cancellation.ensureActive()
        val shellId = shellTemplateId(templateApk)
        cancellation.ensureActive()
        val identity = identityFingerprint(
            config = config,
            shellTemplateId = shellId,
            encryptionEnabled = encryptionEnabled,
            abiFilters = abiFilters,
            iconPath = webApp.iconPath,
            iconBackgroundColor = webApp.apkExportConfig?.iconBackgroundColor,
            nativeLibsFingerprint = nativeLibsFingerprint,
            hostVersionCode = hostVersionCode,
            manifestFingerprint = manifestFingerprint.orEmpty() +
                "|saep=${webApp.apkExportConfig?.saepEnabled == true}:${SaepPolicy.VERSION}",
            perfFingerprint = perfFingerprint,
            signingFingerprint = signingFingerprint
        )
        val content = contentFingerprint(
            config = config,
            projectDirs = projectDirs,
            mediaContentPath = mediaContentPath,
            splashMediaPath = splashMediaPath,
            bgmPlaylistPaths = bgmPlaylistPaths,
            htmlFiles = htmlFiles,
            galleryItems = galleryItems,
            errorPageMediaPath = errorPageMediaPath,
            statusBarImage = config.statusBarBackgroundImage,
            statusBarImageDark = config.statusBarBackgroundImageDark,
            floatingIcon = config.floatingWindowMinimizedIconPath,
            multiWebSiteGalleryItems = multiWebSiteGalleryItems,
            multiWebSiteMediaPaths = multiWebSiteMediaPaths,
            cancellation = cancellation
        )

        if (forceFullRebuild) {
            return IncrementalPlan(
                mode = IncrementalBuildMode.FULL,
                identityFingerprint = identity,
                contentFingerprint = content,
                shellTemplateId = shellId,
                reason = "forceFullRebuild"
            )
        }
        if (encryptionEnabled) {
            return IncrementalPlan(
                mode = IncrementalBuildMode.FULL,
                identityFingerprint = identity,
                contentFingerprint = content,
                shellTemplateId = shellId,
                reason = "encryptionEnabled"
            )
        }

        val key = cacheKeyFor(webApp, packageName)
        val entry = when (val loaded = load(key, cancellation)) {
            is CacheLoad.Missing -> return IncrementalPlan(
                mode = IncrementalBuildMode.FULL,
                identityFingerprint = identity,
                contentFingerprint = content,
                shellTemplateId = shellId,
                reason = "cacheMiss"
            )
            // The entry exists but its bytes do not match its own metadata. Serving it
            // would package a truncated APK; evict it and rebuild from the template.
            is CacheLoad.Corrupt -> {
                evict(key, loaded.reason)
                return IncrementalPlan(
                    mode = IncrementalBuildMode.FULL,
                    identityFingerprint = identity,
                    contentFingerprint = content,
                    shellTemplateId = shellId,
                    reason = loaded.reason
                )
            }
            is CacheLoad.Found -> loaded
        }

        if (entry.meta.packageName != packageName) {
            return IncrementalPlan(
                mode = IncrementalBuildMode.FULL,
                identityFingerprint = identity,
                contentFingerprint = content,
                shellTemplateId = shellId,
                reason = "packageNameChanged"
            )
        }
        if (entry.meta.shellTemplateId != shellId) {
            return IncrementalPlan(
                mode = IncrementalBuildMode.FULL,
                identityFingerprint = identity,
                contentFingerprint = content,
                shellTemplateId = shellId,
                reason = "shellTemplateChanged"
            )
        }
        if (entry.meta.identityFingerprint != identity) {
            return IncrementalPlan(
                mode = IncrementalBuildMode.FULL,
                identityFingerprint = identity,
                contentFingerprint = content,
                shellTemplateId = shellId,
                reason = "identityChanged"
            )
        }
        if (entry.meta.contentFingerprint == content) {
            return IncrementalPlan(
                mode = IncrementalBuildMode.REUSE_UNSIGNED,
                cachedUnsigned = entry.unsignedApk,
                identityFingerprint = identity,
                contentFingerprint = content,
                shellTemplateId = shellId,
                reason = "identityAndContentMatch"
            )
        }
        return IncrementalPlan(
            mode = IncrementalBuildMode.CONTENT_OVERLAY,
            cachedUnsigned = entry.unsignedApk,
            identityFingerprint = identity,
            contentFingerprint = content,
            shellTemplateId = shellId,
            reason = "contentChanged"
        )
    }

    /**
     * Stores [unsignedApk] as this app's incremental base.
     *
     * Both files are written through a sibling `.tmp` file and renamed into place, so a
     * reader can never observe a half-written APK or a metadata file describing bytes that
     * are not there yet. The APK is renamed first and the metadata second: a crash in
     * between leaves a new APK with the previous metadata, which [load] rejects on the
     * hash and rebuilds — the reverse order could leave metadata for an APK that was never
     * completed, which is only indistinguishable from a good entry if the bytes happen to
     * match a stale hash.
     *
     * The hash is computed in the same pass as the copy, so verifying an entry later costs
     * a read, never a second write.
     */
    fun saveUnsigned(
        webApp: WebApp,
        packageName: String,
        unsignedApk: File,
        identityFingerprint: String,
        contentFingerprint: String,
        shellTemplateId: String,
        /**
         * Context of the building coroutine; a cancelled build stops copying mid-copy and
         * leaves the previous entry untouched.
         */
        cancellation: CoroutineContext = EmptyCoroutineContext
    ) {
        if (!unsignedApk.isFile || unsignedApk.length() == 0L) return
        val key = cacheKeyFor(webApp, packageName)
        val dir = File(rootDir, key).apply { mkdirs() }
        val dest = File(dir, UNSIGNED_NAME)
        val metaFile = File(dir, META_NAME)
        val apkTmp = File(dir, "$UNSIGNED_NAME.tmp")
        val metaTmp = File(dir, "$META_NAME.tmp")
        try {
            // Leftovers from a save that died between write and rename.
            apkTmp.delete()
            metaTmp.delete()

            val stored = copyIntoPlace(unsignedApk, dest, apkTmp, cancellation)

            val meta = ApkBuildCacheMeta(
                cacheKey = key,
                packageName = packageName,
                identityFingerprint = identityFingerprint,
                contentFingerprint = contentFingerprint,
                shellTemplateId = shellTemplateId,
                unsignedSha256 = stored.sha256,
                unsignedSize = stored.size
            )
            writeTextAtomically(metaFile, metaTmp, meta.toJson().toString())
            AppLogger.i(TAG, "Saved incremental cache for $key (${dest.length() / 1024} KB)")
            trimOldCaches()
        } catch (e: CancellationException) {
            // Cancellation is not a cache failure: drop the temp files and rethrow so the
            // build stops instead of being reported as a successful save of nothing.
            apkTmp.delete()
            metaTmp.delete()
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to save cache for $key: ${e.message}")
            try {
                dest.delete()
                metaFile.delete()
                apkTmp.delete()
                metaTmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Streaming copy that returns the SHA-256 and length of what was actually written and
     * publishes it with a single [File.renameTo] in the same directory.
     */
    private fun copyIntoPlace(
        source: File,
        target: File,
        temp: File,
        cancellation: CoroutineContext
    ): StoredApk {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    cancellation.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    size += read
                }
                out.flush()
                // Durable before the rename: otherwise a power loss can leave the renamed
                // name pointing at blocks that were never flushed.
                out.fd.sync()
            }
        }
        publish(temp, target)
        return StoredApk(digest.digest().toHexString(), size)
    }

    private data class StoredApk(val sha256: String, val size: Long)

    private fun writeTextAtomically(target: File, temp: File, text: String) {
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        publish(temp, target)
    }

    private fun publish(temp: File, target: File) {
        if (temp.renameTo(target)) return
        // Same-directory renames do not fail on a healthy filesystem; if the platform
        // refuses (e.g. target replaced concurrently), fall back to delete-then-rename
        // rather than leaving the caller with a stale entry.
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Unable to publish ${target.name}")
        }
    }

    private fun evict(key: String, reason: String) {
        val dir = File(rootDir, key)
        if (!dir.exists()) return
        AppLogger.w(TAG, "Evicting incremental cache $key ($reason)")
        dir.deleteRecursively()
    }

    fun clear(webApp: WebApp, packageName: String) {
        val key = cacheKeyFor(webApp, packageName)
        val dir = File(rootDir, key)
        if (dir.exists()) {
            dir.deleteRecursively()
            AppLogger.i(TAG, "Cleared incremental cache for $key")
        }
    }

    /** Disk bytes the [clear] call for this app would free; 0 when nothing is cached. */
    fun entrySizeBytes(webApp: WebApp, packageName: String): Long {
        val dir = File(rootDir, cacheKeyFor(webApp, packageName))
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearAll() {
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
            rootDir.mkdirs()
        }
    }

    fun isContentReplaceableEntry(entryName: String): Boolean {
        if (entryName == ApkTemplate.CONFIG_PATH) return true
        if (entryName == "${ApkTemplate.CONFIG_PATH}.enc") return true
        if (entryName == "assets/encryption_meta.json") return true
        if (entryName == "assets/wta_adblock_compiled.txt") return true
        if (entryName == "assets/wta_perf_optimize.js") return true
        if (entryName == "assets/statusbar_background.png") return true
        if (entryName == "assets/statusbar_background_dark.png") return true
        if (entryName == "assets/announcement_icon.png") return true
        if (entryName == "assets/floating_window_minimized_icon.png") return true
        if (entryName.startsWith("assets/splash_media.")) return true
        if (entryName.startsWith("assets/error_page_media.")) return true
        if (entryName.startsWith("assets/media_content.")) return true
        if (entryName.startsWith("assets/gallery/")) return true
        if (entryName.startsWith("assets/bgm/")) return true
        if (entryName.startsWith("assets/html/")) return true
        if (entryName.startsWith("assets/html_projects/")) return true
        if (entryName.startsWith("assets/nodejs_app/")) return true
        if (entryName.startsWith("assets/php_app/")) return true
        if (entryName.startsWith("assets/python/")) return true
        if (entryName.startsWith("assets/python_app/")) return true
        // Re-embedded by RuntimeAssetEmbedder.embedPythonStdlib on every build in both
        // modes; without this entry CONTENT_OVERLAY copies the cached stdlib AND the
        // embedder writes it again — duplicate entries accumulating on every rebuild.
        if (entryName.startsWith("assets/python_runtime/")) return true
        if (entryName.startsWith("assets/go_app/")) return true
        if (entryName.startsWith("assets/frontend_app/")) return true
        if (entryName.startsWith("assets/static_pack/")) return true
        if (entryName.startsWith("assets/wordpress/")) return true
        if (entryName.startsWith("assets/wta_custom_ca/")) return true
        if (entryName.startsWith("assets/multiweb_sites/")) return true
        if (entryName.startsWith("assets/multi_web/")) return true
        return false
    }

    private sealed interface CacheLoad {
        /** No entry, or metadata we cannot interpret (older schema). Rebuild silently. */
        data object Missing : CacheLoad

        /** An entry exists but its bytes cannot be trusted; [reason] names the miss. */
        data class Corrupt(val reason: String) : CacheLoad

        data class Found(val meta: ApkBuildCacheMeta, val unsignedApk: File) : CacheLoad
    }

    /**
     * Reads an entry and proves that the cached APK is the exact file the metadata
     * describes. Every reuse path starts here, so a corrupt entry can never reach
     * packaging or signing: it is evicted and the build falls back to a full rebuild.
     */
    private fun load(key: String, cancellation: CoroutineContext): CacheLoad {
        val dir = File(rootDir, key)
        val metaFile = File(dir, META_NAME)
        val unsigned = File(dir, UNSIGNED_NAME)
        if (!metaFile.isFile || !unsigned.isFile) return CacheLoad.Missing
        val rawMeta = try {
            metaFile.readText()
        } catch (_: Exception) {
            return CacheLoad.Corrupt("cachedMetaUnreadable")
        }
        val meta = ApkBuildCacheMeta.fromJson(rawMeta) ?: return CacheLoad.Missing
        if (meta.unsignedSha256.isBlank()) return CacheLoad.Corrupt("cachedUnsignedUnverifiable")
        // A zero-length cache entry would sign into a zero-length APK; never reuse one.
        if (meta.unsignedSize <= 0L || unsigned.length() != meta.unsignedSize) {
            return CacheLoad.Corrupt("cachedUnsignedCorrupt")
        }
        cancellation.ensureActive()
        val actual = try {
            fileSha256(unsigned)
        } catch (_: Exception) {
            return CacheLoad.Corrupt("cachedUnsignedUnreadable")
        }
        // ensureActive() sits *before* the comparison on purpose: a cancelled build must
        // stop even when the entry it just read happens to be valid.
        cancellation.ensureActive()
        if (actual != meta.unsignedSha256) return CacheLoad.Corrupt("cachedUnsignedCorrupt")
        return CacheLoad.Found(meta, unsigned)
    }

    private fun trimOldCaches() {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory } ?: return
        if (dirs.size <= MAX_CACHE_APPS) return
        dirs.sortedBy {
            val metaFile = File(it, META_NAME)
            if (metaFile.isFile) {
                ApkBuildCacheMeta.fromJson(metaFile.readText())?.updatedAt ?: it.lastModified()
            } else {
                it.lastModified()
            }
        }.take(dirs.size - MAX_CACHE_APPS).forEach { old ->
            try {
                old.deleteRecursively()
                AppLogger.i(TAG, "Evicted incremental cache ${old.name}")
            } catch (_: Exception) {
            }
        }
    }

    private fun identityFingerprint(
        config: ApkConfig,
        shellTemplateId: String,
        encryptionEnabled: Boolean,
        abiFilters: List<String>,
        iconPath: String?,
        iconBackgroundColor: String? = null,
        nativeLibsFingerprint: String? = null,
        hostVersionCode: Int = 0,
        manifestFingerprint: String? = null,
        perfFingerprint: String? = null,
        signingFingerprint: String? = null
    ): String {
        val parts = mutableListOf<String>()
        parts += "shell=$shellTemplateId"
        parts += "pkg=${config.packageName}"
        parts += "vc=${config.versionCode}"
        parts += "vn=${config.versionName}"
        // Host app versionCode participates so a host upgrade (which may ship improved
        // packaging/alignment/export logic) invalidates every cached unsigned APK, forcing a
        // FULL rebuild that re-applies the latest logic. Without this, a user who built a
        // NODEJS_APP on an older host and upgrades without re-downloading libnode.so would
        // keep hitting REUSE_UNSIGNED and serving a stale APK (e.g. one with an unaligned
        // libnode.so that dlopen rejects on Android 15+/16KB-page devices).
        parts += "hostVc=$hostVersionCode"
        parts += "name=${config.appName}"
        parts += "type=${config.appType}"
        parts += "engine=${config.engineType}"
        parts += "theme=${config.themeType}"
        parts += "abi=${abiFilters.sorted().joinToString(",")}"
        parts += "enc=$encryptionEnabled"
        parts += "icon=${fileFingerprint(iconPath)}"
        parts += "iconBg=${iconBackgroundColor ?: "auto"}"
        parts += "deeplinkHosts=${config.deepLinkHosts.sorted().joinToString(",")}"
        parts += "deeplinkSchemes=${config.deepLinkSchemes.sorted().joinToString(",")}"
        // Inbound share sheet (#943) also rewrites the manifest — the ACTION_SEND
        // intent-filter. Without it in the key, flipping the toggle would reuse a cached
        // unsigned APK that never became a share target.
        parts += "shareReceive=${config.shareReceiveMimeTypes.sorted().joinToString(",")}"
        // The open-with flag changes the manifest (extra VIEW intent-filters), so it must
        // bust the manifest-fingerprinted cache just like the share/deep-link lists do.
        parts += "openWith=${config.openWithEnabled}"
        parts += "runtimePerms=${config.runtimePermissions}"
        parts += "networkTrust=${config.networkTrustConfig}"
        // Native libs (libnode.so / libnode_bridge.so / libc++_shared.so) must participate
        // so a host upgrade that ships a new/realigned libnode.so invalidates the cached
        // unsigned APK. Otherwise REUSE_UNSIGNED serves a stale unaligned lib that dlopen
        // rejects on Android 15+ (16KB-page) devices.
        parts += "nativeLibs=${nativeLibsFingerprint ?: "none"}"
        // targetSdk / minSdk overrides change the manifest's <uses-sdk>; without
        // these a cached unsigned APK would be reused after the user changes them.
        parts += "targetSdk=${config.targetSdkOverride ?: GeneratedSdkLevels.TEMPLATE_TARGET_SDK}"
        parts += "minSdk=${config.minSdkOverride ?: GeneratedSdkLevels.TEMPLATE_MIN_SDK}"
        // The derived manifest permission/component set: CONTENT_OVERLAY reuses the
        // cached base's AndroidManifest, so a config change that alters this set without
        // touching any other identity part (e.g. enabling scheduled start →
        // SCHEDULE_EXACT_ALARM + ScheduledStartReceiver) must change the identity or the
        // overlay ships a stale manifest.
        parts += "manifest=${manifestFingerprint ?: "n/a"}"
        // Export-level performance options change output bytes (resource stripping,
        // asset re-optimization, wta_perf_optimize.js injection) without appearing in
        // ApkConfig — key them explicitly or REUSE_UNSIGNED serves the old setting.
        parts += "perf=${perfFingerprint ?: "off"}"
        // The unsigned APK is not signer-agnostic: encrypted builds embed the signing cert
        // hash in the encryption metadata. Switching between global and per-app signing
        // (or a regenerated identity) must not serve a cached APK bound to the old cert.
        parts += "signer=${signingFingerprint ?: "global"}"
        return sha256(parts.joinToString("\n"))
    }

    private fun contentFingerprint(
        config: ApkConfig,
        projectDirs: List<File?>,
        mediaContentPath: String?,
        splashMediaPath: String?,
        bgmPlaylistPaths: List<String>,
        htmlFiles: List<com.sherwood.wta.data.model.HtmlFile>,
        galleryItems: List<com.sherwood.wta.data.model.GalleryItem>,
        errorPageMediaPath: String?,
        statusBarImage: String?,
        statusBarImageDark: String?,
        floatingIcon: String?,
        multiWebSiteGalleryItems: List<com.sherwood.wta.data.model.GalleryItem> = emptyList(),
        multiWebSiteMediaPaths: List<String> = emptyList(),
        cancellation: CoroutineContext = EmptyCoroutineContext
    ): String {
        val parts = mutableListOf<String>()
        parts += "configJson=${ApkConfigJsonFactory.create(config)}"
        // Hashing a project tree reads every file under it; for a Node/Python project this
        // is the slowest thing planning does, so a cancel must be able to break in here.
        projectDirs.filterNotNull().forEach { dir ->
            cancellation.ensureActive()
            parts += "dir=${dir.absolutePath}|${treeFingerprint(dir)}"
            cancellation.ensureActive()
        }
        parts += "media=${fileFingerprint(mediaContentPath)}"
        parts += "splash=${fileFingerprint(splashMediaPath)}"
        parts += "errorPage=${fileFingerprint(errorPageMediaPath)}"
        parts += "statusBar=${fileFingerprint(statusBarImage)}"
        parts += "statusBarDark=${fileFingerprint(statusBarImageDark)}"
        parts += "floatingIcon=${fileFingerprint(floatingIcon)}"
        bgmPlaylistPaths.forEachIndexed { index, path ->
            parts += "bgm[$index]=${fileFingerprint(path)}"
        }
        htmlFiles.forEachIndexed { index, file ->
            parts += "html[$index]=${file.name}|${fileFingerprint(file.path)}"
        }
        galleryItems.forEachIndexed { index, item ->
            parts += "gallery[$index]=${item.path}|${fileFingerprint(item.path)}|thumb=${fileFingerprint(item.thumbnailPath)}"
        }
        multiWebSiteGalleryItems.forEachIndexed { index, item ->
            parts += "mwGallery[$index]=${item.path}|${fileFingerprint(item.path)}|thumb=${fileFingerprint(item.thumbnailPath)}"
        }
        multiWebSiteMediaPaths.forEachIndexed { index, path ->
            parts += "mwMedia[$index]=${fileFingerprint(path)}"
        }
        return sha256(parts.joinToString("\n"))
    }

    private fun fileFingerprint(path: String?): String {
        if (path.isNullOrBlank()) return "none"
        val file = File(path)
        if (!file.isFile) return "missing"
        return "sha256=${fileSha256(file)}"
    }

    internal fun treeFingerprint(dir: File): String {
        if (!dir.isDirectory) return "missing"
        val digest = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown()
            .filter { it.isFile }
            // .git is excluded by every embed config (RuntimeAssetEmbedder) so it
            // can never affect the APK — but it can be thousands of files. Skip it
            // here to avoid hashing the entire git history on every build.
            .filter { file ->
                !file.relativeTo(dir).invariantSeparatorsPath
                    .split('/').contains(".git")
            }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            .forEach { file ->
                val relativePath = file.relativeTo(dir).invariantSeparatorsPath
                digest.update(relativePath.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(fileSha256(file).toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        return digest.digest().toHexString()
    }

    internal fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).toHexString()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
