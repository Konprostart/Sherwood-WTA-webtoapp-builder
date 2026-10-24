package com.sherwood.wta.core.privacy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsolationConfigTest {

    @Test
    fun `toJson and fromJson preserve isolation config`() {
        val original = IsolationConfig(
            enabled = true,
            fingerprintConfig = FingerprintConfig(
                randomize = true,
                customUserAgent = "UA",
                hardwareConcurrency = 8
            ),
            spoofTimezone = true,
            customTimezone = "Asia/Tokyo",
            spoofLanguage = true,
            customLanguage = "ja-JP",
            spoofScreen = true,
            customScreenWidth = 1920,
            customScreenHeight = 1080,
            customDevicePixelRatio = 1.5f
        )

        val restored = IsolationConfig.fromJson(original.toJson())

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `fromJson returns null for malformed input`() {
        val parsed = IsolationConfig.fromJson("{invalid-json")

        assertThat(parsed).isNull()
    }

    @Test
    fun `legacy json with removed fields still deserializes`() {
        // Old-version JSON carries removed headerConfig/ipSpoofConfig/storageIsolation; Gson ignores unknown fields.
        val legacy = """
            {
              "enabled": true,
              "fingerprintConfig": {"randomize": true, "fingerprintId": "seed-1"},
              "headerConfig": {"enabled": true, "dnt": true},
              "ipSpoofConfig": {"enabled": true, "randomIpRange": "GLOBAL"},
              "storageIsolation": true,
              "spoofTimezone": true,
              "customTimezone": "Europe/Paris"
            }
        """.trimIndent()

        val parsed = IsolationConfig.fromJson(legacy)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.enabled).isTrue()
        assertThat(parsed.spoofTimezone).isTrue()
        assertThat(parsed.customTimezone).isEqualTo("Europe/Paris")
        assertThat(parsed.fingerprintConfig.fingerprintId).isEqualTo("seed-1")
    }

    @Test
    fun `predefined presets expose expected security levels`() {
        assertThat(IsolationConfig.DISABLED.enabled).isFalse()

        assertThat(IsolationConfig.BASIC.enabled).isTrue()
        assertThat(IsolationConfig.BASIC.protectCanvas).isTrue()
        assertThat(IsolationConfig.BASIC.protectAudio).isFalse()
        assertThat(IsolationConfig.BASIC.spoofLanguage).isFalse()

        assertThat(IsolationConfig.STANDARD.enabled).isTrue()
        assertThat(IsolationConfig.STANDARD.protectAudio).isTrue()
        assertThat(IsolationConfig.STANDARD.protectWebGL).isTrue()
        assertThat(IsolationConfig.STANDARD.spoofTimezone).isFalse()

        assertThat(IsolationConfig.MAXIMUM.enabled).isTrue()
        assertThat(IsolationConfig.MAXIMUM.spoofTimezone).isTrue()
        assertThat(IsolationConfig.MAXIMUM.spoofLanguage).isTrue()
        assertThat(IsolationConfig.MAXIMUM.spoofScreen).isTrue()
        assertThat(IsolationConfig.MAXIMUM.fingerprintConfig.regenerateOnLaunch).isTrue()
    }

    @Test
    fun `level detection matches presets and custom mixes`() {
        assertThat(IsolationConfig.BASIC.level()).isEqualTo(IsolationLevel.BASIC)
        assertThat(IsolationConfig.STANDARD.level()).isEqualTo(IsolationLevel.STANDARD)
        assertThat(IsolationConfig.MAXIMUM.level()).isEqualTo(IsolationLevel.MAXIMUM)
        assertThat(IsolationConfig.DISABLED.level()).isNull()

        // Custom values don't change the protection level (spoof values are not part of protection strength)
        val maxCustom = IsolationConfig.MAXIMUM.copy(
            customLanguage = "ja-JP",
            customTimezone = "Asia/Tokyo",
            customScreenWidth = 2560,
            customScreenHeight = 1440
        )
        assertThat(maxCustom.level()).isEqualTo(IsolationLevel.MAXIMUM)

        // Turn off one spoof item → custom combination
        val mixed = IsolationConfig.MAXIMUM.copy(spoofScreen = false)
        assertThat(mixed.level()).isNull()
    }

    @Test
    fun `withLevel preserves chosen spoof values and fingerprint seed`() {
        val customized = IsolationConfig.MAXIMUM.copy(
            customLanguage = "de-DE",
            customTimezone = "Europe/Berlin",
            customScreenWidth = 1600,
            customScreenHeight = 900,
            fingerprintConfig = IsolationConfig.MAXIMUM.fingerprintConfig.copy(fingerprintId = "keep-me")
        )

        val downgraded = customized.withLevel(IsolationLevel.BASIC)
        assertThat(downgraded.spoofLanguage).isFalse()
        assertThat(downgraded.customLanguage).isEqualTo("de-DE")
        assertThat(downgraded.customTimezone).isEqualTo("Europe/Berlin")
        assertThat(downgraded.fingerprintConfig.fingerprintId).isEqualTo("keep-me")

        // Raising back to max re-enables spoofing; the values are kept
        val upgraded = downgraded.withLevel(IsolationLevel.MAXIMUM)
        assertThat(upgraded.spoofLanguage).isTrue()
        assertThat(upgraded.customLanguage).isEqualTo("de-DE")
    }

    @Test
    fun `languageList derives ordered navigator languages`() {
        assertThat(IsolationPresets.languageList("en-US")).containsExactly("en-US", "en").inOrder()
        assertThat(IsolationPresets.languageList("ja-JP")).containsExactly("ja-JP", "ja").inOrder()
        assertThat(IsolationPresets.languageList("en")).containsExactly("en")
    }

    @Test
    fun `preset validators accept sane values and reject garbage`() {
        assertThat(IsolationPresets.isValidLanguageTag("en-US")).isTrue()
        assertThat(IsolationPresets.isValidLanguageTag("zh-Hant-TW")).isTrue()
        assertThat(IsolationPresets.isValidLanguageTag("not a tag")).isFalse()
        assertThat(IsolationPresets.isValidLanguageTag("<script>")).isFalse()

        assertThat(IsolationPresets.isValidTimezoneId("Asia/Shanghai")).isTrue()
        assertThat(IsolationPresets.isValidTimezoneId("Mars/Olympus")).isFalse()
    }
}
