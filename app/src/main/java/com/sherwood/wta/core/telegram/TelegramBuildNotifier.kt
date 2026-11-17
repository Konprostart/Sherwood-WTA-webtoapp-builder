package com.sherwood.wta.core.telegram

import com.sherwood.wta.core.studio.git.GitPipelineRun
import java.util.Collections

/**
 * Deduplicates "this run finished" announcements.
 *
 * Two sources can report the same build: `BuildStudio` publishes a [BuildEvents] event
 * for a dispatch the app started, and the harness's CI watcher notices the same run on
 * its next sweep. Without a shared ledger the chat would get two messages for one build.
 */
object TelegramBuildNotifier {

    /** Bounded so a long-running bot cannot grow this without limit. */
    private const val MAX_TRACKED = 200

    private val notified: MutableSet<Long> =
        Collections.synchronizedSet(LinkedHashSet<Long>())

    /** Returns true exactly once per run id. */
    fun markNotified(runId: Long): Boolean {
        synchronized(notified) {
            if (!notified.add(runId)) return false
            if (notified.size > MAX_TRACKED) {
                val iterator = notified.iterator()
                repeat(notified.size - MAX_TRACKED) {
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            return true
        }
    }

    fun resetForTest() = synchronized(notified) { notified.clear() }
}

/**
 * Decides which runs the CI watcher should announce.
 *
 * Only a run that was observed as *non-terminal* on an earlier sweep and is terminal now
 * is announced. Runs that were already finished when the bot first looked are recorded
 * silently: otherwise enabling the bot on a busy repository would immediately push a
 * backlog of old builds.
 */
object TelegramRunWatch {

    /**
     * Returns the runs that finished since [previous], and the state map to keep for the
     * next sweep. A run missing from [current] is dropped from the map (it fell out of
     * the page window).
     */
    fun sweep(
        previous: Map<Long, String>,
        current: List<GitPipelineRun>
    ): Pair<List<GitPipelineRun>, Map<Long, String>> {
        val finished = mutableListOf<GitPipelineRun>()
        val next = LinkedHashMap<Long, String>(current.size)
        current.forEach { run ->
            val state = stateOf(run)
            next[run.id] = state
            val before = previous[run.id] ?: return@forEach
            if (!GitPipelineRun.isTerminalStatus(before) && run.isTerminal) finished += run
        }
        return finished to next
    }

    private fun stateOf(run: GitPipelineRun): String =
        (run.conclusion ?: run.status).lowercase()
}
