package com.sherwood.wta.core.workspace

import android.content.Context
import android.net.Uri
import com.sherwood.wta.BuildConfig
import com.sherwood.wta.core.agent.files.AtomicFiles
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.session.AgentMessage
import com.sherwood.wta.core.agent.session.SessionConfig
import com.sherwood.wta.core.agent.session.SessionMigration
import com.sherwood.wta.core.agent.session.SessionStore
import com.sherwood.wta.core.backup.BackupEntryFingerprint
import com.sherwood.wta.core.backup.BackupIntegrity
import com.sherwood.wta.core.backup.BackupIntegrityException
import com.sherwood.wta.core.backup.BackupManifest
import com.sherwood.wta.core.backup.Sha256Sink
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.util.SafeZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable workspace bundles: one conversation plus the project files it produced.
 *
 * ```
 *   export   session record ─┐
 *            session sandbox ┴→ allowlist → zip(workspace.json, files/…, manifest.json)
 *   import   zip → stage → verify → new session + files   (never overwrites, never merges)
 * ```
 *
 * ## Why the security property holds structurally
 *
 * The exporter is handed exactly two things: a [SessionStore] record and
 * `ProjectFileManager.getSessionRoot(sessionId)`. There is no code path from here to
 * SharedPreferences, DataStore, the signing keystores in `filesDir` or `_rt_prot`, so
 * `GitCredentialStore`, `TelegramBotTokenStore` and `SidekickSpeechEndpointStore` are
 * unreachable by construction rather than by a deny rule someone has to maintain.
 * [WorkspaceAllowlist] then filters *inside* the sandbox, because the agent can write
 * anything there (a cloned `.git/config`, a `.env`, an `id_rsa`).
 *
 * ## Why import creates a new session
 *
 * Import never writes into an existing session and never merges histories: an arriving
 * bundle whose ids collide with the local ones would otherwise overwrite a
 * conversation, and a "merge" has no correct answer for two divergent turns. The
 * bundle therefore becomes a *new* session (fresh id, title preserved), and the only
 * compensation a failed import needs is deleting what it just created.
 *
 * Integrity reuses the backup archive's primitives ([BackupManifest], [BackupIntegrity],
 * [Sha256Sink]) instead of a second implementation: a truncated entry that keeps its
 * length is the same defect in both archive types, and one tested verifier is worth
 * more than two.
 */
internal class WorkspaceBundleManager(context: Context) {

    private val appContext = context.applicationContext

    private val files = ProjectFileManager(appContext)

    private val sessions = SessionStore(appContext, files)

    private val gson = com.sherwood.wta.data.converter.Converters.gson

    /** A workspace's project files, as written into the archive. */
    data class ExportResult(
        val sessionId: String,
        val title: String,
        val fileCount: Int,
        val totalBytes: Long,
        /** Paths the allowlist refused, with the reason. Never dropped silently. */
        val omitted: List<OmittedEntry>
    ) {
        val omittedCount: Int get() = omitted.size
    }

    data class OmittedEntry(val path: String, val reason: WorkspaceAllowlist.Refusal)

    data class ImportResult(
        val sessionId: String,
        val title: String,
        val fileCount: Int,
        val totalBytes: Long,
        val messageCount: Int,
        val exportedAt: Long,
        val bundleVersion: Int,
        val exportedByVersion: String
    )

    // ------------------------------------------------------------------ export

