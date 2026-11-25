package com.sherwood.wta.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.agent.session.AgentMessage
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sidekick.SidekickTurn
import com.sherwood.wta.ui.agent.AgentViewModel
import com.sherwood.wta.ui.agent.components.formatMessageForCopy
import com.sherwood.wta.ui.design.rememberHapticClick
import kotlinx.coroutines.delay

/**
 * What the inline copy button actually puts on the clipboard.
 *
 * The Workspace transcript and the Sidekick panel answer that question differently,
 * so the rule lives in one place instead of being retyped next to each bubble.
 */
internal object MessageCopyPayload {

    /**
     * Agent transcript: the settled prose of [message].
     *
     * Thinking blocks and tool calls are deliberately left out — the bubble's ⋯ menu
     * already offers "copy with details" for those, and the overwhelmingly common use
     * of a one-tap copy is moving the answer itself somewhere else.
     */
    fun agent(message: AgentMessage): String = sanitize(formatMessageForCopy(message, false))

    /** Sidekick transcript: the turn text exactly as the bubble renders it. */
    fun sidekick(turn: SidekickTurn): String = turn.text

    /**
     * Agent transcript while the answer is still arriving: the prose received so far,
     * with the invisible marker bookkeeping stripped so it never reaches the clipboard.
     */
    fun streaming(raw: String): String = sanitize(AgentViewModel.stripInlineMarkers(raw)).trim()

    /**
     * Marker stripping removes complete `\\u2063TC:…\\u2063` / `\\u2063TH:…\\u2063` pairs; a lone
     * separator (a truncated stream, or a message deserialised from older session JSON)
     * survives it. Nothing a user pastes should ever contain that character, so drop any
     * leftovers here rather than trusting every producer to be well formed.
     */
    private fun sanitize(text: String): String = text.replace(INVISIBLE_SEPARATOR.toString(), "")

    /**
     * A bubble with no text has nothing worth copying: an attachment-only user turn, or
     * an assistant turn whose first token has not arrived yet. The button is hidden
     * rather than shown copying an empty string.
     */
    fun isCopyable(payload: String): Boolean = payload.isNotBlank()
}

/** How long the copy glyph stays a check mark before reverting. */
internal const val MESSAGE_COPY_FEEDBACK_MILLIS = 1_800L

/** U+2063 INVISIBLE SEPARATOR — the delimiter for inline tool/thinking markers. */
private const val INVISIBLE_SEPARATOR = '\u2063'

private val CopyButtonSize: Dp = 30.dp
private val CopyGlyphSize: Dp = 16.dp

/**
 * One-tap "copy this bubble" affordance shared by the Workspace transcript and the
 * Sidekick panel.
 *
 * It sits under the bubble (never inside it) so it cannot collide with the composer's
 * speech/send controls or the message's ⋯ menu. [text] is a lambda because a streaming
 * answer grows while the button is already on screen — the tap must copy the text as it
 * is at that moment, not whatever it was when the row was first composed.
 *
 * The confirmation is the glyph flip itself: copy → check for
 * [MESSAGE_COPY_FEEDBACK_MILLIS]. Both strings already exist in all ten UI languages,
 * and the flip keeps the bubble's footprint unchanged.
 */
@Composable
fun MessageCopyButton(
    text: () -> String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val clipboard = LocalClipboardManager.current
    // Counts taps, so a second tap restarts the confirmation window instead of
    // inheriting the tail of the first one.
    var taps by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        copied = true
        delay(MESSAGE_COPY_FEEDBACK_MILLIS)
        copied = false
    }

    val onClick = rememberHapticClick {
        val payload = text()
        if (MessageCopyPayload.isCopyable(payload)) {
            clipboard.setText(AnnotatedString(payload))
            taps++
        }
    }

    IconButton(onClick = onClick, modifier = modifier.size(CopyButtonSize)) {
        Icon(
            imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
            contentDescription = if (copied) Strings.agentMessageCopied else Strings.copy,
            tint = if (copied) MaterialTheme.colorScheme.primary else tint,
            modifier = Modifier.size(CopyGlyphSize)
        )
    }
}
