package com.sherwood.wta.core.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.session.AgentMessage
import com.sherwood.wta.core.agent.session.AgentSession
import com.sherwood.wta.core.agent.session.SessionConfig
import com.sherwood.wta.core.agent.session.SessionStore
import com.sherwood.wta.core.backup.BackupIntegrityException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * End-to-end behaviour of a workspace bundle: what it carries, what it *never* carries,
 * and what an import does with it.
 *
 * The secrets tests are the important ones. They plant real credential material in the
 * real locations the app uses (`sherwood_git_credentials`, `sherwood_telegram_credentials`,
 * `sherwood_sidekick_speech`, a signing keystore in `filesDir`, `_rt_prot`) and then
 * assert that none of it appears in the archive — not as an entry name and not as a byte
 * sequence anywhere in the file. A second test puts credential-shaped files *inside* the
 * session sandbox, where the agent could legitimately have written them, and checks they
 * are omitted and reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceBundleManagerTest {

    private lateinit var context: Context
    private lateinit var files: ProjectFileManager
    private lateinit var sessions: SessionStore
    private lateinit var manager: WorkspaceBundleManager

    private val secretValues = listOf(
        "ghp_SUPERSECRETGITTOKENVALUE0123456789",
        "123456789:AAF-TELEGRAM-BOT-TOKEN-VALUE-xyz",
        "sk-sidekick-whisper-endpoint-key-value",
        "keystore-password-9f8e7d6c",
        "rt-prot-integrity-evidence-token"
    )

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        files = ProjectFileManager(context)
        sessions = SessionStore(context, files)
        manager = WorkspaceBundleManager(context)
        // Robolectric shares one process-wide DataStore between tests.
        sessions.clearAll()
    }

    // ------------------------------------------------------------- fixtures

    private suspend fun newSessionWith(
        title: String,
        filesToCreate: Map<String, String>
    ): AgentSession {
        val session = sessions.create(title = title)
        filesToCreate.forEach { (path, content) -> files.writeText(session.id, path, content) }
        return session
    }

    /** Writes every credential the app owns, in the location the app uses for it. */
    private fun plantAllSecrets() {
        context.getSharedPreferences("sherwood_git_credentials", Context.MODE_PRIVATE)
            .edit()
            .putString("github|default", secretValues[0])
            .commit()
        context.getSharedPreferences("sherwood_telegram_credentials", Context.MODE_PRIVATE)
            .edit()
            .putString("bot_token", secretValues[1])
            .commit()
        context.getSharedPreferences("sherwood_sidekick_speech", Context.MODE_PRIVATE)
            .edit()
            .putString("token", secretValues[2])
            .commit()
        File(context.filesDir, "custom_keystore.p12").writeText(secretValues[3])
        File(context.filesDir, "custom_keystore_password.txt").writeText(secretValues[3])
        context.getSharedPreferences("_rt_prot", Context.MODE_PRIVATE)
            .edit()
            .putString("evidence", secretValues[4])
            .commit()
    }

    private suspend fun exportBytes(sessionId: String): ByteArray {
        val out = ByteArrayOutputStream()
        manager.exportToStream(sessionId, out)
        return out.toByteArray()
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun `export carries the conversation, its config and its project files`() = runBlocking<Unit> {
        val session = newSessionWith(
            title = "Portfolio site",
            filesToCreate = mapOf(
                "index.html" to "<h1>hi</h1>",
                "src/app.js" to "console.log(1)",
                ".gitignore" to "node_modules"
            )
        )
        sessions.appendMessage(
            session.id,
            AgentMessage(role = AgentMessage.Role.USER, content = "hi")
        )

        val result = manager.exportToStream(session.id, ByteArrayOutputStream())

        assertThat(result.fileCount).isEqualTo(3)
        assertThat(result.omitted).isEmpty()
        assertThat(result.title).isEqualTo("Portfolio site")

        val entries = zipEntries(exportBytes(session.id))
        assertThat(entries.keys).containsExactly(
            "workspace.json",
            "files/index.html",
            "files/src/app.js",
            "files/.gitignore",
            "manifest.json"
        )
        // The manifest is written last and vouches for every other entry.
        assertThat(entries.keys.last()).isEqualTo("manifest.json")
        assertThat(String(entries["manifest.json"]!!)).contains("\"files/index.html\"")
        // The conversation is inside the document, not in a second copy of the store.
        assertThat(String(entries["workspace.json"]!!).contains("\"hi\"")).isTrue()
    }

    @Test
    fun `import creates a new session with the same files, history and config`() = runBlocking<Unit> {
        val session = newSessionWith(
            title = "Portfolio site",
            filesToCreate = mapOf("index.html" to "<h1>hi</h1>", "src/app.js" to "console.log(1)")
        )
        sessions.updateConfig(
            session.id,
            SessionConfig(
                textModelId = "gpt-4o",
                temperature = 0.4f,
                maxTurns = 12,
                customRules = listOf("Prefer plain HTML"),
                // Device-local references must not survive the transfer.
                contextAppIds = listOf(7L, 9L)
            )
        )
        sessions.appendMessage(
            session.id,
            AgentMessage(role = AgentMessage.Role.USER, content = "build me a portfolio")
        )

        val imported = manager.importFromStream(ByteArrayInputStream(exportBytes(session.id)))

        assertThat(imported.sessionId).isNotEqualTo(session.id)
        assertThat(imported.title).isEqualTo("Portfolio site")
        assertThat(imported.fileCount).isEqualTo(2)
        assertThat(imported.messageCount).isEqualTo(1)

        val restored = sessions.get(imported.sessionId)!!
        assertThat(restored.title).isEqualTo("Portfolio site")
        assertThat(restored.messages.map { it.content }).containsExactly("build me a portfolio")
        assertThat(restored.config.textModelId).isEqualTo("gpt-4o")
        assertThat(restored.config.temperature).isWithin(0.001f).of(0.4f)
        assertThat(restored.config.customRules).containsExactly("Prefer plain HTML")
        // Row ids from the exporting device would address unrelated apps here.
        assertThat(restored.config.contextAppIds).isEmpty()
        assertThat(restored.config.builtApks).isEmpty()
        assertThat(files.readText(imported.sessionId, "index.html")).isEqualTo("<h1>hi</h1>")
        assertThat(files.readText(imported.sessionId, "src/app.js")).isEqualTo("console.log(1)")
    }

    @Test
    fun `import never overwrites the session it was exported from`() = runBlocking<Unit> {
        val session = newSessionWith("Keep me", mapOf("index.html" to "original"))
        val imported = manager.importFromStream(ByteArrayInputStream(exportBytes(session.id)))

        // Editing the imported copy must not touch the source session.
        files.writeText(imported.sessionId, "index.html", "changed")

        assertThat(sessions.get(session.id)!!.title).isEqualTo("Keep me")
        assertThat(files.readText(session.id, "index.html")).isEqualTo("original")
    }

    // --------------------------------------------------------- the allowlist

    @Test
    fun `app owned credentials never reach the archive`() = runBlocking<Unit> {
        plantAllSecrets()
        val session = newSessionWith("Work", mapOf("index.html" to "<h1>hi</h1>"))

        val bytes = exportBytes(session.id)
        val entries = zipEntries(bytes)

        // Not an entry...
        assertThat(entries.keys.any { it.contains("keystore") }).isFalse()
        assertThat(entries.keys.any { it.contains("credential") }).isFalse()

        // ...and not a single byte sequence anywhere in the archive.
        val raw = String(bytes, Charsets.ISO_8859_1)
        secretValues.forEach { secret ->
            assertThat(raw.contains(secret)).isFalse()
        }
    }

    @Test
    fun `credential looking files inside the sandbox are omitted and reported`() = runBlocking<Unit> {
        val session = newSessionWith(
            title = "Work",
            filesToCreate = mapOf(
                "index.html" to "<h1>hi</h1>",
                ".env" to "API_KEY=abc",
                ".env.local" to "API_KEY=def",
                "id_rsa" to "-----BEGIN PRIVATE KEY-----",
                "certs/server.pem" to "-----BEGIN CERTIFICATE-----",
                "notes/github_token.txt" to "ghp_whatever",
                ".git/config" to "[remote]\n url = https://user:tok@example.com/r.git",
                "node_modules/x/index.js" to "module.exports = 1"
            )
        )

        val result = manager.exportToStream(session.id, ByteArrayOutputStream())
        val entries = zipEntries(exportBytes(session.id))

        assertThat(entries.keys).containsExactly("workspace.json", "files/index.html", "manifest.json")
        assertThat(result.omitted.map { it.path }).containsExactly(
            ".env", ".env.local", "id_rsa", "certs/server.pem",
            "notes/github_token.txt", ".git/config", "node_modules/x/index.js"
        )
        assertThat(result.omitted.first { it.path == ".env" }.reason)
            .isEqualTo(WorkspaceAllowlist.Refusal.SECRET_NAME)
        assertThat(result.omitted.first { it.path == ".git/config" }.reason)
            .isEqualTo(WorkspaceAllowlist.Refusal.EXCLUDED_DIRECTORY)
    }

    @Test
    fun `the session store's own history file is not duplicated into the bundle`() = runBlocking<Unit> {
        val session = newSessionWith("Chat", mapOf("index.html" to "x"))
        sessions.appendMessage(
            session.id,
            AgentMessage(role = AgentMessage.Role.USER, content = "hello")
        )

        val entries = zipEntries(exportBytes(session.id))

        assertThat(entries.keys.any { it.contains(".session/") }).isFalse()
        assertThat(entries.keys.any { it.endsWith("messages.json") }).isFalse()
    }

    // ------------------------------------------------------------ integrity

    @Test
    fun `an archive with a tampered entry is refused before anything is written`() = runBlocking<Unit> {
        val session = newSessionWith("Chat", mapOf("index.html" to "original"))
        val corrupted = rewriteZip(exportBytes(session.id)) { entries ->
            entries["files/index.html"] = "tampered!!".toByteArray()
        }

        val failure = runCatching {
            manager.importFromStream(ByteArrayInputStream(corrupted))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupIntegrityException.Mismatch::class.java)
        // Nothing was created: the only session is still the one we exported.
        assertThat(sessions.sessionsFlow.first().map { it.id }).containsExactly(session.id)
        assertThat(sessions.get(session.id)).isNotNull()
    }

    @Test
    fun `a bundle that carries a forbidden path is refused`() = runBlocking<Unit> {
        val session = newSessionWith("Chat", mapOf("index.html" to "x"))
        val hostile = rewriteZip(exportBytes(session.id)) { entries ->
            entries["files/.env"] = "API_KEY=leak".toByteArray()
        }

        val failure = runCatching {
            manager.importFromStream(ByteArrayInputStream(hostile))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(WorkspaceBundleException.ForbiddenEntry::class.java)
        assertThat(sessions.sessionsFlow.first()).hasSize(1)
    }

    @Test
    fun `an archive without a manifest is refused`() = runBlocking<Unit> {
        val session = newSessionWith("Chat", mapOf("index.html" to "x"))
        val stripped = rewriteZip(exportBytes(session.id)) { entries ->
            entries.remove("manifest.json")
        }

        val failure = runCatching {
            manager.importFromStream(ByteArrayInputStream(stripped))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupIntegrityException.Missing::class.java)
    }

    @Test
    fun `an archive that is not a workspace bundle reports the format`() = runBlocking<Unit> {
        val notOurs = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("workspace.json"))
                zip.write("""{"format":"something-else","bundleVersion":1}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val failure = runCatching {
            manager.importFromStream(ByteArrayInputStream(notOurs))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(WorkspaceBundleException.UnsupportedFormat::class.java)
    }

    // -------------------------------------------------------------- helpers

    private fun rewriteZip(
        bytes: ByteArray,
        edit: (MutableMap<String, ByteArray>) -> Unit
    ): ByteArray {
        val entries = LinkedHashMap(zipEntries(bytes))
        edit(entries)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
