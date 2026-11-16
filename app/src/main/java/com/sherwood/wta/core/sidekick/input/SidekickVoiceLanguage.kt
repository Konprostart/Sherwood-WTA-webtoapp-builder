package com.sherwood.wta.core.sidekick.input

/**
 * The languages dictation offers.
 *
 * Deliberately a short explicit list rather than "whatever the device supports": the three
 * languages the user asked for are not all installed on every phone, and a picker built
 * from a device query would silently drop Serbian on the devices that need it most. When
 * the chosen language is not available the recognizer says so instead of falling back to
 * English and returning nonsense.
 *
 * The tags are the BCP-47 values `RecognizerIntent.EXTRA_LANGUAGE` expects.
 */
enum class SidekickVoiceLanguage(val id: String, val tag: String?) {
    /** No hint: the service uses its own default language (and may detect it on API 34+). */
    AUTO("auto", null),
    SERBIAN("sr", "sr-RS"),
    ENGLISH("en", "en-US"),
    SWEDISH("sv", "sv-SE");

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
