package com.sherwood.wta.core.agent.autoprompt

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Auto Prompt screen renders: the master switch plus the saved prompts in order. */
data class AutoPromptState(
    val masterEnabled: Boolean = true,
    val prompts: List<AutoPrompt> = emptyList()
)

/**
 * Persistence for Auto Prompt.
 *
 * The whole feature lives in one small JSON document: the master switch, whether the
 * starter prompts were created, and the ordered list. A prompt is edited as a unit, so a
 * document is the natural shape — and unlike a per-field preference, a half-applied edit
 * cannot leave an enabled prompt with a blank body.
 *
 * Two rules this store enforces:
 *
 *  - **Turning the feature off never deletes anything.** [setMasterEnabled] rewrites one
 *    boolean; the prompts array is carried through untouched.
 *  - **An unreadable document is never overwritten blindly.** A corrupt document is
 *    preserved under a backup key and every mutation refuses until the user's data can be
 *    read again, so a transient parse failure cannot silently erase a user's prompts.
 *
 * Writes use `commit()` so a save that reports success is on disk before the UI says so;
 * the calls run on [Dispatchers.IO] because that is a synchronous disk write.
 */
class AutoPromptStore internal constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()

    suspend fun state(): AutoPromptState = withContext(Dispatchers.IO) {
        synchronized(lock) { readStateLocked() }
    }

    /**
     * Creates the starter prompts the first time the feature is opened.
     *
     * "First time" means the document has never been written — not "the list is empty".
     * A user who deletes every prompt gets an empty list back, not a re-seeded one.
     * [defaults] is supplied by the UI so the starter text follows the app language.
     */
    suspend fun seedIfFirstRun(defaults: List<AutoPrompt>): AutoPromptState =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (prefs.contains(KEY_DOCUMENT)) return@withContext readStateLocked()
                val seeded = defaults.map { it.normalized().sanitized() }
                writeDocumentLocked(Document(masterEnabled = true, seeded = true, prompts = seeded))
                AutoPromptState(masterEnabled = true, prompts = seeded)
            }
        }

    suspend fun setMasterEnabled(enabled: Boolean): Result<AutoPromptState> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val current = readDocumentLocked(true)
                    ?: return@withContext Result.failure(corruptDocument())
                val next = current.copy(masterEnabled = enabled)
                if (!writeDocumentLocked(next)) {
                    return@withContext Result.failure(IllegalStateException("Could not save Auto Prompt settings"))
                }
                Result.success(stateOf(next))
            }
        }

    suspend fun setPromptEnabled(id: String, enabled: Boolean): Result<AutoPromptState> =
        update { prompts ->
            prompts.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }

    /** Inserts a new prompt or replaces the existing one in place, keeping list order. */
    suspend fun save(prompt: AutoPrompt): Result<AutoPromptState> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val clean = prompt.normalized()
            clean.validationError()?.let { error ->
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid Auto Prompt: ${error.name}")
                )
            }
            val current = readDocumentLocked(true)
                ?: return@withContext Result.failure(corruptDocument())
            val existingIndex = current.prompts.indexOfFirst { it.id == clean.id }
            val nextPrompts = if (existingIndex >= 0) {
                current.prompts.toMutableList().also { it[existingIndex] = clean }
            } else {
                current.prompts + clean
            }
            val next = current.copy(prompts = nextPrompts)
            if (!writeDocumentLocked(next)) {
                return@withContext Result.failure(IllegalStateException("Could not save the prompt"))
            }
            Result.success(stateOf(next))
        }
    }

    suspend fun delete(id: String): Result<AutoPromptState> = update { prompts ->
        prompts.filterNot { it.id == id }
    }

    /**
     * Copies a prompt (new id, disabled-by-default is *not* assumed — the copy keeps the
     * original's enabled state) directly after the source.
     *
     * [name] is supplied by the UI so the "copy" suffix follows the app language.
     */
    suspend fun duplicate(id: String, name: String): Result<AutoPromptState> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readDocumentLocked(true)
                ?: return@withContext Result.failure(corruptDocument())
            val index = current.prompts.indexOfFirst { it.id == id }
            if (index < 0) {
                return@withContext Result.failure(IllegalArgumentException("Unknown Auto Prompt: $id"))
            }
            val copy = current.prompts[index].copy(
                id = java.util.UUID.randomUUID().toString(),
                name = name.trim().ifBlank { current.prompts[index].name }
            )
            val nextPrompts = current.prompts.toMutableList().also { it.add(index + 1, copy) }
            val next = current.copy(prompts = nextPrompts)
            if (!writeDocumentLocked(next)) {
                return@withContext Result.failure(IllegalStateException("Could not duplicate the prompt"))
            }
            Result.success(stateOf(next))
        }
    }

    /** Moves a prompt to [toIndex], clamped into range. */
    suspend fun move(id: String, toIndex: Int): Result<AutoPromptState> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readDocumentLocked(true)
                ?: return@withContext Result.failure(corruptDocument())
            val from = current.prompts.indexOfFirst { it.id == id }
            if (from < 0) {
                return@withContext Result.failure(IllegalArgumentException("Unknown Auto Prompt: $id"))
            }
            val target = toIndex.coerceIn(0, current.prompts.lastIndex)
            if (target == from) return@withContext Result.success(stateOf(current))
            val nextPrompts = current.prompts.toMutableList()
            val moved = nextPrompts.removeAt(from)
            nextPrompts.add(target, moved)
            val next = current.copy(prompts = nextPrompts)
            if (!writeDocumentLocked(next)) {
                return@withContext Result.failure(IllegalStateException("Could not reorder prompts"))
            }
            Result.success(stateOf(next))
        }
    }

    suspend fun moveBy(id: String, offset: Int): Result<AutoPromptState> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readDocumentLocked(true)
                ?: return@withContext Result.failure(corruptDocument())
            val from = current.prompts.indexOfFirst { it.id == id }
            if (from < 0) {
                return@withContext Result.failure(IllegalArgumentException("Unknown Auto Prompt: $id"))
            }
            val target = (from + offset).coerceIn(0, current.prompts.lastIndex)
            if (target == from) return@withContext Result.success(stateOf(current))
            val nextPrompts = current.prompts.toMutableList()
            val moved = nextPrompts.removeAt(from)
            nextPrompts.add(target, moved)
            val next = current.copy(prompts = nextPrompts)
            if (!writeDocumentLocked(next)) {
                return@withContext Result.failure(IllegalStateException("Could not reorder prompts"))
            }
            Result.success(stateOf(next))
        }
    }

    private suspend fun update(
        transform: (List<AutoPrompt>) -> List<AutoPrompt>
    ): Result<AutoPromptState> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readDocumentLocked(true)
                ?: return@withContext Result.failure(corruptDocument())
            val next = current.copy(prompts = transform(current.prompts))
            if (!writeDocumentLocked(next)) {
                return@withContext Result.failure(IllegalStateException("Could not save Auto Prompt settings"))
            }
            Result.success(stateOf(next))
        }
    }

    // ---- document handling -------------------------------------------------

    private fun stateOf(document: Document): AutoPromptState = AutoPromptState(
        masterEnabled = document.masterEnabled,
        prompts = document.prompts
    )

    /**
     * Reads the document, optionally insisting on an existing, valid one.
     *
     * With [requireExisting] a missing document falls back to the defaults (nothing to
     * lose yet); an *unreadable* document returns null so callers can refuse to write.
     */
    private fun readDocumentLocked(requireExisting: Boolean = false): Document? {
        val raw = prefs.getString(KEY_DOCUMENT, null)
        if (raw == null) {
            return if (requireExisting) Document(masterEnabled = true, seeded = false) else null
        }
        val parsed = runCatching { parseDocument(raw) }.getOrElse { error ->
            AppLogger.w(TAG, "Stored Auto Prompt document could not be read: ${error.javaClass.simpleName}")
            preserveCorruptLocked(raw)
            return null
        }
        return parsed
    }

    private fun readStateLocked(): AutoPromptState {
        val raw = prefs.getString(KEY_DOCUMENT, null)
        if (raw == null) return AutoPromptState()
        val parsed = runCatching { parseDocument(raw) }.getOrElse { error ->
            AppLogger.w(TAG, "Stored Auto Prompt document could not be read: ${error.javaClass.simpleName}")
            preserveCorruptLocked(raw)
            return AutoPromptState()
        }
        return stateOf(parsed)
    }

    private fun parseDocument(raw: String): Document {
        val type = object : TypeToken<StoredDocument>() {}.type
        val stored: StoredDocument = gson.fromJson(raw, type)
            ?: throw IllegalStateException("Empty Auto Prompt document")
        // An explicit null array means a half-written document, not "no prompts".
        val rawPrompts = stored.prompts ?: throw IllegalStateException("Missing prompts array")
        val prompts = rawPrompts.map { it.sanitized() }
        return Document(
            masterEnabled = stored.masterEnabled ?: true,
            seeded = stored.seeded ?: true,
            prompts = prompts
        )
    }

    private fun preserveCorruptLocked(raw: String) {
        if (prefs.contains(KEY_CORRUPT_BACKUP)) return
        prefs.edit().putString(KEY_CORRUPT_BACKUP, raw).commit()
    }

    private fun writeDocumentLocked(document: Document): Boolean {
        val encoded = runCatching {
            gson.toJson(
                StoredDocument(
                    version = DOCUMENT_VERSION,
                    masterEnabled = document.masterEnabled,
                    seeded = document.seeded,
                    prompts = document.prompts
                )
            )
        }.getOrElse {
            AppLogger.e(TAG, "Could not encode Auto Prompt document", it)
            return false
        }
        return prefs.edit().putString(KEY_DOCUMENT, encoded).commit()
    }

    private fun corruptDocument(): IllegalStateException =
        IllegalStateException("Stored Auto Prompt document is unreadable; refusing to overwrite it")

    private data class Document(
        val masterEnabled: Boolean,
        val seeded: Boolean = true,
        val prompts: List<AutoPrompt> = emptyList()
    )

    /**
     * Storage shape. Every field is nullable because Gson bypasses Kotlin nullability;
     * [parseDocument] turns a valid document into [Document] and rejects anything that
     * cannot be trusted.
     */
    private data class StoredDocument(
        val version: Int? = null,
        val masterEnabled: Boolean? = null,
        val seeded: Boolean? = null,
        val prompts: List<AutoPrompt>? = null
    )

    companion object {
        private const val TAG = "AutoPromptStore"
        private const val PREFS = "sherwood_auto_prompts"
        private const val KEY_DOCUMENT = "auto_prompts_document"
        private const val KEY_CORRUPT_BACKUP = "auto_prompts_corrupt_backup"
        private const val DOCUMENT_VERSION = 1

        @Volatile
        private var INSTANCE: AutoPromptStore? = null

        fun getInstance(context: Context): AutoPromptStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutoPromptStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
