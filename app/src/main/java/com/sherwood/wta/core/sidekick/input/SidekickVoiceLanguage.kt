package com.sherwood.wta.core.sidekick.input

/**
 * The languages dictation offers.
 *
 * Deliberately a short explicit list rather than "whatever the device supports": the
 * languages the user asked for are not all installed on every phone, and a picker built
 * from a device query would silently drop Serbian on the devices that need it most. When
 * the chosen language is not available the recognizer says so instead of falling back to
 * English and returning nonsense.
 *
 * One list serves both microphones — the floating Sidekick panel and the agent workspace.
 * A single catalog is what keeps the two from disagreeing about what "Serbian" means, and
 * it is longer than the panel's original three entries because the workspace serves users
 * whose language the ten UI locales do not cover (the neighbouring South Slavic languages
 * in particular, where `sr`, `hr` and `bs` are not interchangeable).
 *
 * The tags are the BCP-47 values `RecognizerIntent.EXTRA_LANGUAGE` expects. The primary
 * subtag is also the ISO-639-1 code Whisper expects (`sr`, not `sr-RS`), so both engines
 * derive their language hint from one field and can never disagree.
 */
enum class SidekickVoiceLanguage(val id: String, val tag: String?) {
    /** No hint: the service uses its own default language (and may detect it on API 34+). */
    AUTO("auto", null),

    SERBIAN("sr", "sr-RS"),
    CROATIAN("hr", "hr-HR"),
    BOSNIAN("bs", "bs-BA"),

    ENGLISH("en", "en-US"),
    GERMAN("de", "de-DE"),
    FRENCH("fr", "fr-FR"),
    SPANISH("es", "es-ES"),
    PORTUGUESE("pt", "pt-BR"),
    ITALIAN("it", "it-IT"),
    DUTCH("nl", "nl-NL"),

    RUSSIAN("ru", "ru-RU"),
    UKRAINIAN("uk", "uk-UA"),
    POLISH("pl", "pl-PL"),
    CZECH("cs", "cs-CZ"),
    GREEK("el", "el-GR"),

    TURKISH("tr", "tr-TR"),
    ARABIC("ar", "ar-SA"),
    HINDI("hi", "hi-IN"),

    CHINESE("zh", "zh-CN"),
    JAPANESE("ja", "ja-JP"),
    KOREAN("ko", "ko-KR"),

    SWEDISH("sv", "sv-SE");

    /**
     * True for "let the service decide".
     *
     * Whisper has no equivalent setting: the server auto-detects exactly when no language
     * is sent, which is what a `null` tag already expresses.
     */
    val isAuto: Boolean get() = tag == null

    /** The ISO-639-1 code Whisper wants, or `null` to let the server auto-detect. */
    val whisperCode: String? get() = tag?.substringBefore('-')

    /**
     * Short uppercase label for the composer chip (`SR`, `EN`, `AUTO`).
     *
     * Not translated: a locale code names the same language in every UI language, and the
     * picker it opens carries the spelled-out, translated name.
     */
    val shortLabel: String get() = tag?.substringBefore('-')?.uppercase() ?: "AUTO"

    companion object {
        /** Unknown or blank stored values fall back to auto rather than to a wrong locale. */
        fun fromId(id: String?): SidekickVoiceLanguage =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Why dictation could not produce text. Mapped to UI copy by the view model. */
enum class SidekickSpeechFailure {
    /** No speech recognition service on this device (de-Googled ROMs, some OEM builds). */
    NO_SERVICE,

    /** The service reached the network and failed, or timed out there. */
    NETWORK,

    /** Nothing recognisable was said, or the phrase timed out. */
    NO_MATCH,

    /** RECORD_AUDIO was denied. */
    PERMISSION,

    /** Another recognition session already holds the recognizer. */
    BUSY,

    /** The device's service does not support the selected language or variant. */
    LANGUAGE_UNSUPPORTED,

    /** Microphone could not be read. */
    AUDIO,

    /** Rate limited or the remote service is busy. */
    THROTTLED,

    /** The transcription server refused the API key (401/403). */
    AUTH,

    /**
     * The server was reached but the request was refused or answered in the wrong shape:
     * wrong path, wrong protocol, redirect, or an unknown/oversized model.
     */
    ENDPOINT,

    /** Whisper dictation is selected but no server URL is configured, or it is invalid. */
    NOT_CONFIGURED,

    /** Anything else the service reported. */
    UNKNOWN
}

/** A live dictation session's callbacks. Called on the main thread. */
interface SidekickSpeechListener {
    /** The service is listening; the UI should show the recording state. */
    fun onReady() {}

    /** Interim text; replaces the previous partial rather than appending to it. */
    fun onPartial(text: String) {}

    /** Final transcript. Exactly one terminal callback fires per session. */
    fun onResult(text: String) {}

    /** Terminal failure. Exactly one terminal callback fires per session. */
    fun onFailure(failure: SidekickSpeechFailure) {}
}
