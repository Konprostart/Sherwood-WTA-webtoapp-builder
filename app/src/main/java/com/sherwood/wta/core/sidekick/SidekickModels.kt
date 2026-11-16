package com.sherwood.wta.core.sidekick

import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment

/**
 * One line of the Sidekick conversation.
 *
 * Deliberately smaller than the agent's [com.sherwood.wta.core.agent.session.AgentMessage]:
 * the Sidekick has no tools and no session persistence, so a role, text and the
 * attachments of that one question are the whole shape. Nothing here is written to disk —
 * the transcript lives in the view model and dies with the process, which is what "quick
 * consultation" should mean.
 *
 * [attachments] live on the turn rather than in a side table so that the retention rule
 * ("only the newest questions keep their images") can be applied at send time without any
 * bookkeeping drifting out of step with the transcript.
 */
data class SidekickTurn(
    val role: Role,
    val text: String,
    /** Set when the turn carries a provider/runtime failure instead of an answer. */
    val isError: Boolean = false,
    val attachments: List<SidekickAttachment> = emptyList()
) {
    enum class Role { USER, ASSISTANT }

    val images: List<SidekickImageAttachment>
        get() = attachments.filterIsInstance<SidekickImageAttachment>()

    val hasImages: Boolean get() = attachments.any { it is SidekickImageAttachment }
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
