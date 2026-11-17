package com.sherwood.wta.core.sidekick.input

/**
 * One way of asking the platform recognizer to listen.
 *
 * @param onDevice true to use the on-device recognizer instead of the general service.
 * @param tag the BCP-47 language to request, or `null` to let the service decide.
 */
internal data class SidekickSpeechAttempt(val onDevice: Boolean, val tag: String?)

/**
 * Decides which recognizer attempts a dictation session makes, best first.
 *
 * Kept as a pure function so the policy that decides "what do we ask the phone for" is
 * unit-testable on a plain JVM — the recognizer that consumes it needs a real
 * `SpeechRecognizer`, but the decision must not.
 *
 * The policy exists because two separate mistakes made dictation fail for *every* offered
 * language with "the speech service does not support this language":
 *
 * 1. **The on-device recognizer was chosen whenever it merely existed.** Android's
 *    on-device models are scoped to the device's configured language, so it refused
 *    Serbian and Swedish outright. It is now only chosen when it can plausibly serve the
 *    requested language.
 * 2. **A region-qualified tag was the only form ever sent.** Services differ in whether
 *    they match `sr-RS` or only `sr`, so the primary subtag is a second attempt.
 *
 * The ladder never drops the language: a service default would transcribe Swedish speech
 * as English, which is worse than an honest failure. Widening `sr-RS` to `sr` names the
 * same language, so it is safe.
 */
internal object SidekickSpeechPlan {

    fun attempts(
        language: SidekickVoiceLanguage,
        preferOnDevice: Boolean,
        onDeviceAvailable: Boolean,
        deviceLanguage: String
    ): List<SidekickSpeechAttempt> {
        val fullTag = language.tag
        val primary = fullTag?.substringBefore('-')
        val ladder = mutableListOf<SidekickSpeechAttempt>()

        if (preferOnDevice && onDeviceAvailable && canServeOnDevice(fullTag, deviceLanguage)) {
            ladder += SidekickSpeechAttempt(onDevice = true, tag = fullTag)
        }
        // The general service always gets a turn: it covers far more languages than any
        // on-device model, and it is the only option when the preference is off.
        ladder += SidekickSpeechAttempt(onDevice = false, tag = fullTag)
        if (primary != null && primary != fullTag) {
            ladder += SidekickSpeechAttempt(onDevice = false, tag = primary)
        }
        return ladder
    }

    /**
     * Whether the on-device recognizer is a sensible choice for [tag].
     *
     * `null` means no language was pinned, so the device default is exactly what is wanted.
     * Otherwise only the device's own language is trusted, because that is the language its
     * on-device model ships with.
     */
    private fun canServeOnDevice(tag: String?, deviceLanguage: String): Boolean {
        if (tag == null) return true
        val primary = tag.substringBefore('-')
        return primary.equals(deviceLanguage, ignoreCase = true)
    }

    /**
     * Whether a declared failure is worth another attempt from the ladder.
     *
     * A service that cannot serve this language or has no recognizer behind it may still be
     * helped by the next rung; a microphone or permission failure will not.
     */
    fun isRetryable(failure: SidekickSpeechFailure): Boolean =
        failure == SidekickSpeechFailure.LANGUAGE_UNSUPPORTED ||
            failure == SidekickSpeechFailure.NO_SERVICE
}
