package com.sherwood.wta.ui.agent

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.sidekick.input.SidekickVoiceLanguage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composer gating while dictating.
 *
 * The send button reads [AgentUiState.canSend], so a dictation that forgets to clear the
 * flag (or a send that ignores it) submits half-heard text. These are the two states the
 * composer must never confuse: the recognizer is still rewriting the text, and the take is
 * uploading with no text at all yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentComposerDictationStateTest {

    @Test
    fun `an idle composer can send and offers dictation`() {
        val state = AgentUiState()
        assertThat(state.canSend).isTrue()
        assertThat(state.speechListening).isFalse()
        assertThat(state.speechTranscribing).isFalse()
        assertThat(state.speechAvailable).isTrue()
        assertThat(state.voiceLanguage).isEqualTo(SidekickVoiceLanguage.AUTO)
    }

    @Test
    fun `sending is blocked while the recognizer is still listening`() {
        val state = AgentUiState(speechListening = true)

        assertThat(state.canSend).isFalse()
        // Listening is not "working": the agent is not answering, only the mic is open.
        assertThat(state.isWorking).isFalse()
    }

    @Test
    fun `sending is blocked while a take is transcribing`() {
        val state = AgentUiState(speechTranscribing = true, composerText = "hello")

        assertThat(state.canSend).isFalse()
        assertThat(state.isWorking).isFalse()
    }

    @Test
    fun `a streaming turn blocks sending regardless of dictation flags`() {
        val streaming = AgentUiState(phase = AgentUiState.Phase.Streaming)
        assertThat(streaming.canSend).isFalse()
        assertThat(streaming.isWorking).isTrue()

        // Even if a stale flag survived, the turn state still wins.
        val stale = AgentUiState(phase = AgentUiState.Phase.Streaming, speechListening = true)
        assertThat(stale.canSend).isFalse()
    }

    @Test
    fun `the chosen language and availability round-trip through the state`() {
        val state = AgentUiState(
            voiceLanguage = SidekickVoiceLanguage.SERBIAN,
            speechAvailable = false,
            speechHint = "hint"
        )
        assertThat(state.voiceLanguage).isEqualTo(SidekickVoiceLanguage.SERBIAN)
        assertThat(state.speechAvailable).isFalse()
        assertThat(state.speechHint).isEqualTo("hint")
    }
}
