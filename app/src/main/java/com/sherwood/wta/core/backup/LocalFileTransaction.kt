package com.sherwood.wta.core.backup

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sherwood.wta.core.agent.files.AtomicFiles
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.data.converter.Converters
import java.io.File
import java.io.IOException

/**
 * Two-phase commit for the local (non-database) half of a restore.
 *
 * A restore touches files the app is *currently running on* — settings, DataStore,
 * shared preferences, signing keys, extension state. Before this class those files
 * were overwritten one by one while the database import had not even started, so any
 * later failure left the install half-restored with no way back.
 *
 * The order here is the whole point:
 *
 * ```
 *  1. snapshot   copy every existing target into the rollback directory
 *  2. journal    persist the snapshot table (fsync + rename) BEFORE any live write
 *  3. publish    write each staged file atomically (tmp -> fsync -> rename)
 *  4. finish     success deletes the journal; failure replays it backwards
 * ```
 *
 * Because the journal is durable, a process death between 3 and 4 is recoverable:
 * [recoverInterrupted] replays it on the next import and puts the previous files
 * back. Without the journal the snapshots would be unreachable after a restart, and
 * "transaction" would only mean "rollback if the exception happens to be catchable".
 *
 * Targets are validated against the app's private roots on recovery, so a corrupted
 * journal cannot be used to write outside the sandbox.
 */
