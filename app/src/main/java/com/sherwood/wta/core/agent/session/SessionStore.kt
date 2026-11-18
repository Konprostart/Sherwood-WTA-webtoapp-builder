package com.sherwood.wta.core.agent.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sherwood.wta.core.agent.files.AtomicFiles
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.util.GsonProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File

internal val Context.aicodingStore: DataStore<Preferences> by preferencesDataStore(name = "aicoding_sessions_v1")

/**
 * Persistence for agent sessions.
 *
 * Chat history started life as one JSON string holding *every* session and *every*
 * message. Each throttled streaming draft rewrote that whole string — a turn in a long
 * session re-serialised the entire history several times a second. The first split moved
 * each session's history into its own DataStore key, which removed the cross-session
 * amplification but still rewrote one physical `Preferences` file per flush, and that
 * file grows with the number of sessions a user keeps.
 *
 * The store now separates the two concerns by where their data belongs:
 *
 *  - **DataStore holds the index only** — `sessions_v2`, metadata for every session
 *    (title, config, timestamps, plan slugs, pin state) with no messages. Rewriting it
 *    per turn is cheap because it is bounded by session *count*, never by history size.
 *  - **The filesystem holds the history** — `<session sandbox>/.session/messages.json`
 *    for committed messages and `drafts.json` for the in-flight assistant turn, both
 *    written with [AtomicFiles] (`tmp → fsync → rename`). A streaming flush writes one
 *    small file for one session and never touches another session's bytes.
 *
 * Readers see the same [AgentSession] shape as before: [sessionsFlow] merges the index,
 * the session's messages file and its drafts file. Files are cached by their
 * `(length, lastModified)` stamp, so an unchanged session costs one `stat`, never a read
 * or a parse — an emission is still O(sessions) cheap checks rather than O(sessions)
 * decodes.
 *
 * DataStore's `edit {}` serialises every mutation process-wide, and file writes happen
 * inside that transform, so the index and the files it points at cannot interleave.
 * A write that fails aborts the transform and leaves the previous state readable.
 *
 * Two migrations run on first access:
 *
 *  1. the legacy all-in-one `sessions_v1` blob is split into the index plus one message
 *     file per session;
 *  2. the per-session blobs written by 2.10.x (`messages_v2:*`, `draft_v2:*`) are moved
 *     out of DataStore into those same files and their keys are dropped.
 *
 * A blob that cannot be parsed is never deleted — the raw bytes are carried into the
 * file as-is (or the legacy key is left in place) rather than replaced with an empty
 * history.
 */
