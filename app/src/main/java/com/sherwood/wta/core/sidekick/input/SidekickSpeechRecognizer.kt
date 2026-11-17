package com.sherwood.wta.core.sidekick.input

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.sherwood.wta.core.logging.AppLogger
import java.util.Locale

/**
 * System dictation for the Sidekick panel.
 *
 * Uses the platform `SpeechRecognizer`, which costs zero APK size and needs no API key: the
 * phone's own recognition service does the work. That service may run on-device or in the
 * cloud depending on the device and language pack — this class does not pretend otherwise.
 *
 * Two rules keep dictation working for the languages the panel offers (Serbian, English,
 * Swedish), because both used to fail for *every* selection with
 * "the speech service does not support this language":
 *
 * 1. **Never ask for offline-only recognition on the general service.** Sending
 *    `EXTRA_PREFER_OFFLINE` makes a service that is otherwise perfectly able to dictate
 *    answer `ERROR_LANGUAGE_UNAVAILABLE` when the offline model for that language is not
 *    downloaded — for every language, which is exactly the reported symptom. The honest way
 *    to ask for on-device recognition is to create the on-device recognizer, and that is
 *    what the preference now does.
 * 2. **Use the on-device recognizer only where it can serve the language.** Android's
 *    on-device models are scoped to the device's configured language, so selecting it
 *    whenever it merely *exists* made every other language unsupported. It is now chosen
 *    only when the requested language matches the device locale (or no language was pinned),
 *    and the session falls through to the next option if a service still refuses.
 *
 * The fall-through ladder is finite and language-preserving: it never drops the language
 * (which would transcribe Swedish as English). It only widens a region-qualified tag to its
 * primary subtag, which names the same language.
 *
 * Lifetime: one session at a time, released after every terminal event. A recognizer left
 * alive holds an audio session open, which on some devices keeps the microphone indicator
 * lit after the panel closes — so [release] is called from `onCleared` and from every
 * terminal callback.
 *
 * All methods must be called from the main thread; `SpeechRecognizer` requires it.
 */