    /**
     * Writes [outputUri] as a `.sherwoodworkspace` archive. [sessionId] defaults to the
     * session the user currently has open.
     */
    suspend fun exportWorkspace(
        outputUri: Uri,
        sessionId: String? = null,
        onProgress: Progress = NoProgress
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openOutputStream(outputUri)?.use { out ->
                Result.success(exportToStream(sessionId, out, onProgress))
            } ?: Result.failure(IOException("Could not open the chosen file for writing"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Workspace export failed", e)
            Result.failure(e)
        }
    }

    /** Export body, stream-based so tests need no SAF and so the zip is never buffered. */
    suspend fun exportToStream(
        sessionId: String?,
        output: OutputStream,
        onProgress: Progress = NoProgress
    ): ExportResult {
        val context = currentCoroutineContext()
        onProgress(0, 100, WorkspaceText.readingSession)
        context.ensureActive()

        val id = sessionId ?: sessions.currentSessionIdFlow.first()
            ?: throw WorkspaceBundleException.NoCurrentSession()
        val session = sessions.get(id) ?: throw WorkspaceBundleException.NoSuchSession(id)

        val root = files.getSessionRoot(id)
        val omitted = mutableListOf<OmittedEntry>()
        val admitted = mutableListOf<Pair<String, File>>()
        var totalBytes = 0L

        // listAll already hides the store's own persistence (`.session`), the undo
        // journal (`.changes`) and abandoned temp files; the allowlist then refuses
        // everything that looks like a credential.
        files.listAll(id).forEach { info ->
            context.ensureActive()
            val verdict = WorkspaceAllowlist.admits(info.relativePath)
            if (!verdict.admitted) {
                omitted += OmittedEntry(info.relativePath, verdict.refusal ?: WorkspaceAllowlist.Refusal.UNSAFE_PATH)
                return@forEach
            }
            val file = File(root, info.relativePath)
            // A symlink resolves to a different canonical path; following one would
            // copy bytes from outside the sandbox into a shareable archive.
            if (!isConfined(file, root)) {
                omitted += OmittedEntry(info.relativePath, WorkspaceAllowlist.Refusal.NOT_REGULAR_FILE)
                return@forEach
            }
            if (info.sizeBytes > WorkspaceAllowlist.MAX_SINGLE_FILE_BYTES) {
                omitted += OmittedEntry(info.relativePath, WorkspaceAllowlist.Refusal.TOO_LARGE)
                return@forEach
            }
            if (totalBytes + info.sizeBytes > WorkspaceAllowlist.MAX_TOTAL_BYTES ||
                admitted.size >= WorkspaceAllowlist.MAX_FILES
            ) {
                omitted += OmittedEntry(info.relativePath, WorkspaceAllowlist.Refusal.TOO_LARGE)
                return@forEach
            }
            totalBytes += info.sizeBytes
            admitted += info.relativePath to file
        }

        onProgress(30, 100, WorkspaceText.collectingFiles)

        val document = WorkspaceDocument(
            format = WorkspaceBundle.FORMAT,
            bundleVersion = WorkspaceBundle.VERSION,
            exportedAt = System.currentTimeMillis(),
            exportedBy = WorkspaceOrigin(
                app = WorkspaceBundle.PRODUCT,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE
            ),
            session = WorkspaceSession(
                title = session.title,
                titleAutoGenerated = session.titleAutoGenerated,
                pinned = session.pinned,
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                planSlugs = session.planSlugs,
                config = session.config.forTransfer(),
                messages = session.messages
            )
        )

        val manifestEntries = mutableListOf<BackupEntryFingerprint>()
        val zipOut = ZipOutputStream(BufferedOutputStream(output, BUFFER_SIZE))
        try {
            fun writeEntry(name: String, body: (OutputStream) -> Unit) {
                zipOut.putNextEntry(ZipEntry(name))
                val sink = Sha256Sink(zipOut)
                try {
                    body(sink)
                    sink.flush()
                    manifestEntries += BackupEntryFingerprint(name, sink.size, sink.sha256())
                } finally {
                    runCatching { zipOut.closeEntry() }
                }
            }

            writeEntry(WorkspaceBundle.DOCUMENT_ENTRY) { sink ->
                sink.write(gson.toJson(document).toByteArray(Charsets.UTF_8))
            }

            admitted.forEachIndexed { index, (relativePath, file) ->
                context.ensureActive()
                onProgress(
                    30 + (index * 60 / admitted.size.coerceAtLeast(1)),
                    100,
                    WorkspaceText.packingFile(relativePath)
                )
                writeEntry(WorkspaceBundle.FILES_DIR + relativePath) { sink ->
                    FileInputStream(file).buffered(BUFFER_SIZE).use { input ->
                        input.copyTo(sink, BUFFER_SIZE)
                    }
                }
            }

            // Last, because the digests are only all known once every other entry is
            // written. Import therefore stages the whole archive before it verifies it.
            zipOut.putNextEntry(ZipEntry(WorkspaceBundle.MANIFEST_ENTRY))
            zipOut.write(
                BackupManifest(
                    backupVersion = WorkspaceBundle.VERSION,
                    createdAt = document.exportedAt,
                    entries = manifestEntries
                ).toJson().toByteArray(Charsets.UTF_8)
            )
            zipOut.closeEntry()
            zipOut.finish()
            zipOut.flush()
        } finally {
            // Not closed: the caller owns [output]. A prefix left behind by a failure
            // is unusable anyway — the manifest never made it.
            runCatching { zipOut.finish() }
        }

        onProgress(100, 100, WorkspaceText.exportComplete)
        AppLogger.i(
            TAG,
            "Exported workspace $id (${admitted.size} files, ${omitted.size} omitted)"
        )
        return ExportResult(
            sessionId = id,
            title = session.title,
            fileCount = admitted.size,
            totalBytes = totalBytes,
            omitted = omitted
        )
    }

    // ------------------------------------------------------------------ import

    /** Imports [inputUri] as a new session. Nothing local is modified on failure. */
    suspend fun importWorkspace(
        inputUri: Uri,
        onProgress: Progress = NoProgress
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openInputStream(inputUri)?.use { input ->
                Result.success(importFromStream(input, onProgress))
            } ?: Result.failure(IOException("Could not open the chosen file for reading"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Workspace import failed", e)
            Result.failure(e)
        }
    }

    /**
     * ```
     *  1. stage    every entry into a private cache directory (no live write)
     *  2. verify   manifest + sha256 of every staged entry, allowlist of every path
     *  3. create   a NEW session, then its files, then config/history
     *  4. failure  delete the session it just created
     * ```
     */
    suspend fun importFromStream(
        input: InputStream,
        onProgress: Progress = NoProgress
    ): ImportResult {
        val context = currentCoroutineContext()
        onProgress(0, 100, WorkspaceText.checkingBundle)
        context.ensureActive()

        val staging = File(appContext.cacheDir, "workspace_import_${UUID.randomUUID()}")
        try {
            var document: WorkspaceDocument? = null
            var manifest: BackupManifest? = null
            val observed = mutableListOf<BackupEntryFingerprint>()
            val staged = mutableListOf<Pair<String, File>>()
            var stagedBytes = 0L

            val guard = SafeZip.EntryGuard(
                maxEntries = WorkspaceAllowlist.MAX_FILES + 2,
                maxTotalBytes = WorkspaceAllowlist.MAX_TOTAL_BYTES
            )

            ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zipIn ->
                var entry = zipIn.nextEntry
                var index = 0
                while (entry != null) {
                    context.ensureActive()
                    index++
                    guard.onEntry()
                    onProgress(
                        (index % 40) + 5,
                        100,
                        WorkspaceText.readingBundle
                    )

                    when {
                        entry.name == WorkspaceBundle.DOCUMENT_ENTRY -> {
                            val bytes = readSmall(zipIn, guard, MAX_DOCUMENT_BYTES)
                            observed += fingerprint(WorkspaceBundle.DOCUMENT_ENTRY, bytes)
                            document = parseDocument(bytes)
                        }

                        entry.name == WorkspaceBundle.MANIFEST_ENTRY -> {
                            val bytes = readSmall(zipIn, guard, MAX_MANIFEST_BYTES)
                            manifest = BackupManifest.fromJson(String(bytes, Charsets.UTF_8))
                        }

                        entry.name.startsWith(WorkspaceBundle.FILES_DIR) && !entry.isDirectory -> {
                            val relativePath = entry.name.removePrefix(WorkspaceBundle.FILES_DIR)
                            val verdict = WorkspaceAllowlist.admits(relativePath)
                            if (!verdict.admitted) {
                                throw WorkspaceBundleException.ForbiddenEntry(
                                    relativePath,
                                    verdict.refusal ?: WorkspaceAllowlist.Refusal.UNSAFE_PATH
                                )
                            }
                            val target = stagedTarget(staging, relativePath)
                                ?: throw WorkspaceBundleException.ForbiddenEntry(
                                    relativePath,
                                    WorkspaceAllowlist.Refusal.UNSAFE_PATH
                                )
                            val written = stageToFile(zipIn, guard, target)
                            stagedBytes += written.size
                            observed += BackupEntryFingerprint(entry.name, written.size, written.sha256)
                            staged += relativePath to target
                        }

                        // Anything else is ignored (and never written): if the manifest
                        // declares it, verification fails; if not, it is inert.
                        else -> Unit
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            val parsed = document ?: throw WorkspaceBundleException.MissingDocument()
            if (parsed.format != WorkspaceBundle.FORMAT) {
                throw WorkspaceBundleException.UnsupportedFormat(parsed.format)
            }
            if (parsed.bundleVersion > WorkspaceBundle.VERSION || parsed.bundleVersion <= 0) {
                throw WorkspaceBundleException.UnsupportedVersion(parsed.bundleVersion)
            }

            // Gson bypasses Kotlin constructor defaults, so a hand-edited document can
            // deliver null for a field the model declares non-null. Every field that the
            // session is built from is read through the nullable form — the same guard the
            // session store applies to legacy blobs (issue #712).
            @Suppress("USELESS_CAST")
            val sessionDoc = (parsed.session as WorkspaceSession?)
                ?: throw WorkspaceBundleException.MalformedDocument()
            @Suppress("USELESS_CAST")
            val rawTitle = (sessionDoc.title as String?) ?: ""
            @Suppress("USELESS_CAST")
            val rawMessages = (sessionDoc.messages as List<AgentMessage>?) ?: emptyList()
            @Suppress("USELESS_CAST")
            val rawPlanSlugs = (sessionDoc.planSlugs as List<String>?) ?: emptyList()
            @Suppress("USELESS_CAST")
            val rawOrigin = (parsed.exportedBy as WorkspaceOrigin?)

            // Phase 2: prove the archive before a single live byte is written.
            onProgress(45, 100, WorkspaceText.verifyingBundle)
            context.ensureActive()
            BackupIntegrity.verify(
                manifest = manifest,
                observed = observed,
                manifestRequired = true
            )

            // Phase 3: create. Every write past this point is compensated on failure.
            onProgress(55, 100, WorkspaceText.creatingSession)
            context.ensureActive()

            val title = rawTitle.trim().take(MAX_TITLE_CHARS)
            val messages = rawMessages.map(SessionMigration::normalizeMessage)
            // `create` makes the new session current. If the import then fails we put the
            // previous selection back, so a rejected archive leaves the user where they
            // were instead of with no session open.
            val previousCurrent = sessions.currentSessionIdFlow.first()
            val created = sessions.create(title = "")
            val newId = created.id
            try {
                staged.forEachIndexed { index, (relativePath, source) ->
                    context.ensureActive()
                    onProgress(
                        55 + (index * 40 / staged.size.coerceAtLeast(1)),
                        100,
                        WorkspaceText.restoringFile(relativePath)
                    )
                    val target = files.resolveSafe(newId, relativePath)
                        ?: throw WorkspaceBundleException.ForbiddenEntry(
                            relativePath,
                            WorkspaceAllowlist.Refusal.UNSAFE_PATH
                        )
                    // AtomicFiles rather than ProjectFileManager.writeStream: an import
                    // is not a user edit, and `snapshotBeforeWrite` would litter the undo
                    // journal with "did not exist" markers for every imported file.
                    FileInputStream(source).buffered(BUFFER_SIZE).use { bytes ->
                        AtomicFiles.writeStream(target, bytes)
                    }
                }

                @Suppress("USELESS_CAST")
                val rawConfig = (sessionDoc.config as SessionConfig?)
                sessions.updateConfig(newId, (rawConfig ?: SessionConfig()).sanitize())

                if (messages.isNotEmpty()) sessions.replaceMessages(newId, messages)
                if (title.isNotEmpty()) sessions.rename(newId, title)
                if (sessionDoc.pinned) sessions.pin(newId, pinned = true)
                rawPlanSlugs.filter { it.isNotBlank() }.forEach { slug ->
                    sessions.recordPlan(newId, slug)
                }

                onProgress(100, 100, WorkspaceText.importComplete)
                AppLogger.i(
                    TAG,
                    "Imported workspace as session $newId (${staged.size} files, ${messages.size} messages)"
                )
                return ImportResult(
                    sessionId = newId,
                    title = title,
                    fileCount = staged.size,
                    totalBytes = stagedBytes,
                    messageCount = messages.size,
                    exportedAt = parsed.exportedAt,
                    bundleVersion = parsed.bundleVersion,
                    exportedByVersion = rawOrigin?.versionName.orEmpty()
                )
            } catch (t: Throwable) {
                // Only what this import created is removed; nothing else was touched.
                runCatching { sessions.delete(newId) }
                    .onFailure { AppLogger.w(TAG, "Could not remove the partial imported session", it) }
                if (previousCurrent != null && previousCurrent != newId) {
                    runCatching { sessions.setCurrent(previousCurrent) }
                }
                throw t
            }
        } catch (e: WorkspaceBundleException) {
            throw e
        } catch (e: BackupIntegrityException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Workspace bundle could not be read", e)
            throw e
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /** Suggested file name for the SAF create-document dialog. */
    suspend fun suggestedFileName(sessionId: String? = null): String {
        val base = runCatching { sessionTitleOrNull(sessionId) }.getOrNull()
        val slug = (base ?: WorkspaceBundle.PRODUCT)
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
            .ifEmpty { "workspace" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "$slug-$stamp.${WorkspaceAllowlist.EXTENSION}"
    }

    private suspend fun sessionTitleOrNull(sessionId: String?): String? {
        val id = sessionId ?: sessions.currentSessionIdFlow.first() ?: return null
        return sessions.get(id)?.title?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- helpers

    private fun parseDocument(bytes: ByteArray): WorkspaceDocument =
        runCatching {
            gson.fromJson(String(bytes, Charsets.UTF_8), WorkspaceDocument::class.java)
        }.getOrNull() ?: throw WorkspaceBundleException.MalformedDocument()

    /**
     * True when [file] is a regular file whose canonical location is exactly where its
     * lexical path says it is.
     *
     * Comparing the *relative* paths rather than testing for a symlink is what keeps
     * this usable on API 24 (`java.nio.file` is 26+). A symlink — or a path that only
     * reaches inside the sandbox by traversing one — canonicalises to a different
     * relative name, so it is refused. Copying through it would put bytes from outside
     * the sandbox into a shareable archive.
     */
    private fun isConfined(file: File, root: File): Boolean = try {
        if (!file.isFile) return false
        val prefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        val canonical = file.canonicalPath
        if (!canonical.startsWith(prefix)) return false
        val canonicalRelative = canonical.removePrefix(prefix).replace(File.separatorChar, '/')
        val lexicalRelative = file.relativeTo(root).path.replace(File.separatorChar, '/')
        canonicalRelative == lexicalRelative
    } catch (e: Exception) {
        false
    }

    /**
     * Maps a bundle-relative path onto the staging directory. The path was already
     * validated by the allowlist; the canonical check is the second opinion that keeps a
     * crafted name from escaping the staging directory on any filesystem.
     */
    private fun stagedTarget(staging: File, relativePath: String): File? {
        val target = File(staging, relativePath)
        return try {
            val canonRoot = staging.canonicalPath + File.separator
            if (target.canonicalPath.startsWith(canonRoot)) {
                target.parentFile?.mkdirs()
                target
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readSmall(zipIn: ZipInputStream, guard: SafeZip.EntryGuard, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = zipIn.read(buffer)
            if (read <= 0) break
            guard.onBytes(read)
            if (out.size() + read > maxBytes) {
                throw WorkspaceBundleException.TooLarge("Bundle entry exceeds $maxBytes bytes")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun stageToFile(
        zipIn: ZipInputStream,
        guard: SafeZip.EntryGuard,
        target: File
    ): Digest = try {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = zipIn.read(buffer)
                if (read <= 0) break
                guard.onBytes(read)
                size += read
                if (size > WorkspaceAllowlist.MAX_SINGLE_FILE_BYTES) {
                    throw WorkspaceBundleException.TooLarge("Bundle entry exceeds the per-file limit")
                }
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
        }
        Digest(size, BackupIntegrity.toHex(digest.digest()))
    } catch (t: Throwable) {
        runCatching { target.delete() }
        throw t
    }

    private fun fingerprint(path: String, bytes: ByteArray): BackupEntryFingerprint =
        BackupEntryFingerprint(path, bytes.size.toLong(), BackupIntegrity.hex(bytes))

    private data class Digest(val size: Long, val sha256: String)

    /** Progress callback, matching [com.sherwood.wta.core.backup.DataBackupManager]. */
    fun interface Progress {
        fun onProgress(percent: Int, total: Int, message: String)
    }

    companion object {
        private const val TAG = "WorkspaceBundleManager"
        private const val BUFFER_SIZE = 8192
        private const val MAX_DOCUMENT_BYTES = 64 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        private const val MAX_TITLE_CHARS = 120

        val NoProgress = Progress { _, _, _ -> }
    }
}

/**
 * Bundle-level constants. Kept in one object so the document model, the exporter and the
 * importer cannot disagree about a name or a version.
 */
internal object WorkspaceBundle {

    /** `format` written into `workspace.json`. */
    const val FORMAT = "sherwood-workspace"

    /** Bundle schema. Bumped only for an incompatible layout. */
    const val VERSION = 1

    const val PRODUCT = "Sherwood Studio"

    const val DOCUMENT_ENTRY = "workspace.json"

    const val FILES_DIR = "files/"

    const val MANIFEST_ENTRY = "manifest.json"
}