class SessionStore(
    context: Context,
    private val files: ProjectFileManager
) {

    private val appContext = context.applicationContext

    private val gson = GsonProvider.gson

    /** Fast path: after the first successful access the legacy keys are gone. */
    @Volatile
    private var migrated = false

    val sessionsFlow: Flow<List<AgentSession>> = flow {
        migrateLegacy()
        emitAll(appContext.aicodingStore.data.map { decodeAll(it) })
        // Decoding and merging a session list is CPU work; keeping it off the main
        // dispatcher is what makes a streaming flush (every 500 ms) cost the UI nothing.
    }.flowOn(Dispatchers.Default)

    val currentSessionIdFlow: Flow<String?> = appContext.aicodingStore.data.map { it[KEY_CURRENT] }

    suspend fun create(title: String = ""): AgentSession {
        val session = AgentSession(title = title)
        appContext.aicodingStore.edit { p ->
            migrate(p)
            val list = decodeIndex(p[KEY_INDEX]).toMutableList()
            list.add(0, session)
            p[KEY_INDEX] = encodeIndex(list)
            p[KEY_CURRENT] = session.id
        }

        files.getSessionRoot(session.id)
        return session
    }

    suspend fun setCurrent(id: String) {
        appContext.aicodingStore.edit { it[KEY_CURRENT] = id }
    }

    suspend fun get(id: String): AgentSession? = sessionsFlow.first().firstOrNull { it.id == id }

    suspend fun delete(id: String) {
        appContext.aicodingStore.edit { p ->
            migrate(p)
            val list = decodeIndex(p[KEY_INDEX]).filter { it.id != id }
            p[KEY_INDEX] = encodeIndex(list)
            p.remove(messagesKey(id))
            draftKeys(p, id).forEach { p.remove(it) }
            if (p[KEY_CURRENT] == id) p.remove(KEY_CURRENT)
        }
        // Removes the session sandbox, which is also where the history files live.
        files.deleteSession(id)
        forget(id)
    }

    suspend fun rename(id: String, newTitle: String) = mutate(id) {

        it.copy(title = newTitle, titleAutoGenerated = false)
    }

    suspend fun setAutoTitle(id: String, newTitle: String): AgentSession? = mutate(id) { s ->
        if (!s.titleAutoGenerated) s else s.copy(title = newTitle)
    }

    suspend fun pin(id: String, pinned: Boolean) = mutate(id) { it.copy(pinned = pinned) }

    suspend fun updateConfig(id: String, config: SessionConfig) = mutate(id) {
        it.copy(config = config)
    }

    suspend fun appendMessage(id: String, message: AgentMessage): AgentSession? {
        var captured: AgentSession? = null
        appContext.aicodingStore.edit { p ->
            migrate(p)
            val list = decodeIndex(p[KEY_INDEX]).toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@edit
            var s = list[idx]

            if (s.title.isBlank() && message.role == AgentMessage.Role.USER) {
                s = s.copy(title = message.content.take(40).trim())
            }
            val updated = s.copy(
                messages = loadMessages(id) + message,
                updatedAt = System.currentTimeMillis()
            )
            list[idx] = updated
            p[KEY_INDEX] = encodeIndex(list)
            storeMessages(id, updated.messages)
            // A draft that outlived its turn is abandoned once a new message is appended;
            // leaving it would re-show it above the new turn forever.
            storeDrafts(id, emptyList())
            captured = updated
        }
        return captured
    }

    suspend fun replaceMessages(id: String, messages: List<AgentMessage>): AgentSession? =
        mutate(id, clearDrafts = true) {
            it.copy(messages = messages)
        }

    /**
     * Insert or replace the trailing assistant [message] for a session, keyed by
     * [message.id]. Used by [com.sherwood.wta.core.agent.runtime.AgentService] to keep a
     * live "draft" of the in-flight turn so the output survives even if the UI is
     * destroyed mid-turn.
     *
     * The draft goes to the session's own `drafts.json`, so a streaming flush writes one
     * small file for that session — never the committed history and never another
     * session's data. Committed messages are only touched by [finalizeDraft].
     */
    suspend fun upsertAssistantDraft(id: String, message: AgentMessage): AgentSession? {
        var captured: AgentSession? = null
        appContext.aicodingStore.edit { p ->
            migrate(p)
            val list = decodeIndex(p[KEY_INDEX]).toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@edit
            val updated = list[idx].copy(updatedAt = System.currentTimeMillis())
            list[idx] = updated
            p[KEY_INDEX] = encodeIndex(list)

            val drafts = loadDrafts(id).filterNot { it.id == message.id } + message
            storeDrafts(id, drafts)
            captured = updated.copy(
                messages = mergeDrafts(loadMessages(id), drafts)
            )
        }
        return captured
    }

    /**
     * Finalize a draft: replace the trailing message whose id == [final.id] with
     * [final] (used when the turn ends), or append [final] if no draft with that id
     * exists. The draft is removed in the same write, so the finished message is
     * committed exactly once and never re-appears from a stale draft.
     */
    suspend fun finalizeDraft(id: String, final: AgentMessage): AgentSession? {
        var captured: AgentSession? = null
        appContext.aicodingStore.edit { p ->
            migrate(p)
            val list = decodeIndex(p[KEY_INDEX]).toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@edit
            val msgs = loadMessages(id).toMutableList()
            val draftIdx = msgs.indexOfLast { it.id == final.id }
            if (draftIdx >= 0) {
                msgs[draftIdx] = final
            } else {
                msgs += final
            }
            val updated = list[idx].copy(
                messages = msgs,
                updatedAt = System.currentTimeMillis()
            )
            list[idx] = updated
            p[KEY_INDEX] = encodeIndex(list)
            storeMessages(id, msgs)
            storeDrafts(id, loadDrafts(id).filterNot { it.id == final.id })
            captured = updated
        }
        return captured
    }

    /**
     * Remove the trailing draft message with [draftMessageId] if it is still present
     * and was never finalized. Used to clean up an aborted draft that produced no
     * usable output.
     */
    suspend fun dropDraft(sessionId: String, draftMessageId: String): AgentSession? {
        var captured: AgentSession? = null
        appContext.aicodingStore.edit { p ->
            migrate(p)
            val list = decodeIndex(p[KEY_INDEX]).toMutableList()
            val idx = list.indexOfFirst { it.id == sessionId }
            if (idx < 0) return@edit
            val drafts = loadDrafts(sessionId).filterNot { it.id == draftMessageId }
            storeDrafts(sessionId, drafts)
            captured = list[idx].copy(
                messages = mergeDrafts(loadMessages(sessionId), drafts)
            )
        }
        return captured
    }

    suspend fun truncateAt(id: String, messageId: String, keep: Boolean): AgentSession? =
        mutate(id, clearDrafts = true) { s ->
            val idx = s.messages.indexOfFirst { it.id == messageId }
            if (idx < 0) return@mutate s
            val end = if (keep) idx + 1 else idx
            s.copy(messages = s.messages.take(end))
        }

    suspend fun recordPlan(sessionId: String, planSlug: String) = mutate(sessionId) { s ->
        val newSlugs = (s.planSlugs + planSlug).distinct()
        s.copy(planSlugs = newSlugs)
    }

    suspend fun clearAll() {
        val all = sessionsFlow.first().map { it.id }
        all.forEach { files.deleteSession(it) }
        appContext.aicodingStore.edit { p ->
            p.asMap().keys
                .filter { key ->
                    // Every message blob and draft becomes unreachable once the index,
                    // which is the only way to name a session, is gone.
                    key.name.startsWith(MESSAGES_PREFIX) || key.name.startsWith(DRAFT_PREFIX)
                }
                .forEach { p.remove(stringPreferencesKey(it.name)) }
            p.remove(KEY_INDEX)
            p.remove(KEY_CURRENT)
            p.remove(KEY_LEGACY)
            p.remove(KEY_VERSION)
        }
        synchronized(messagesCache) { messagesCache.clear() }
        synchronized(draftCache) { draftCache.clear() }
        migrated = true
    }

    /**
     * Applies [block] to the full session (metadata plus committed messages) and writes
     * back the metadata index and that one session's messages file. No other session's
     * history is read or rewritten.
     */
    private suspend fun mutate(
        id: String,
        clearDrafts: Boolean = false,
        block: (AgentSession) -> AgentSession
    ): AgentSession? {
        var captured: AgentSession? = null
        appContext.aicodingStore.edit { p ->
            migrate(p)
            val list = decodeIndex(p[KEY_INDEX]).toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@edit
            val current = list[idx].copy(messages = loadMessages(id))
            val updated = block(current).copy(updatedAt = System.currentTimeMillis())
            list[idx] = updated
            p[KEY_INDEX] = encodeIndex(list)
            // Rename / pin / config edits leave the history untouched; rewriting an
            // identical file would only invalidate its stamp cache for nothing.
            if (updated.messages != current.messages) storeMessages(id, updated.messages)
            // Rewriting the committed history invalidates any in-flight draft: keeping
            // one would re-append a turn that the replacement deliberately removed.
            if (clearDrafts) storeDrafts(id, emptyList())
            captured = updated
        }
        return captured
    }

    // ------------------------------------------------------------ migration

    private suspend fun migrateLegacy() {
        if (migrated) return
        appContext.aicodingStore.edit { p -> migrate(p) }
        migrated = true
    }

    /**
     * Brings any older layout up to the current one: the all-in-one `sessions_v1` blob
     * becomes the index plus one message file per session, and the per-session blobs
     * written by 2.10.x leave DataStore for those same files.
     *
     * A legacy blob that cannot be parsed is deliberately left in place: replacing it
     * with an empty index would silently destroy every conversation. The next access
     * retries. For a session blob that is moved, the raw bytes — not a re-serialised
     * form — go to disk, and its key is only dropped once the file is durable, so an
     * unparsable blob survives as bytes even though it currently decodes to nothing.
     */
    private fun migrate(p: MutablePreferences) {
        // The index may only be missing while the version marker survives (a partial
        // restore), so it is part of the guard rather than assumed from the version.
        val settled = p[KEY_INDEX] != null &&
            p[KEY_VERSION] == STORE_VERSION &&
            !hasLegacyBlobs(p)
        if (settled) return

        if (p[KEY_INDEX] == null) {
            val legacy = p[KEY_LEGACY]
            if (legacy.isNullOrBlank()) {
                p[KEY_INDEX] = "[]"
            } else {
                val split = SessionMigration.split(legacy, gson) ?: return
                split.messages.forEach { (sessionId, json) -> p[messagesKey(sessionId)] = json }
                p[KEY_INDEX] = split.index
            }
        }

        if (!moveBlobsToFiles(p)) return
        p.remove(KEY_LEGACY)
        p[KEY_VERSION] = STORE_VERSION
    }

    /** True while any 2.10.x per-session blob still lives in DataStore. */
    private fun hasLegacyBlobs(p: Preferences): Boolean = p.asMap().keys.any { key ->
        key.name.startsWith(MESSAGES_PREFIX) || key.name.startsWith(DRAFT_PREFIX)
    }

    /**
     * Moves every `messages_v2:*` / `draft_v2:*` blob out of DataStore. Returns false as
     * soon as one write fails, leaving the remaining keys (and the version marker) alone
     * so the whole step runs again on the next access.
     */
    private fun moveBlobsToFiles(p: MutablePreferences): Boolean {
        val index = decodeIndex(p[KEY_INDEX])
        index.forEach { session ->
            val blob = p[messagesKey(session.id)]
            if (blob != null) {
                if (!writeBlobFile(messagesFile(session.id), blob)) return false
                p.remove(messagesKey(session.id))
                forget(session.id)
            }
            val draftPrefKeys = draftKeys(p, session.id)
            if (draftPrefKeys.isNotEmpty()) {
                val drafts = draftPrefKeys.mapNotNull { p[it] }
                if (!writeBlobFile(draftsFile(session.id), drafts.joinToString(",", "[", "]"))) {
                    return false
                }
                draftPrefKeys.forEach { p.remove(it) }
                forget(session.id)
            }
        }
        return true
    }

    private fun writeBlobFile(file: File, raw: String): Boolean = try {
        AtomicFiles.writeText(file, raw)
        true
    } catch (e: Exception) {
        AppLogger.w(TAG, "Session migration could not write ${file.name}: ${e.message}")
        false
    }

    // ------------------------------------------------------------ reading

    private fun decodeAll(p: Preferences): List<AgentSession> {
        val index = decodeIndex(p[KEY_INDEX])
        if (index.isEmpty()) return emptyList()
        return index.map { session ->
            session.copy(
                messages = mergeDrafts(loadMessages(session.id), loadDrafts(session.id))
            )
        }
    }

    /**
     * Live drafts overlay the committed history. A draft whose id matches a committed
     * message replaces it in place (that is what `finalizeDraft` guarantees), so the
     * timeline never shows the same turn twice.
     */
    private fun mergeDrafts(
        committed: List<AgentMessage>,
        drafts: List<AgentMessage>
    ): List<AgentMessage> {
        if (drafts.isEmpty()) return committed
        val merged = committed.toMutableList()
        drafts.forEach { draft ->
            val existing = merged.indexOfLast { it.id == draft.id }
            if (existing >= 0) merged[existing] = draft else merged += draft
        }
        return merged
    }

    private fun loadMessages(sessionId: String): List<AgentMessage> =
        readSessionFile(messagesCache, sessionId, messagesFile(sessionId))

    private fun loadDrafts(sessionId: String): List<AgentMessage> =
        readSessionFile(draftCache, sessionId, draftsFile(sessionId))

    /**
     * Reads one session file, reusing the cached decode while the file's
     * `(length, lastModified)` stamp is unchanged. A missing file is an empty history,
     * not an error: it is how a session with no messages (or a cleared draft) is stored.
     */
    private fun readSessionFile(
        cache: LinkedHashMap<String, CachedMessages>,
        sessionId: String,
        file: File
    ): List<AgentMessage> {
        if (!file.isFile) {
            synchronized(cache) { cache.remove(sessionId) }
            return emptyList()
        }
        val stamp = FileStamp(file.length(), file.lastModified())
        val hit = synchronized(cache) { cache[sessionId] }
        if (hit != null && hit.stamp == stamp) return hit.messages

        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val messages = SessionMigration.decodeMessages(text, gson)
        // A publish racing this read would otherwise cache the bytes we read under a
        // stamp that describes the newer file, pinning stale content until the next
        // write. Only cache when the file did not move under us.
        val after = FileStamp(file.length(), file.lastModified())
        if (after == stamp) {
            synchronized(cache) {
                putBounded(cache, sessionId, CachedMessages(stamp, messages), MAX_FILE_CACHE)
            }
        }
        return messages
    }

    private fun storeMessages(sessionId: String, messages: List<AgentMessage>) {
        val file = messagesFile(sessionId)
        if (messages.isEmpty()) {
            file.delete()
            synchronized(messagesCache) { messagesCache.remove(sessionId) }
        } else {
            AtomicFiles.writeText(file, encodeMessages(messages))
            remember(messagesCache, sessionId, file, messages)
        }
    }

    private fun storeDrafts(sessionId: String, drafts: List<AgentMessage>) {
        val file = draftsFile(sessionId)
        if (drafts.isEmpty()) {
            file.delete()
            synchronized(draftCache) { draftCache.remove(sessionId) }
        } else {
            // A session may hold several drafts only if an earlier one was never
            // finalized; keep the order they were produced.
            val ordered = drafts.sortedWith(compareBy<AgentMessage>({ it.timestamp }, { it.id }))
            AtomicFiles.writeText(file, gson.toJson(ordered))
            remember(draftCache, sessionId, file, ordered)
        }
    }

    /** Caches exactly what was written, so the next read costs one `stat`. */
    private fun remember(
        cache: LinkedHashMap<String, CachedMessages>,
        sessionId: String,
        file: File,
        messages: List<AgentMessage>
    ) {
        if (!file.isFile) {
            synchronized(cache) { cache.remove(sessionId) }
            return
        }
        val stamp = FileStamp(file.length(), file.lastModified())
        synchronized(cache) {
            putBounded(cache, sessionId, CachedMessages(stamp, messages), MAX_FILE_CACHE)
        }
    }

    private fun forget(sessionId: String) {
        synchronized(messagesCache) { messagesCache.remove(sessionId) }
        synchronized(draftCache) { draftCache.remove(sessionId) }
    }

    private fun decodeIndex(raw: String?): List<AgentSession> {
        if (raw.isNullOrBlank()) return emptyList()
        synchronized(indexCache) {
            indexCache[raw]?.let { return it }
            val decoded = SessionMigration.decodeIndex(raw, gson)
            putBounded(indexCache, raw, decoded, MAX_INDEX_CACHE)
            return decoded
        }
    }

    private fun encodeIndex(sessions: List<AgentSession>): String =
        gson.toJson(sessions.map { it.copy(messages = emptyList()) })

    private fun encodeMessages(messages: List<AgentMessage>): String = gson.toJson(messages)

    private fun messagesFile(sessionId: String): File =
        File(files.sessionStateDir(sessionId), MESSAGES_FILE)

    private fun draftsFile(sessionId: String): File =
        File(files.sessionStateDir(sessionId), DRAFTS_FILE)

    /** Bounded caches: a session whose file is no longer read costs nothing. */
    private val indexCache = LinkedHashMap<String, List<AgentSession>>()
    private val messagesCache = LinkedHashMap<String, CachedMessages>()
    private val draftCache = LinkedHashMap<String, CachedMessages>()

    private fun <K, V> putBounded(map: LinkedHashMap<K, V>, key: K, value: V, max: Int) {
        map[key] = value
        while (map.size > max) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
    }

    private fun messagesKey(sessionId: String) = stringPreferencesKey("$MESSAGES_PREFIX$sessionId")

    private fun draftKeys(p: Preferences, sessionId: String): List<Preferences.Key<String>> {
        val prefix = "$DRAFT_PREFIX$sessionId:"
        return p.asMap().keys
            .filter { it.name.startsWith(prefix) }
            .map { stringPreferencesKey(it.name) }
    }

    /** Test hook: exposes legacy-blob decoding (including normalisation) without DataStore. */
    fun decodeForTest(raw: String): List<AgentSession> = SessionMigration.decodeSessions(raw, gson)

    private data class FileStamp(val length: Long, val modifiedAt: Long)

    private data class CachedMessages(val stamp: FileStamp, val messages: List<AgentMessage>)

    companion object {
        private const val TAG = "SessionStore"

        private const val MAX_INDEX_CACHE = 8
        private const val MAX_FILE_CACHE = 32

        /** Layout of the on-disk history inside [ProjectFileManager.SESSION_STATE_DIR]. */
        const val MESSAGES_FILE = "messages.json"
        const val DRAFTS_FILE = "drafts.json"

        /** 3 = committed messages and drafts live in per-session files. */
        private const val STORE_VERSION = 3

        /** Keys written by 2.10.x; read only by the migration, never written again. */
        private const val MESSAGES_PREFIX = "messages_v2:"
        private const val DRAFT_PREFIX = "draft_v2:"

        private val KEY_INDEX = stringPreferencesKey("sessions_v2")
        private val KEY_LEGACY = stringPreferencesKey("sessions_v1")
        private val KEY_CURRENT = stringPreferencesKey("current_v1")
        private val KEY_VERSION = intPreferencesKey("sessions_version")
    }
}

