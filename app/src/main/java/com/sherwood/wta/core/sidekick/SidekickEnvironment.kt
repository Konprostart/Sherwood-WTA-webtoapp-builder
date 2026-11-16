package com.sherwood.wta.core.sidekick

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.git.GitStudio
import android.net.Uri

/**
 * A compact, read-only description of this installation, prepended to the Sidekick's system
 * prompt.
 *
 * Why this exists: half of the useful questions ("which host am I on?", "why is publishing
 * greyed out?") are unanswerable from the conversation alone, and a model that has to guess
 * invents a provider. The block is deliberately tiny and contains no user content — hosting
 * provider, server host, whether a token is stored, whether a publish path exists.
 *
 * Everything here is a local read: [GitStudio.state] touches preferences and a presence
 * check, never the network and never the token material itself.
 */
object SidekickEnvironment {

    /** Whole-block ceiling. It is a paragraph of facts, not a report. */
    const val MAX_CHARS = 700

    /**
     * Renders the block, or `null` when there is nothing worth saying.
     *
     * Callers run this off the main thread: it reads preferences and the credential store.
     */
    fun snapshot(context: Context): String? = try {
        val studio = GitStudio.state(context.applicationContext)
        val host = hostOf(studio.baseUrl)
        val lines = buildList {
            add("## This installation (read-only facts)")
            add(
                buildString {
                    append("- Git hosting: ").append(studio.displayName)
                    if (host != null) append(" at ").append(host)
                    if (studio.baseUrl.isBlank()) append(" (no server URL configured)")
                }
            )
            add("- Access token stored: ${if (studio.hasToken) "yes" else "no"}")
            add("- Publishing (new branch + PR) from Studio: ${if (studio.canPublish) "available" else "not available"}")
            studio.unavailableReason?.let { reason ->
                add("- Connection problem: ${reason.name}")
            }
        }
        lines.joinToString("\n").take(MAX_CHARS)
    } catch (t: Throwable) {
        // A missing fact is better than a failed question.
        AppLogger.w(TAG, "Sidekick environment snapshot failed: ${t.javaClass.simpleName}")
        null
    }

    /**
     * Host only. The path of a self-hosted base URL can carry a company or project name that
     * has no business in a prompt; the host is what identifies the server.
     */
    private fun hostOf(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { Uri.parse(trimmed).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private const val TAG = "SidekickEnvironment"
}
