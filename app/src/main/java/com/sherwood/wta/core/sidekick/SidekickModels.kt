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
    val attachments: List<SidekickAttachment> = emptyList(),
    /**
     * True when this turn used to carry an image whose bytes were released to keep a long
     * conversation from holding every screenshot in memory. The prompt still tells the
     * model that an image was left out; the panel simply stops showing the thumbnail.
     */
    val imagesOmitted: Boolean = false
) {
    enum class Role { USER, ASSISTANT }

    val images: List<SidekickImageAttachment>
        get() = attachments.filterIsInstance<SidekickImageAttachment>()

    /** Whether this turn carries bytes an image block can be built from right now. */
    val hasImages: Boolean get() = attachments.any { it is SidekickImageAttachment }
}

/**
 * What one Sidekick answer emits while it runs.
 *
 * Typed rather than pre-rendered strings so the panel owns the wording (and its ten
 * languages) while the engine owns the behaviour.
 */
sealed interface SidekickStreamEvent {

    /** Answer text, appended in order. */
    data class Text(val delta: String) : SidekickStreamEvent

    /** A read-only step the user should see, so a slow turn does not look frozen. */
    data class Status(val kind: StatusKind) : SidekickStreamEvent {
        enum class StatusKind {
            /** A web search is running. */
            SEARCHING,

            /** A page or repository is being read. */
            READING_PAGE,

            /** The provider rejected the image; the same question is retried without it. */
            RETRY_TEXT_ONLY
        }
    }

    /** The provider failed. [message] is the provider's own text and may be English. */
    data class Failure(val message: String) : SidekickStreamEvent

    /** The stream went silent with nothing to show for it. */
    data object Stalled : SidekickStreamEvent

    /** The answer is complete (possibly partial, if the user stopped it). */
    data object Finished : SidekickStreamEvent
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