/**
 * Legacy-blob handling kept free of DataStore so it can be unit-tested directly.
 *
 * Gson bypasses Kotlin constructor defaults, so fields absent from legacy (pre-Agent
 * rename) JSON deserialise as null even though the Kotlin properties are declared
 * non-null. Every session and message is normalised here so callers can rely on empty
 * lists instead of tripping NPEs deep in the UI/VM (issue #712).
 */
internal object SessionMigration {

    data class Split(val index: String, val messages: Map<String, String>)

    /** Splits a legacy blob, or null when it cannot be parsed (never destroy it then). */
    fun split(raw: String, gson: Gson = GsonProvider.gson): Split? {
        val sessions = decodeSessionsOrNull(raw, gson) ?: return null
        val index = gson.toJson(sessions.map { it.copy(messages = emptyList()) })
        val messages = sessions.associate { it.id to gson.toJson(it.messages) }
        return Split(index, messages)
    }

    fun decodeIndex(raw: String, gson: Gson = GsonProvider.gson): List<AgentSession> =
        decodeSessionsOrNull(raw, gson).orEmpty()

    fun decodeMessages(raw: String, gson: Gson = GsonProvider.gson): List<AgentMessage> =
        runCatching {
            val messages: List<AgentMessage> =
                gson.fromJson(raw, object : TypeToken<List<AgentMessage>>() {}.type)
            messages.orEmpty().map(::normalizeMessage)
        }.getOrElse { emptyList() }

