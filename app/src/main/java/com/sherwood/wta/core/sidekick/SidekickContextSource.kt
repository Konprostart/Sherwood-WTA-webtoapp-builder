package com.sherwood.wta.core.sidekick

import android.content.Context
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.session.AgentMessage
import com.sherwood.wta.core.agent.session.SessionStore
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.flow.first

/**
 * Reads the tail of the session the main agent is currently working in.
 *
 * Read-only on purpose: the Sidekick must not be able to alter the agent's history, and
 * a second [SessionStore] instance is safe here because every write path goes through a
 * DataStore `edit` (serialised) and the legacy migration is a no-op once the v2 index
 * exists.
 *
 * Failures degrade to "no context" rather than failing the question — an answer without
 * the session tail is still useful, an exception in the panel is not.
 */
class SidekickContextSource(context: Context) {

    private val appContext = context.applicationContext
    private val store by lazy { SessionStore(appContext, ProjectFileManager(appContext)) }

    suspend fun snapshot(): SidekickContextSnapshot? {
        return try {
            val sessionId = store.currentSessionIdFlow.first() ?: return null
            val session = store.get(sessionId) ?: return null
            val messages = session.messages.takeLast(SidekickPrompt.MAX_CONTEXT_MESSAGES)
            val lastError = lastErrorMessage(session.messages)

            val digest = SidekickPrompt.digest(
                title = session.title.orEmpty(),
                messages = messages,
                lastError = lastError
            )
            if (digest.isBlank()) return null

            SidekickContextSnapshot(
                title = session.title.orEmpty(),
                text = digest,
                lastError = lastError
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AppLogger.w(TAG, "Sidekick context snapshot failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * The newest message the main agent flagged as an error, or the newest assistant
     * message that looks like a provider failure. Both are what the user means by
     * "explain this error".
     */
    private fun lastErrorMessage(messages: List<AgentMessage>): String? {
        val flagged = messages.lastOrNull { it.isError }
        if (flagged != null) {
            @Suppress("USELESS_CAST")
            val body = (flagged.content as String?) ?: ""
            if (body.isNotBlank()) return body
        }
        val assistant = messages.lastOrNull { it.role == AgentMessage.Role.ASSISTANT }
        @Suppress("USELESS_CAST")
        val body = (assistant?.content as String?) ?: ""
        return body.takeIf { it.isNotBlank() && looksLikeFailure(it) }
    }

    private fun looksLikeFailure(text: String): Boolean {
        val head = text.take(400).lowercase()
        return head.contains("error") ||
            head.contains("failed") ||
            head.contains("exception") ||
            head.contains("cannot") ||
            head.contains("unable to")
    }

    companion object {
        private const val TAG = "SidekickContext"
    }
}