class SidekickSpeechRecognizer(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var listener: SidekickSpeechListener? = null
    private var terminalDelivered = false

    /** The ladder of attempts for the session in flight; see [SidekickSpeechPlan]. */
    private var attempts: List<SidekickSpeechAttempt> = emptyList()
    private var attemptIndex = 0

    /** Whether any recognition service is installed at all. */
    fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
    }.getOrDefault(false)

    /**
     * Starts listening. Returns `false` when a session is already running or no service
     * exists; the caller then shows the "no speech service" state instead of a dead
     * microphone button.
     */
    fun start(
        language: SidekickVoiceLanguage,
        preferOnDevice: Boolean,
        listener: SidekickSpeechListener
    ): Boolean {
        if (!isAvailable()) {
            listener.onFailure(SidekickSpeechFailure.NO_SERVICE)
            return false
        }
        // A session started while another was running simply replaces it: the user pressed
        // the button again, which is not a failure worth reporting.
        cancelInternal()
        this.listener = listener
        terminalDelivered = false
        attempts = SidekickSpeechPlan.attempts(
            language = language,
            preferOnDevice = preferOnDevice,
            onDeviceAvailable = isOnDeviceAvailable(),
            deviceLanguage = deviceLocaleLanguage()
        )
        attemptIndex = 0
        return startAttempt() != null
    }

    /** Stops listening and lets the service deliver its final result. */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    /** Abandons the session; any pending result is ignored. */
    fun cancel() = cancelInternal()

    /** Releases the recognizer for good. Safe to call more than once. */
    fun release() {
        listener = null
        releaseRecognizer()
    }

    private fun cancelInternal() {
        listener = null
        terminalDelivered = true
        attempts = emptyList()
        attemptIndex = 0
        releaseRecognizer()
    }

    /**
     * Whether the platform offers an on-device recognizer at all (API 31+).
     *
     * This is availability only — whether it can serve the *selected language* is
     * [SidekickSpeechPlan]'s decision, because a recognizer that exists can still refuse
     * every language the panel offers.
     */
    private fun isOnDeviceAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context.applicationContext)
        }.getOrDefault(false)
    }

    private fun deviceLocaleLanguage(): String = runCatching {
        val configuration = context.applicationContext.resources.configuration
        val locales = configuration.locales
        if (locales.isEmpty) {
            Locale.getDefault().language
        } else {
            locales[0].language
        }
    }.getOrDefault(Locale.getDefault().language)

    /**
     * Creates the recognizer for the current rung and starts listening.
     *
     * Returns the recognizer when listening began, or `null` when every remaining rung
     * failed to start — in which case the listener has already been told why.
     */
    private fun startAttempt(): SpeechRecognizer? {
        while (attemptIndex < attempts.size) {
            val attempt = attempts[attemptIndex]
            if (this.listener == null) return null

            val created = createRecognizer(attempt.onDevice)
            if (created == null) {
                // The on-device recognizer can disappear between the availability check and
                // here; that is a reason to move on, not to fail the session.
                attemptIndex++
                continue
            }
            recognizer = created
            created.setRecognitionListener(Callback())
            val started = try {
                created.startListening(buildIntent(attempt.tag))
                true
            } catch (t: Throwable) {
                AppLogger.w(TAG, "startListening failed: ${t.javaClass.simpleName}")
                false
            }
            // A rung that could not even open the microphone must not end the ladder: the
            // next one is exactly what the fall-through exists for.
            if (started) return created
            releaseRecognizer()
            attemptIndex++
        }
        // No rung could even open a microphone: report the failure the service last implied.
        val pending = this.listener
        this.listener = null
        if (!terminalDelivered) {
            terminalDelivered = true
            pending?.onFailure(SidekickSpeechFailure.NO_SERVICE)
        }
        return null
    }

    /** Moves to the next rung; returns false when the session is exhausted. */
    private fun advance(): Boolean {
        if (terminalDelivered) return false
        attemptIndex++
        if (attemptIndex >= attempts.size) return false
        releaseRecognizer()
        startAttempt()
        return !terminalDelivered
    }

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer? {
        val appContext = context.applicationContext
        return try {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "createSpeechRecognizer failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun buildIntent(tag: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Partial results are what makes the panel feel like dictation instead of a
            // recording that resolves when you stop.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (tag != null) {
                // The documented input. `EXTRA_LANGUAGE_PREFERENCE` is deliberately not
                // set: it is the service's report of the user's preference, not a request,
                // and sending it next to a different `EXTRA_LANGUAGE` made the two
                // contradict each other.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            } else if (Build.VERSION.SDK_INT >= 34) {
                // Auto: let the service decide, and report which language it used.
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            }
        }

    private fun releaseRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.setRecognitionListener(null) }
        runCatching { current.destroy() }
    }

    private inner class Callback : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            if (terminalDelivered) return
            listener?.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (terminalDelivered) return
            val text = firstResult(partialResults) ?: return
            listener?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (terminalDelivered) return
            terminalDelivered = true
            val text = firstResult(results).orEmpty().trim()
            val pending = listener
            listener = null
            releaseRecognizer()
            if (text.isEmpty()) {
                pending?.onFailure(SidekickSpeechFailure.NO_MATCH)
            } else {
                pending?.onResult(text)
            }
        }

        override fun onError(error: Int) {
            if (terminalDelivered) return
            val failure = mapError(error)
            // A service that cannot serve this language or model is worth one more,
            // narrower attempt before the user is told dictation is impossible.
            if (SidekickSpeechPlan.isRetryable(failure) && advance()) return
            terminalDelivered = true
            val pending = listener
            listener = null
            releaseRecognizer()
            pending?.onFailure(failure)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String? {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    internal fun mapError(error: Int): SidekickSpeechFailure = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SidekickSpeechFailure.NETWORK

        // ERROR_CLIENT is what the service reports when a session is abandoned; it is not
        // worth an error banner, so it lands on the same "nothing caught" path.
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_CLIENT -> SidekickSpeechFailure.NO_MATCH

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SidekickSpeechFailure.PERMISSION

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SidekickSpeechFailure.BUSY

        SpeechRecognizer.ERROR_AUDIO -> SidekickSpeechFailure.AUDIO

        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        SpeechRecognizer.ERROR_SERVER -> SidekickSpeechFailure.THROTTLED

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SidekickSpeechFailure.LANGUAGE_UNSUPPORTED

        // ERROR_CANNOT_CHECK_SUPPORT (14) and anything newer: the service declined before
        // it even tried to listen, which is the "no usable service" case rather than a
        // language verdict we can justify.
        else -> SidekickSpeechFailure.UNKNOWN
    }

    companion object {
        private const val TAG = "SidekickSpeech"
    }
}