    fun decodeSessions(raw: String, gson: Gson = GsonProvider.gson): List<AgentSession> =
        decodeSessionsOrNull(raw, gson).orEmpty()

    private fun decodeSessionsOrNull(raw: String, gson: Gson): List<AgentSession>? =
        runCatching {
            val sessions: List<AgentSession>? =
                gson.fromJson(raw, object : TypeToken<List<AgentSession>>() {}.type)
            sessions?.map(::normalize)
        }.getOrNull()

    private fun normalize(session: AgentSession): AgentSession {
        // Gson bypasses the constructor, so *any* missing field can arrive as null even
        // though the Kotlin type is non-null. A blob with no `config` used to crash the
        // whole decode; defaulting it keeps the rest of the history readable.
        val config = (session.config as SessionConfig?) ?: SessionConfig()
        return session.copy(
            config = config.copy(
                contextAppIds = config.contextAppIdsSafe,
                contextModuleIds = config.contextModuleIdsSafe,
                builtApks = config.builtApksSafe,
                customRules = config.customRules ?: emptyList()
            ),
            messages = session.messages.orEmpty().map(::normalizeMessage),
            planSlugs = session.planSlugs ?: emptyList()
        )
    }

    fun normalizeMessage(message: AgentMessage): AgentMessage = message.copy(
        userAttachments = message.userAttachmentsSafe,
        thinkingSegments = message.thinkingSegmentsSafe,
        toolCalls = message.toolCalls ?: emptyList(),
        producedFiles = message.producedFiles ?: emptyList(),
        attachments = message.attachments ?: emptyList(),
        mentionedFiles = message.mentionedFiles ?: emptyList()
    )
}
