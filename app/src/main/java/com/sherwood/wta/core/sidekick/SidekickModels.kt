package com.sherwood.wta.core.sidekick

/**
 * One line of the Sidekick conversation.
 *
 * Deliberately smaller than the agent's [com.sherwood.wta.core.agent.session.AgentMessage]:
 * the Sidekick has no tools, no attachments and no session persistence, so a role plus
 * text is the whole shape. Nothing here is written to disk — the transcript lives in the
 * view model and dies with the process, which is what "quick consultation" should mean.
 */
data class SidekickTurn(
    val role: Role,
    val text: String,
    /** Set when the turn carries a provider/runtime failure instead of an answer. */
    val isError: Boolean = false
) {
    enum class Role { USER, ASSISTANT }
}

/**
 * A read-only snapshot of the main agent session, used as consultable context.
 *
 * [text] is already size-bounded and rendered as plain lines; [lastError] lets "explain
 * this error" work without the user having to copy anything out of the chat.
 */
data class SidekickContextSnapshot(
    val title: String,
    val text: String,
    val lastError: String?
) {
    val isEmpty: Boolean get() = text.isBlank()
}
