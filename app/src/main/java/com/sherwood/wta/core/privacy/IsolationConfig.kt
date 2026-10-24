package com.sherwood.wta.core.privacy

import java.util.UUID

data class IsolationConfig(
    val enabled: Boolean = false,

    val fingerprintConfig: FingerprintConfig = FingerprintConfig(),

    val blockWebRTC: Boolean = true,

    val protectCanvas: Boolean = true,

    val protectAudio: Boolean = true,

    val protectWebGL: Boolean = true,

    val protectFonts: Boolean = false,

    val spoofTimezone: Boolean = false,
    val customTimezone: String? = null,

    val spoofLanguage: Boolean = false,
    val customLanguage: String? = null,

    val spoofScreen: Boolean = false,
    val customScreenWidth: Int? = null,
    val customScreenHeight: Int? = null,
    val customDevicePixelRatio: Float? = null
) {
    companion object {
        private val gson = com.sherwood.wta.util.GsonProvider.gson
        val DISABLED = IsolationConfig(enabled = false)

        /** Basic protection: fingerprint randomization + Canvas noise + WebRTC blocking; the least noticeable. */
        val BASIC = IsolationConfig(
            enabled = true,
            protectCanvas = true,
            protectAudio = false,
            protectWebGL = false,
            protectFonts = false,
            blockWebRTC = true
        )

        /** Standard protection: full fingerprint protection (no environment spoofing). */
        val STANDARD = IsolationConfig(
            enabled = true,
            protectCanvas = true,
            protectAudio = true,
            protectWebGL = true,
            protectFonts = false,
            blockWebRTC = true
        )

        /** Maximum protection: all protections + language/timezone/resolution spoofing + new fingerprint on every launch. */
        val MAXIMUM = IsolationConfig(
            enabled = true,
            fingerprintConfig = FingerprintConfig(randomize = true, regenerateOnLaunch = true),
            protectCanvas = true,
            protectAudio = true,
            protectWebGL = true,
            protectFonts = true,
            blockWebRTC = true,
            spoofTimezone = true,
            spoofLanguage = true,
            spoofScreen = true
        )

        fun fromJson(json: String): IsolationConfig? {
            return try {
                gson.fromJson(json, IsolationConfig::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Protection level matching the current config; returns null for a custom combination. */
    fun level(): IsolationLevel? = when {
        !enabled -> null
        matches(MAXIMUM) -> IsolationLevel.MAXIMUM
        matches(STANDARD) -> IsolationLevel.STANDARD
        matches(BASIC) -> IsolationLevel.BASIC
        else -> null
    }

    /** Compares only fields related to protection strength; ignores the specific values of spoof items. */
    private fun matches(preset: IsolationConfig): Boolean {
        return fingerprintConfig.randomize == preset.fingerprintConfig.randomize &&
            fingerprintConfig.regenerateOnLaunch == preset.fingerprintConfig.regenerateOnLaunch &&
            protectCanvas == preset.protectCanvas &&
            protectAudio == preset.protectAudio &&
            protectWebGL == preset.protectWebGL &&
            protectFonts == preset.protectFonts &&
            blockWebRTC == preset.blockWebRTC &&
            spoofTimezone == preset.spoofTimezone &&
            spoofLanguage == preset.spoofLanguage &&
            spoofScreen == preset.spoofScreen
    }

    fun toJson(): String = gson.toJson(this)
}

enum class IsolationLevel { BASIC, STANDARD, MAXIMUM }

/**
 * Applies a protection-strength preset: only resets protection toggles and spoof on/off; keeps the user's chosen spoof values and fingerprint seed.
 */
fun IsolationConfig.withLevel(level: IsolationLevel): IsolationConfig {
    val preset = when (level) {
        IsolationLevel.BASIC -> IsolationConfig.BASIC
        IsolationLevel.STANDARD -> IsolationConfig.STANDARD
        IsolationLevel.MAXIMUM -> IsolationConfig.MAXIMUM
    }
    return copy(
        enabled = true,
        fingerprintConfig = fingerprintConfig.copy(
            randomize = preset.fingerprintConfig.randomize,
            regenerateOnLaunch = preset.fingerprintConfig.regenerateOnLaunch
        ),
        protectCanvas = preset.protectCanvas,
        protectAudio = preset.protectAudio,
        protectWebGL = preset.protectWebGL,
        protectFonts = preset.protectFonts,
        blockWebRTC = preset.blockWebRTC,
        spoofTimezone = preset.spoofTimezone,
        spoofLanguage = preset.spoofLanguage,
        spoofScreen = preset.spoofScreen
    )
}

data class FingerprintConfig(
    val randomize: Boolean = true,
    val regenerateOnLaunch: Boolean = false,

    val customUserAgent: String? = null,
    val randomUserAgent: Boolean = true,

    val platform: String? = null,
    val vendor: String? = null,

    val hardwareConcurrency: Int? = null,
    val deviceMemory: Int? = null,

    val fingerprintId: String = UUID.randomUUID().toString()
)

/** Selectable regions for language spoofing (label is the native name, so it is not translated with the UI language). */
data class SpoofLocaleOption(val tag: String, val label: String)

/** Selectable timezones for spoofing (label includes the standard UTC offset; DST is handled automatically at runtime via Intl). */
data class SpoofTimezoneOption(val id: String, val label: String)

/** Selectable resolutions for spoofing (CSS pixel size + matching DPR, keeping screen/innerWidth/matchMedia consistent). */
data class SpoofScreenOption(val width: Int, val height: Int, val dpr: Float) {
    val label: String get() = "${width} × ${height}"
}

object IsolationPresets {

    val locales = listOf(
        SpoofLocaleOption("zh-CN", "简体中文"),
        SpoofLocaleOption("zh-TW", "繁體中文"),
        SpoofLocaleOption("en-US", "English (US)"),
        SpoofLocaleOption("en-GB", "English (UK)"),
        SpoofLocaleOption("ja-JP", "日本語"),
        SpoofLocaleOption("ko-KR", "한국어"),
        SpoofLocaleOption("de-DE", "Deutsch"),
        SpoofLocaleOption("fr-FR", "Français"),
        SpoofLocaleOption("es-ES", "Español"),
        SpoofLocaleOption("pt-BR", "Português (BR)"),
        SpoofLocaleOption("ru-RU", "Русский"),
        SpoofLocaleOption("ar-SA", "العربية"),
        SpoofLocaleOption("hi-IN", "हिन्दी"),
        SpoofLocaleOption("it-IT", "Italiano"),
        SpoofLocaleOption("nl-NL", "Nederlands")
    )

    val timezones = listOf(
        SpoofTimezoneOption("Asia/Shanghai", "Asia/Shanghai · UTC+8"),
        SpoofTimezoneOption("Asia/Hong_Kong", "Asia/Hong_Kong · UTC+8"),
        SpoofTimezoneOption("Asia/Taipei", "Asia/Taipei · UTC+8"),
        SpoofTimezoneOption("Asia/Singapore", "Asia/Singapore · UTC+8"),
        SpoofTimezoneOption("Asia/Tokyo", "Asia/Tokyo · UTC+9"),
        SpoofTimezoneOption("Asia/Seoul", "Asia/Seoul · UTC+9"),
        SpoofTimezoneOption("Asia/Kolkata", "Asia/Kolkata · UTC+5:30"),
        SpoofTimezoneOption("Europe/London", "Europe/London · UTC+0"),
        SpoofTimezoneOption("Europe/Paris", "Europe/Paris · UTC+1"),
        SpoofTimezoneOption("Europe/Berlin", "Europe/Berlin · UTC+1"),
        SpoofTimezoneOption("Europe/Moscow", "Europe/Moscow · UTC+3"),
        SpoofTimezoneOption("America/New_York", "America/New_York · UTC-5"),
        SpoofTimezoneOption("America/Chicago", "America/Chicago · UTC-6"),
        SpoofTimezoneOption("America/Denver", "America/Denver · UTC-7"),
        SpoofTimezoneOption("America/Los_Angeles", "America/Los_Angeles · UTC-8"),
        SpoofTimezoneOption("America/Sao_Paulo", "America/Sao_Paulo · UTC-3"),
        SpoofTimezoneOption("Australia/Sydney", "Australia/Sydney · UTC+10"),
        SpoofTimezoneOption("Pacific/Auckland", "Pacific/Auckland · UTC+12")
    )

    val screens = listOf(
        SpoofScreenOption(1920, 1080, 1.0f),
        SpoofScreenOption(1366, 768, 1.0f),
        SpoofScreenOption(1536, 864, 1.25f),
        SpoofScreenOption(1440, 900, 1.0f),
        SpoofScreenOption(1600, 900, 1.0f),
        SpoofScreenOption(1280, 800, 1.0f),
        SpoofScreenOption(2560, 1440, 1.5f),
        SpoofScreenOption(3840, 2160, 1.5f)
    )

    /** Derives navigator.languages from the BCP-47 primary language tag (["en-US", "en"]). */
    fun languageList(tag: String): List<String> {
        val base = tag.substringBefore('-')
        return if (base == tag) listOf(tag) else listOf(tag, base)
    }

    fun isValidLanguageTag(tag: String): Boolean {
        return tag.matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"))
    }

    fun isValidTimezoneId(id: String): Boolean {
        return java.util.TimeZone.getTimeZone(id).id == id
    }
}
