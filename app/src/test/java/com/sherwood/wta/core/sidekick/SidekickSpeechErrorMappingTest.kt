package com.sherwood.wta.core.sidekick

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.sidekick.input.SidekickSpeechFailure
import com.sherwood.wta.core.sidekick.input.SidekickSpeechRecognizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How platform recognition errors are named in the panel.
 *
 * Pinned because the reported bug surfaced here: `ERROR_LANGUAGE_NOT_SUPPORTED` and
 * `ERROR_LANGUAGE_UNAVAILABLE` were the only path to the "does not support this language"
 * banner, so proving what each code means is what keeps that banner honest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SidekickSpeechErrorMappingTest {

    private fun recognizer(): SidekickSpeechRecognizer {
        val context: Context = ApplicationProvider.getApplicationContext()
        return SidekickSpeechRecognizer(context)
    }

    @Test
    fun `language errors are the only ones reported as unsupported language`() {
        val error = recognizer()

        assertThat(error.mapError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
            .isEqualTo(SidekickSpeechFailure.LANGUAGE_UNSUPPORTED)
        assertThat(error.mapError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
            .isEqualTo(SidekickSpeechFailure.LANGUAGE_UNSUPPORTED)
    }

    @Test
    fun `network failures are network failures, not language failures`() {
        val error = recognizer()

        assertThat(error.mapError(SpeechRecognizer.ERROR_NETWORK))
            .isEqualTo(SidekickSpeechFailure.NETWORK)
        assertThat(error.mapError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
            .isEqualTo(SidekickSpeechFailure.NETWORK)
        assertThat(error.mapError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
            .isEqualTo(SidekickSpeechFailure.NETWORK)
    }

    @Test
    fun `permission, busy and audio keep their own reason`() {
        val error = recognizer()

        assertThat(error.mapError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
            .isEqualTo(SidekickSpeechFailure.PERMISSION)
        assertThat(error.mapError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
            .isEqualTo(SidekickSpeechFailure.BUSY)
        assertThat(error.mapError(SpeechRecognizer.ERROR_AUDIO))
            .isEqualTo(SidekickSpeechFailure.AUDIO)
    }

    @Test
    fun `nothing caught is a no match rather than a hard failure`() {
        val error = recognizer()

        assertThat(error.mapError(SpeechRecognizer.ERROR_NO_MATCH))
            .isEqualTo(SidekickSpeechFailure.NO_MATCH)
        assertThat(error.mapError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
            .isEqualTo(SidekickSpeechFailure.NO_MATCH)
        assertThat(error.mapError(SpeechRecognizer.ERROR_CLIENT))
            .isEqualTo(SidekickSpeechFailure.NO_MATCH)
    }

    @Test
    fun `rate limiting is throttled, not a language verdict`() {
        val error = recognizer()

        assertThat(error.mapError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS))
            .isEqualTo(SidekickSpeechFailure.THROTTLED)
        assertThat(error.mapError(SpeechRecognizer.ERROR_SERVER))
            .isEqualTo(SidekickSpeechFailure.THROTTLED)
    }

    @Test
    fun `a service that refuses to answer at all is unknown, not unsupported language`() {
        val error = recognizer()

        // ERROR_CANNOT_CHECK_SUPPORT means the service declined before listening; calling it
        // a language verdict would send the user to change a setting that is not at fault.
        assertThat(error.mapError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT))
            .isEqualTo(SidekickSpeechFailure.UNKNOWN)
        assertThat(error.mapError(0)).isEqualTo(SidekickSpeechFailure.UNKNOWN)
        assertThat(error.mapError(99)).isEqualTo(SidekickSpeechFailure.UNKNOWN)
    }
}