internal class LocalFileTransaction(
    private val context: Context,
    private val rollbackDir: File = defaultRollbackDir(context)
) {

    private class Record(
        val target: File,
        val zipPath: String,
        val backup: File?,
        val staged: File
    )

    private val records = mutableListOf<Record>()
    private val createdDirs = mutableListOf<File>()

    /** Number of entries whose bytes actually landed in place. */
    var committedEntries: Int = 0
        private set

    val anyCommitted: Boolean get() = committedEntries > 0

    /** True once [commit] has recorded a plan, i.e. a rollback has something to do. */
    val hasWork: Boolean get() = records.isNotEmpty()

    /**
     * Snapshots originals, journals the plan, then publishes every staged file.
     *
     * Throws on the first failure, leaving the caller to call [rollback]. The
     * exception is never swallowed: a restore that could not write must report it.
     */
    fun commit(entries: List<DataBackupManager.PendingLocalEntry>) {
        if (entries.isEmpty()) return

        // A leftover journal from an earlier crash must be resolved before this
        // commit overwrites the very files it describes.
        recoverInterrupted()

        rollbackDir.mkdirs()
        if (!rollbackDir.isDirectory) {
            throw IOException("Unable to create the restore rollback directory")
        }

        entries.forEachIndexed { index, entry ->
            val target = entry.targetFile
            // Recorded before any mkdirs, so the directory the commit creates is
            // known even though the write happens later.
            topmostMissingDir(target.parentFile)?.let { dir ->
                if (createdDirs.none { it == dir }) createdDirs.add(dir)
            }

            // Snapshot before publishing: the copy is the only way back once the
            // target has been replaced.
            val backup = if (target.isFile) {
                File(rollbackDir, "$ORIGINAL_PREFIX$index").also { copy ->
                    target.inputStream().use { input -> AtomicFiles.writeStream(copy, input) }
                }
            } else null

            records.add(Record(target, entry.zipPath, backup, entry.tempFile))
        }

        // Durable plan before the first live byte: a crash after this point is
        // recoverable, a crash before it changes nothing.
        writeJournal()

        records.forEach { record ->
            record.target.parentFile?.mkdirs()
            record.staged.inputStream().use { AtomicFiles.writeStream(record.target, it) }
            runCatching { record.staged.delete() }
            committedEntries++
        }
    }

    /**
     * Restores every target from its snapshot, in reverse order, and removes files
     * and directories this commit created. Individual failures are logged and the
     * remaining entries are still restored — a partial rollback is better than an
     * abandoned one, and the caller reports the outcome.
     *
     * @return the number of targets that could not be restored; 0 means the previous
     *         state was brought back in full.
     */
    fun rollback(): Int {
        var failures = 0
        records.asReversed().forEach { record ->
            runCatching {
                val backup = record.backup
                if (backup != null && backup.isFile) {
                    backup.inputStream().use { AtomicFiles.writeStream(record.target, it) }
                } else {
                    record.target.delete()
                }
            }.onFailure { e ->
                failures++
                AppLogger.w(TAG, "回滚本地恢复失败: ${record.zipPath}", e)
            }
        }
        createdDirs.asReversed().forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
        cleanup()
        return failures
    }

    /** Deletes snapshots, journals and staged temporaries. Safe to call twice. */
    fun cleanup() {
        records.forEach { runCatching { it.staged.delete() } }
        records.clear()
        createdDirs.clear()
        runCatching { rollbackDir.deleteRecursively() }
    }

    /**
     * Replays a journal left behind by a process that died mid-commit.
     *
     * Returns true when a previous restore was actually undone, so the caller can
     * tell the user the install has been put back rather than silently guessing.
     */
    fun recoverInterrupted(): Boolean {
        if (!rollbackDir.isDirectory) return false
        val journal = readJournal(File(rollbackDir, JOURNAL_FILE))
        if (journal == null) {
            // Directory without a readable journal: nothing was published (the
            // journal is written first), so there is nothing to undo.
            runCatching { rollbackDir.deleteRecursively() }
            return false
        }

        var restored = false
        journal.entries.asReversed().forEach { entry ->
            val target = safeTarget(context, entry.target)
            if (target == null) {
                AppLogger.w(TAG, "跳过不安全的回滚路径: ${entry.target}")
                return@forEach
            }
            runCatching {
                val backup = entry.backup?.let { File(rollbackDir, it) }
                if (entry.existed) {
                    if (backup != null && backup.isFile) {
                        backup.inputStream().use { AtomicFiles.writeStream(target, it) }
                        restored = true
                    }
                } else if (target.exists()) {
                    target.delete()
                    restored = true
                }
            }.onFailure { e ->
                AppLogger.w(TAG, "恢复被中断的回滚失败: ${entry.target}", e)
            }
        }
        journal.createdDirs.asReversed().forEach { path ->
            safeTarget(context, path)?.let { dir -> runCatching { dir.deleteRecursively() } }
        }

        if (restored) AppLogger.i(TAG, "已将中断的恢复回滚到先前状态")
        runCatching { rollbackDir.deleteRecursively() }
        return restored
    }

    private fun writeJournal() {
        val root = JsonObject()
        root.addProperty("version", JOURNAL_VERSION)
        val entriesArray = JsonArray()
        records.forEach { record ->
            val item = JsonObject()
            item.addProperty("target", record.target.absolutePath)
            item.addProperty("backup", record.backup?.name)
            item.addProperty("existed", record.backup != null)
            entriesArray.add(item)
        }
        root.add("entries", entriesArray)
        val dirs = JsonArray()
        createdDirs.forEach { dirs.add(it.absolutePath) }
        root.add("createdDirs", dirs)

        // fsync + rename via the shared atomic writer: a torn journal is worse than
        // no journal, because recovery would act on a half-written plan.
        AtomicFiles.writeText(File(rollbackDir, JOURNAL_FILE), Converters.gson.toJson(root))
    }

    private class JournalEntry(val target: String, val backup: String?, val existed: Boolean)

    private class Journal(val entries: List<JournalEntry>, val createdDirs: List<String>)

    companion object {
        private const val TAG = "LocalFileTransaction"
        private const val JOURNAL_FILE = "restore_journal.json"
        private const val JOURNAL_VERSION = 1
        private const val ORIGINAL_PREFIX = "original-"

        fun defaultRollbackDir(context: Context): File = File(context.cacheDir, "backup_restore")

        private fun readJournal(file: File): Journal? {
            if (!file.isFile) return null
            return runCatching {
                val root = JsonParser.parseString(file.readText()).asJsonObject
                if ((root.get("version")?.asInt ?: 0) != JOURNAL_VERSION) return null
                val entries = root.getAsJsonArray("entries")?.mapNotNull { element ->
                    runCatching {
                        val item = element.asJsonObject
                        JournalEntry(
                            target = item.get("target").asString,
                            backup = item.get("backup")?.takeIf { !it.isJsonNull }?.asString,
                            existed = item.get("existed")?.asBoolean ?: true
                        )
                    }.getOrNull()
                } ?: emptyList()
                val dirs = root.getAsJsonArray("createdDirs")?.mapNotNull { element ->
                    runCatching { element.asString }.getOrNull()
                } ?: emptyList()
                Journal(entries, dirs)
            }.getOrElse { e ->
                AppLogger.w(TAG, "无法读取回滚日志", e)
                null
            }
        }

        /**
         * Confines a journal path to the app's private storage. The journal is ours,
         * but it is a file on disk: validating it keeps a corrupted or edited journal
         * from turning recovery into an arbitrary-write primitive.
         */
        private fun safeTarget(context: Context, path: String): File? {
            val roots = listOfNotNull(
                context.filesDir,
                File(context.applicationInfo.dataDir),
                context.getExternalFilesDir(null)
            )
            val targetCanonical = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
            val inside = roots.any { root ->
                runCatching {
                    targetCanonical.path.startsWith(root.canonicalFile.path + File.separator)
                }.getOrDefault(false)
            }
            return if (inside) targetCanonical else null
        }

        /** Highest ancestor of [dir] that does not exist yet, or null when it does. */
        private fun topmostMissingDir(dir: File?): File? {
            var missing: File? = null
            var current = dir
            while (current != null && !current.exists()) {
                missing = current
                current = current.parentFile
            }
            return missing
        }
    }
}
