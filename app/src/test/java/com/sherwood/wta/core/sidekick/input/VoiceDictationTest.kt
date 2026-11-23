package com.sherwood.wta.core.sidekick.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dictation text contract shared by both microphones.
 *
 * The panel and the workspace composer both start from whatever the user had typed and
 * then lay the recognizer's transcript over the spoken part. Getting this wrong is user
 * visible in a way that is hard to notice in review: a missing space joins two words, a
 * kept old transcript doubles the sentence after every interim result, and a failed take
 * that does not restore the base text silently deletes what was typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceDictationTest {

    @Test
    fun `a transcript is appended to the typed text with one space`() {
        assertThat(VoiceDictation.compose("open the settings", "and find the theme"))
            .isEqualTo("open the settings and find the theme")
        // Trailing whitespace the user typed must not double the separating space.
        assertThat(VoiceDictation.compose("open the settings   ", "and find the theme"))
            .isEqualTo("open the settings and find the theme")
        assertThat(VoiceDictation.compose("open", "  find  ")).isEqualTo("open find")
    }

    @Test
    fun `dictating into an empty composer yields just the transcript`() {
        assertThat(VoiceDictation.compose("", "hello")).isEqualTo("hello")
        assertThat(VoiceDictation.compose("   ", "hello")).isEqualTo("hello")
    }

    @Test
    fun `an empty transcript leaves the typed text untouched`() {
        assertThat(VoiceDictation.compose("typed text", "")).isEqualTo("typed text")
        assertThat(VoiceDictation.compose("typed text", "   ")).isEqualTo("typed text")
        assertThat(VoiceDictation.compose("", "")).isEqualTo("")
    }

    @Test
    fun `interim updates replace the spoken part instead of growing it`() {
        // The recognizer sends "open", then "open set", then "open settings"; composing
        // each against the same base is what keeps the sentence from repeating itself.
        val base = "please"
        val partials = listOf("open", "open set", "open settings")
        val rendered = partials.map { VoiceDictation.compose(base, it) }
        assertThat(rendered).containsExactly(
            "please open",
            "please open set",
            "please open settings"
        ).inOrder()
    }

    @Test
    fun `every failure has its own message and none of them is blank`() {
        val messages = SidekickSpeechFailure.entries.map { VoiceDictation.messageFor(it) }
        assertThat(messages).containsNoDuplicates()
        assertThat(messages.any { it.isBlank() }).isFalse()
        // The two engine-specific failures must stay distinguishable: "no speech service"
        // and "no Whisper server configured" have completely different fixes.
        assertThat(VoiceDictation.messageFor(SidekickSpeechFailure.NO_SERVICE))
            .isNotEqualTo(VoiceDictation.messageFor(SidekickSpeechFailure.NOT_CONFIGURED))
    }
}
