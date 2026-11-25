package com.sherwood.wta.ui.components

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.agent.session.AgentMessage
import com.sherwood.wta.core.agent.session.RecordedToolCall
import com.sherwood.wta.core.agent.session.ThinkingSegmentData
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sidekick.SidekickTurn
import com.sherwood.wta.ui.agent.components.formatMessageForCopy
import org.junit.Test

/**
 * Pins what the inline copy buttons put on the clipboard, and when they should not be
 * offered at all.
 *
 * The composables are thin wrappers around [MessageCopyPayload]; these are the
 * decisions that actually change what the user ends up copying.
 */
class MessageCopyPayloadTest {

    // U+2063 = INVISIBLE SEPARATOR, the marker delimiter used in agent content.
    private val IS = "\u2063"

    private fun user(content: String) =
        AgentMessage(role = AgentMessage.Role.USER, content = content)

    @Test
    fun `user message copies its content verbatim`() {
        val message = user("napravi mi splash screen")
        assertThat(MessageCopyPayload.agent(message)).isEqualTo("napravi mi splash screen")
    }

    @Test
    fun `assistant copy strips inline markers`() {
        val message = AgentMessage(
            role = AgentMessage.Role.ASSISTANT,
            content = "Hello${IS}TC:call_1${IS}World"
        )
        assertThat(MessageCopyPayload.agent(message)).isEqualTo("HelloWorld")
    }

    @Test
    fun `assistant copy leaves out thinking and tool calls`() {
        val message = AgentMessage(
            role = AgentMessage.Role.ASSISTANT,
            content = "Final answer",
            thinking = "reasoning",
            thinkingSegments = listOf(ThinkingSegmentData(id = "th1", content = "reasoning")),
            toolCalls = listOf(
                RecordedToolCall(
                    toolCallId = "c1",
                    name = "build_apk",
                    argumentsJson = "{}",
                    resultPreview = "built",
                    ok = true
                )
            )
        )
        // The one-tap button copies the answer, not the transcript of how it was made.
        assertThat(MessageCopyPayload.agent(message)).isEqualTo("Final answer")
    }

    @Test
    fun `menu variant with details keeps thinking and tool calls`() {
        val message = AgentMessage(
            role = AgentMessage.Role.ASSISTANT,
            content = "Final answer",
            thinkingSegments = listOf(ThinkingSegmentData(id = "th1", content = "why")),
            toolCalls = listOf(
                RecordedToolCall(
                    toolCallId = "c1",
                    name = "build_apk",
                    argumentsJson = "{}",
                    resultPreview = "built",
                    ok = true
                )
            )
        )
        val detailed = formatMessageForCopy(message, includeDetails = true)
        assertThat(detailed).contains(Strings.agentCopyThinkingHeader)
        assertThat(detailed).contains("build_apk")
        assertThat(detailed).contains("Final answer")
    }

    @Test
    fun `copy never carries a stray invisible separator`() {
        // stripInlineMarkers only rewrites complete marker pairs, so a lone separator
        // survives it; the copy payload must still be clean.
        val message = AgentMessage(
            role = AgentMessage.Role.ASSISTANT,
            content = "Hello${IS}World"
        )
        assertThat(MessageCopyPayload.agent(message)).isEqualTo("HelloWorld")
    }

    @Test
    fun `whitespace only payloads are not copyable`() {
        assertThat(MessageCopyPayload.isCopyable("")).isFalse()
        assertThat(MessageCopyPayload.isCopyable("   ")).isFalse()
        assertThat(MessageCopyPayload.isCopyable("\n\t ")).isFalse()
    }

    @Test
    fun `real text is copyable`() {
        assertThat(MessageCopyPayload.isCopyable("hi")).isTrue()
    }

    @Test
    fun `attachment only user message has nothing to copy`() {
        // The bubble shows a "no output" placeholder for an empty turn; the clipboard
        // must not receive that placeholder as if it were the user's message.
        val payload = MessageCopyPayload.agent(user(""))
        assertThat(MessageCopyPayload.isCopyable(payload)).isFalse()
    }

    @Test
    fun `streaming copy keeps only the arrived prose`() {
        assertThat(MessageCopyPayload.streaming("Hello${IS}TC:call_1${IS}World")).isEqualTo("HelloWorld")
        assertThat(MessageCopyPayload.streaming("  partial answer  ")).isEqualTo("partial answer")
    }

    @Test
    fun `streaming copy is hidden before the first token`() {
        assertThat(MessageCopyPayload.isCopyable(MessageCopyPayload.streaming(""))).isFalse()
        assertThat(MessageCopyPayload.isCopyable(MessageCopyPayload.streaming("$IS$IS"))).isFalse()
    }

    @Test
    fun `sidekick copies the turn text as shown`() {
        val turn = SidekickTurn(role = SidekickTurn.Role.ASSISTANT, text = "**bold** answer")
        assertThat(MessageCopyPayload.sidekick(turn)).isEqualTo("**bold** answer")
    }

    @Test
    fun `sidekick pending answer offers no copy`() {
        val turn = SidekickTurn(role = SidekickTurn.Role.ASSISTANT, text = "")
        assertThat(MessageCopyPayload.isCopyable(MessageCopyPayload.sidekick(turn))).isFalse()
    }
}
