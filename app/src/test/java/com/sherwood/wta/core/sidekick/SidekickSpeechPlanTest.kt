package com.sherwood.wta.core.sidekick

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.sidekick.input.SidekickSpeechAttempt
import com.sherwood.wta.core.sidekick.input.SidekickSpeechFailure
import com.sherwood.wta.core.sidekick.input.SidekickSpeechPlan
import com.sherwood.wta.core.sidekick.input.SidekickVoiceLanguage
import org.junit.Test

/**
 * The dictation attempt ladder.
 *
 * Every one of the three offered languages used to fail with "the speech service does not
 * support this language", for two independent reasons this policy now prevents:
 *
 * - the on-device recognizer was selected whenever it merely existed, although its models
 *   are scoped to the device's own language;
 * - only a region-qualified tag was ever sent, and services disagree on whether they match
 *   `sr-RS` or only `sr`.
 *
 * The ladder must also never drop the language: falling back to the service default would
 * transcribe Swedish speech as English, which is worse than an honest failure.
 */
class SidekickSpeechPlanTest {

    private fun attempts(
        language: SidekickVoiceLanguage,
        preferOnDevice: Boolean = true,
        onDeviceAvailable: Boolean = true,
        deviceLanguage: String = "en"
    ): List<SidekickSpeechAttempt> = SidekickSpeechPlan.attempts(
        language = language,
        preferOnDevice = preferOnDevice,
        onDeviceAvailable = onDeviceAvailable,
        deviceLanguage = deviceLanguage
    )

    @Test
    fun `serbian on an english device goes straight to the general service`() {
        // The on-device model ships per locale; asking it for Serbian is what produced the
        // language error. It must not even be offered.
        val plan = attempts(SidekickVoiceLanguage.SERBIAN, deviceLanguage = "en")

        assertThat(plan.map { it.onDevice }).containsExactly(false, false).inOrder()
        assertThat(plan.map { it.tag }).containsExactly("sr-RS", "sr").inOrder()
        assertThat(plan.any { it.onDevice }).isFalse()
    }

    @Test
    fun `swedish on a swedish device tries on-device first`() {
        val plan = attempts(SidekickVoiceLanguage.SWEDISH, deviceLanguage = "sv")

        assertThat(plan.first()).isEqualTo(SidekickSpeechAttempt(onDevice = true, tag = "sv-SE"))
        // The general service is still next, so a missing on-device pack is not a dead end.
        assertThat(plan.drop(1).map { it.onDevice }).containsExactly(false, false).inOrder()
    }

    @Test
    fun `the language is never dropped from the ladder`() {
        // Widening sv-SE to sv names the same language; a null tag would hand the choice to
        // the service default and answer in the wrong language.
        for (language in listOf(
            SidekickVoiceLanguage.SERBIAN,
            SidekickVoiceLanguage.ENGLISH,
            SidekickVoiceLanguage.SWEDISH
        )) {
            val plan = attempts(language)
            assertThat(plan).isNotEmpty()
            assertThat(plan.map { it.tag }).doesNotContain(null)
            val expectedPrimary = language.tag!!.substringBefore('-')
            assertThat(plan.last().tag).isEqualTo(expectedPrimary)
        }
    }

    @Test
    fun `auto asks with no language tag and may use the on-device model`() {
        val plan = attempts(SidekickVoiceLanguage.AUTO, deviceLanguage = "en")

        assertThat(plan.first()).isEqualTo(SidekickSpeechAttempt(onDevice = true, tag = null))
        // The general service follows without a tag: there is no second, wider form of
        // "let the service decide".
        assertThat(plan).containsExactly(
            SidekickSpeechAttempt(onDevice = true, tag = null),
            SidekickSpeechAttempt(onDevice = false, tag = null)
        ).inOrder()
    }

    @Test
    fun `no on-device attempt when the platform has none`() {
        val plan = attempts(SidekickVoiceLanguage.ENGLISH, onDeviceAvailable = false)

        assertThat(plan.any { it.onDevice }).isFalse()
        assertThat(plan.map { it.tag }).containsExactly("en-US", "en").inOrder()
    }

    @Test
    fun `no on-device attempt when the preference is off`() {
        val plan = attempts(SidekickVoiceLanguage.ENGLISH, preferOnDevice = false)

        assertThat(plan).containsExactly(
            SidekickSpeechAttempt(onDevice = false, tag = "en-US"),
            SidekickSpeechAttempt(onDevice = false, tag = "en")
        ).inOrder()
    }

    @Test
    fun `a device language match is case insensitive and ignores the region`() {
        val plan = attempts(SidekickVoiceLanguage.SERBIAN, deviceLanguage = "SR")
        assertThat(plan.first().onDevice).isTrue()
    }

    @Test
    fun `only language and service failures are worth another attempt`() {
        // A denied microphone or a missing permission must not be retried three times: the
        // next rung cannot fix it, and the user would wait through each one.
        assertThat(SidekickSpeechPlan.isRetryable(SidekickSpeechFailure.LANGUAGE_UNSUPPORTED))
            .isTrue()
        assertThat(SidekickSpeechPlan.isRetryable(SidekickSpeechFailure.NO_SERVICE)).isTrue()
        assertThat(SidekickSpeechPlan.isRetryable(SidekickSpeechFailure.PERMISSION)).isFalse()
        assertThat(SidekickSpeechPlan.isRetryable(SidekickSpeechFailure.AUDIO)).isFalse()
        assertThat(SidekickSpeechPlan.isRetryable(SidekickSpeechFailure.BUSY)).isFalse()
        assertThat(SidekickSpeechPlan.isRetryable(SidekickSpeechFailure.NETWORK)).isFalse()
        assertThat(SidekickSpeechPlan.isRetryable(SidekickSpeechFailure.NO_MATCH)).isFalse()
    }
}
