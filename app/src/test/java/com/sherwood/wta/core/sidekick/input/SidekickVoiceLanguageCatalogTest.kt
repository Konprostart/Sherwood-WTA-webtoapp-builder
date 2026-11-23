package com.sherwood.wta.core.sidekick.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The dictation language catalog.
 *
 * Both microphones read this one list, so a defect here is a defect in the Sidekick panel
 * and the agent workspace at once. The assertions pin the two things that silently break
 * dictation: a duplicated or malformed code (the recognizer refuses the language) and a
 * language that lost its region-free form (services differ on whether they match `sr-RS`
 * or only `sr`, and Whisper only understands the latter).
 */
class SidekickVoiceLanguageCatalogTest {

    @Test
    fun `ids and tags are unique and non-blank`() {
        val ids = SidekickVoiceLanguage.entries.map { it.id }
        assertThat(ids).containsNoDuplicates()

        val tags = SidekickVoiceLanguage.entries.mapNotNull { it.tag }
        assertThat(tags).containsNoDuplicates()
        assertThat(tags).containsNoneIn(ids)

        for (language in SidekickVoiceLanguage.entries) {
            assertThat(language.id).isNotEmpty()
            if (language.tag != null) {
                // BCP-47 language-region, which is what `EXTRA_LANGUAGE` expects.
                assertThat(language.tag).matches("[a-z]{2}-[A-Z]{2}")
            }
        }
    }

    @Test
    fun `serbian, english and the neighbouring languages are offered`() {
        val offered = SidekickVoiceLanguage.entries.toSet()
        assertThat(offered).containsAtLeast(
            SidekickVoiceLanguage.SERBIAN,
            SidekickVoiceLanguage.CROATIAN,
            SidekickVoiceLanguage.BOSNIAN,
            SidekickVoiceLanguage.ENGLISH,
            SidekickVoiceLanguage.GERMAN,
            SidekickVoiceLanguage.RUSSIAN,
            SidekickVoiceLanguage.CHINESE,
            SidekickVoiceLanguage.JAPANESE,
            SidekickVoiceLanguage.KOREAN
        )
        // The original three keep their stored ids: preferences written by 2.13.0 must
        // still resolve to the same language after the catalog grew.
        assertThat(SidekickVoiceLanguage.fromId("sr")).isEqualTo(SidekickVoiceLanguage.SERBIAN)
        assertThat(SidekickVoiceLanguage.fromId("en")).isEqualTo(SidekickVoiceLanguage.ENGLISH)
        assertThat(SidekickVoiceLanguage.fromId("sv")).isEqualTo(SidekickVoiceLanguage.SWEDISH)
        assertThat(SidekickVoiceLanguage.fromId("auto")).isEqualTo(SidekickVoiceLanguage.AUTO)
    }

    @Test
    fun `every stored id round-trips and unknown ids fall back to auto`() {
        for (language in SidekickVoiceLanguage.entries) {
            assertThat(SidekickVoiceLanguage.fromId(language.id)).isEqualTo(language)
        }
        // A removed/renamed id and a blank value must not resolve to a wrong locale.
        assertThat(SidekickVoiceLanguage.fromId("tlh")).isEqualTo(SidekickVoiceLanguage.AUTO)
        assertThat(SidekickVoiceLanguage.fromId("")).isEqualTo(SidekickVoiceLanguage.AUTO)
        assertThat(SidekickVoiceLanguage.fromId(null)).isEqualTo(SidekickVoiceLanguage.AUTO)
    }

    @Test
    fun `auto asks for no language on either engine`() {
        assertThat(SidekickVoiceLanguage.AUTO.isAuto).isTrue()
        assertThat(SidekickVoiceLanguage.AUTO.tag).isNull()
        assertThat(SidekickVoiceLanguage.AUTO.whisperCode).isNull()
        assertThat(SidekickSpeechEndpointPolicy.whisperLanguage(SidekickVoiceLanguage.AUTO)).isNull()
        // A pinned language always names itself on both engines.
        for (language in SidekickVoiceLanguage.entries.filterNot { it.isAuto }) {
            assertThat(language.isAuto).isFalse()
            assertThat(language.whisperCode).isNotNull()
        }
    }

    @Test
    fun `whisper gets the primary subtag and the policy agrees with the catalog`() {
        for (language in SidekickVoiceLanguage.entries) {
            assertThat(SidekickSpeechEndpointPolicy.whisperLanguage(language))
                .isEqualTo(language.whisperCode)
        }
        // Whisper wants `sr`, not `sr-RS`: a region-qualified code is rejected or ignored.
        assertThat(SidekickVoiceLanguage.SERBIAN.whisperCode).isEqualTo("sr")
        assertThat(SidekickVoiceLanguage.SERBIAN.tag).isEqualTo("sr-RS")
        assertThat(SidekickVoiceLanguage.PORTUGUESE.whisperCode).isEqualTo("pt")
        assertThat(SidekickVoiceLanguage.PORTUGUESE.tag).isEqualTo("pt-BR")
    }

    @Test
    fun `the composer chip label is a short uppercase locale code`() {
        assertThat(SidekickVoiceLanguage.AUTO.shortLabel).isEqualTo("AUTO")
        for (language in SidekickVoiceLanguage.entries.filterNot { it.isAuto }) {
            assertThat(language.shortLabel).matches("[A-Z]{2}")
            assertThat(language.shortLabel).isEqualTo(language.whisperCode?.uppercase())
        }
        assertThat(SidekickVoiceLanguage.SERBIAN.shortLabel).isEqualTo("SR")
        assertThat(SidekickVoiceLanguage.ENGLISH.shortLabel).isEqualTo("EN")
    }
}
