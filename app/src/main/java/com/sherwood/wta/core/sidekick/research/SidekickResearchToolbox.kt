package com.sherwood.wta.core.sidekick.research

import com.sherwood.wta.core.agent.llm.ToolDeclaration

/**
 * The read-only research capability as the answer loop sees it.
 *
 * An interface so the loop's rules — round cap, tool-call cap, always answering a tool call,
 * retrying an image rejection — can be tested without a network or a real search engine.
 * [SidekickResearch] is the production implementation.
 */
interface SidekickResearchToolbox {

    /** Declarations advertised to the model for this turn. Empty means "no tools". */
    val toolDeclarations: List<ToolDeclaration>

    /** True when [question] carries a link worth reading before the model is asked. */
    fun hasLinks(question: String): Boolean

    /**
     * Reads the links in [question].
     *
     * Returns `null` when there is nothing to read, so the caller can skip the progress
     * line instead of showing "reading…" for a question with no links.
     */
    suspend fun prefetch(question: String): String?

    /** Runs one tool call by name and returns the text handed back to the model. */
    suspend fun execute(name: String, argumentsJson: String): String
}
