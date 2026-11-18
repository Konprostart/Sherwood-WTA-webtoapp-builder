package com.sherwood.wta.core.agent.session

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.sherwood.wta.core.agent.files.ProjectFileManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the session-storage split that this release introduces: the DataStore
 * `Preferences` blob holds session *metadata* only, while committed messages and live
 * drafts live in one file per session inside that session's sandbox.
 *
 * The reason this matters is write amplification. Before the split, every throttled
 * streaming flush (`upsertAssistantDraft`, ~every 500 ms) rewrote the history of every
 * session. The headline test asserts the invariant on bytes: a draft flush must leave
 * the committed-message file byte-identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionStoreFileLayoutTest {

    private lateinit var ctx: android.content.Context
    private lateinit var files: ProjectFileManager

    private val gson = com.sherwood.wta.util.GsonProvider.gson

    @Before
    fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        files = ProjectFileManager(ctx)
        // Robolectric shares one process-wide DataStore instance between tests, so an
        // explicit reset is what makes each test start from the same state.
        SessionStore(ctx, files).clearAll()
    }

    private fun newStore() = SessionStore(ctx, files)

    private fun stateDir(sessionId: String) = files.sessionStateDir(sessionId)

    private fun messagesFile(sessionId: String) = File(stateDir(sessionId), SessionStore.MESSAGES_FILE)

    private fun draftsFile(sessionId: String) = File(stateDir(sessionId), SessionStore.DRAFTS_FILE)

    /** The live DataStore content — the blob whose size this change is about. */
    private suspend fun prefsSnapshot(): Preferences = ctx.aicodingStore.data.first()

    /** Everything DataStore persists, flattened: the body of a message must not be here. */
    private suspend fun prefsText(): String =
        prefsSnapshot().asMap().entries.joinToString("\n") { "${it.key.name}=${it.value}" }

    private fun message(id: String, content: String, role: AgentMessage.Role = AgentMessage.Role.USER) =
        AgentMessage(id = id, role = role, content = content)

    @Test
    fun `committed messages live in the session file, not the preference blob`() = runBlocking {
        val store = newStore()
        val session = store.create("layout")
        store.appendMessage(session.id, message("m1", "UNIQUE_BODY_TOKEN_9c3f"))

        val file = messagesFile(session.id)
        assertThat(file.isFile).isTrue()
        val stored = JsonParser.parseString(file.readText()).asJsonArray
        assertThat(stored).hasSize(1)
        assertThat(stored[0].asJsonObject.get("content").asString)
            .isEqualTo("UNIQUE_BODY_TOKEN_9c3f")

        // The whole point of the split: the body is nowhere in DataStore, but the
        // session metadata still is.
        val prefs = prefsText()
        assertThat(prefs).doesNotContain("UNIQUE_BODY_TOKEN_9c3f")
        assertThat(prefs).contains("layout")

        store.delete(session.id)
    }

    @Test
    fun `a draft flush never rewrites the committed history`() = runBlocking {
        val store = newStore()
        val session = store.create("streaming")
        store.appendMessage(session.id, message("m1", "committed body"))

        val committedBefore = messagesFile(session.id).readBytes()

        // Simulates the throttled streaming flush: the draft grows on every tick.
        repeat(6) { tick ->
            store.upsertAssistantDraft(
                session.id,
                message("draft-1", "partial output ".repeat(tick + 1), AgentMessage.Role.ASSISTANT)
            )
        }

        assertThat(messagesFile(session.id).readBytes()).isEqualTo(committedBefore)
        val draft = JsonParser.parseString(draftsFile(session.id).readText()).asJsonArray
        assertThat(draft).hasSize(1)
        assertThat(draft[0].asJsonObject.get("id").asString).isEqualTo("draft-1")

        // The merged view still shows the draft after the committed turn.
        val merged = store.get(session.id)!!.messages
        assertThat(merged.map { it.id }).containsExactly("m1", "draft-1").inOrder()

        store.delete(session.id)
    }

    @Test
    fun `a draft flush does not touch another session's files`() = runBlocking {
        val store = newStore()
        val busy = store.create("busy")
        val quiet = store.create("quiet")
        store.appendMessage(quiet.id, message("q1", "quiet history"))

        val quietBytes = messagesFile(quiet.id).readBytes()
        val quietDraftsExistsBefore = draftsFile(quiet.id).exists()

        repeat(4) {
            store.upsertAssistantDraft(
                busy.id,
                message("d-$it", "x".repeat(it + 1), AgentMessage.Role.ASSISTANT)
            )
        }

        assertThat(messagesFile(quiet.id).readBytes()).isEqualTo(quietBytes)
        assertThat(draftsFile(quiet.id).exists()).isEqualTo(quietDraftsExistsBefore)

        store.delete(busy.id)
        store.delete(quiet.id)
    }

    @Test
    fun `finalizing a draft commits the file and clears the draft file`() = runBlocking {
        val store = newStore()
        val session = store.create("finalize")
        store.appendMessage(session.id, message("u1", "question"))
        store.upsertAssistantDraft(session.id, message("d1", "partial", AgentMessage.Role.ASSISTANT))
        store.finalizeDraft(session.id, message("d1", "complete", AgentMessage.Role.ASSISTANT))

        val committed = JsonParser.parseString(messagesFile(session.id).readText()).asJsonArray
        assertThat(committed.map { it.asJsonObject.get("content").asString })
            .containsExactly("question", "complete").inOrder()
        assertThat(draftsFile(session.id).exists()).isFalse()

        store.delete(session.id)
    }

    @Test
    fun `writes leave no temp file behind`() = runBlocking {
        val store = newStore()
        val session = store.create("temps")
        store.appendMessage(session.id, message("m1", "body"))
        store.upsertAssistantDraft(session.id, message("d1", "draft", AgentMessage.Role.ASSISTANT))
        store.finalizeDraft(session.id, message("d1", "final", AgentMessage.Role.ASSISTANT))
        files.writeText(session.id, "index.html", "<html></html>")
        files.writeBytes(session.id, "logo.bin", byteArrayOf(1, 2, 3))

        val strays = files.getSessionRoot(session.id).walkTopDown()
            .filter { it.isFile }
            .map { it.name }
            .filter { it.startsWith(".wta-tmp-") }
            .toList()
        assertThat(strays).isEmpty()

        store.delete(session.id)
    }

    @Test
    fun `the agent file view never lists session persistence`() = runBlocking {
        val store = newStore()
        val session = store.create("listing")
        store.appendMessage(session.id, message("m1", "body"))
        files.writeText(session.id, "index.html", "<html></html>")

        val listed = files.listAll(session.id).map { it.relativePath }
        assertThat(listed).contains("index.html")
        assertThat(listed.none { it.startsWith(".session/") }).isTrue()

        store.delete(session.id)
    }

    @Test
    fun `deleting a session removes its history files`() = runBlocking {
        val store = newStore()
        val session = store.create("doomed")
        store.appendMessage(session.id, message("m1", "body"))
        assertThat(messagesFile(session.id).isFile).isTrue()

        store.delete(session.id)

        assertThat(messagesFile(session.id).exists()).isFalse()
        assertThat(store.get(session.id)).isNull()
    }

    @Test
    fun `2_10_x preference blobs migrate into per-session files`() = runBlocking {
        val sessionId = "migrated-session"
        val index = gson.toJson(listOf(AgentSession(id = sessionId, title = "from 2.10.9")))
        val blob = gson.toJson(
            listOf(message("m1", "old body"), message("m2", "second"))
        )
        ctx.aicodingStore.edit { p ->
            p[stringPreferencesKey("sessions_v2")] = index
            p[stringPreferencesKey("messages_v2:$sessionId")] = blob
            p[stringPreferencesKey("draft_v2:$sessionId:dr")] =
                gson.toJson(message("dr", "live draft", AgentMessage.Role.ASSISTANT))
            p[stringPreferencesKey("current_v1")] = sessionId
        }

        val store = newStore()
        val sessions = store.sessionsFlow.first()

        val restored = sessions.first { it.id == sessionId }
        assertThat(restored.title).isEqualTo("from 2.10.9")
        assertThat(restored.messages.map { it.id }).containsExactly("m1", "m2", "dr").inOrder()

        // History lives on disk now; the preference keys that held it are gone.
        val committed = JsonParser.parseString(messagesFile(sessionId).readText()).asJsonArray
        assertThat(committed.map { it.asJsonObject.get("id").asString }).containsExactly("m1", "m2")
        assertThat(prefsText()).doesNotContain("old body")
        assertThat(prefsSnapshot()[stringPreferencesKey("messages_v2:$sessionId")]).isNull()
        assertThat(prefsSnapshot()[stringPreferencesKey("draft_v2:$sessionId:dr")]).isNull()

        store.delete(sessionId)
    }

    @Test
    fun `legacy v1 blob is split into per-session files`() = runBlocking {
        val legacy = gson.toJson(
            listOf(
                AgentSession(id = "legacy-a", title = "A", messages = listOf(message("a1", "alpha"))),
                AgentSession(id = "legacy-b", title = "B", messages = listOf(message("b1", "beta")))
            )
        )
        ctx.aicodingStore.edit { p -> p[stringPreferencesKey("sessions_v1")] = legacy }

        val store = newStore()
        val sessions = store.sessionsFlow.first()

        assertThat(sessions.map { it.id }).containsExactly("legacy-a", "legacy-b")
        assertThat(sessions.first { it.id == "legacy-a" }.messages.single().content).isEqualTo("alpha")
        assertThat(messagesFile("legacy-a").isFile).isTrue()
        assertThat(messagesFile("legacy-b").isFile).isTrue()
        assertThat(prefsSnapshot()[stringPreferencesKey("sessions_v1")]).isNull()

        store.delete("legacy-a")
        store.delete("legacy-b")
    }

    @Test
    fun `an unparsable legacy blob is preserved and never replaced`() = runBlocking {
        val broken = "{this is not session json"
        ctx.aicodingStore.edit { p -> p[stringPreferencesKey("sessions_v1")] = broken }

        // Reads as "no sessions" rather than crashing, but the bytes stay put.
        assertThat(newStore().sessionsFlow.first()).isEmpty()
        assertThat(prefsSnapshot()[stringPreferencesKey("sessions_v1")]).isEqualTo(broken)

        // A second access retries and still must not destroy it.
        assertThat(newStore().sessionsFlow.first()).isEmpty()
        assertThat(prefsSnapshot()[stringPreferencesKey("sessions_v1")]).isEqualTo(broken)
    }

    @Test
    fun `an unparsable session blob is carried to disk as bytes`() = runBlocking {
        val sessionId = "broken-blob"
        ctx.aicodingStore.edit { p ->
            p[stringPreferencesKey("sessions_v2")] =
                gson.toJson(listOf(AgentSession(id = sessionId, title = "broken")))
            p[stringPreferencesKey("messages_v2:$sessionId")] = "{not an array"
        }

        val store = newStore()
        val session = store.sessionsFlow.first().first { it.id == sessionId }

        // Undecodable, but the raw bytes survive the move instead of being dropped.
        assertThat(session.messages).isEmpty()
        assertThat(messagesFile(sessionId).readText()).isEqualTo("{not an array")
        assertThat(prefsSnapshot()[stringPreferencesKey("messages_v2:$sessionId")]).isNull()

        store.delete(sessionId)
    }

    @Test
    fun `two store instances see each other's writes`() = runBlocking {
        val writer = newStore()
        val reader = newStore()
        val session = writer.create("shared")
        writer.appendMessage(session.id, message("m1", "written by A"))

        assertThat(reader.get(session.id)!!.messages.map { it.id }).containsExactly("m1")

        reader.appendMessage(session.id, message("m2", "written by B", AgentMessage.Role.ASSISTANT))
        assertThat(writer.get(session.id)!!.messages.map { it.id })
            .containsExactly("m1", "m2").inOrder()

        writer.delete(session.id)
    }

    @Test
    fun `metadata-only edits leave the history file untouched`() = runBlocking {
        val store = newStore()
        val session = store.create("metadata")
        store.appendMessage(session.id, message("m1", "history"))

        val before = messagesFile(session.id).readBytes()
        store.rename(session.id, "renamed")
        store.pin(session.id, true)
        store.updateConfig(session.id, session.config.copy(temperature = 0.2f))

        assertThat(messagesFile(session.id).readBytes()).isEqualTo(before)
        val reloaded = store.get(session.id)!!
        assertThat(reloaded.title).isEqualTo("renamed")
        assertThat(reloaded.pinned).isTrue()
        assertThat(reloaded.messages.single().content).isEqualTo("history")

        store.delete(session.id)
    }
}
