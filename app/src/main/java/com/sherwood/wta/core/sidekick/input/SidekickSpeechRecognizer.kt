package com.sherwood.wta.core.sidekick.input

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * When the user prefers on-device recognition and the platform has an on-device recognizer
 * (API 31+), that recognizer is created directly; otherwise the default one is used with
 * `EXTRA_PREFER_OFFLINE` as a hint.
 *
 * Lifetime: one session at a time, released after every terminal event. A recognizer left
 * alive holds an audio session open, which on some devices keeps the microphone indicator
 * lit after the panel closes — so [release] is called from `onCleared` and from every
 * terminal callback.
 *
 * All methods must be called from the main thread; `SpeechRecognizer` requires it.
 */
class SidekickSpeechRecognizer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listener: SidekickSpeechListener? = null
    private var terminalDelivered = false

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
        cancelInternal(notify = false)
        this.listener = listener
        terminalDelivered = false

        val created = createRecognizer(preferOnDevice)
        if (created == null) {
            this.listener = null
            listener.onFailure(SidekickSpeechFailure.NO_SERVICE)
            return false
        }
        recognizer = created
        created.setRecognitionListener(Callback())
        val intent = buildIntent(language, preferOnDevice)
        return try {
            created.startListening(intent)
            true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "startListening failed: ${t.javaClass.simpleName}")
            releaseRecognizer()
            this.listener = null
            listener.onFailure(SidekickSpeechFailure.UNKNOWN)
            false
        }
    }

    /** Stops listening and lets the service deliver its final result. */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    /** Abandons the session; any pending result is ignored. */
    fun cancel() = cancelInternal(notify = false)

    /** Releases the recognizer for good. Safe to call more than once. */
    fun release() {
        listener = null
        releaseRecognizer()
    }

    private fun cancelInternal(notify: Boolean) {
        val pending = listener
        listener = null
        terminalDelivered = true
        if (notify && pending != null) {
            main.post { pending.onFailure(SidekickSpeechFailure.NO_MATCH) }
        }
        releaseRecognizer()
    }

    private fun createRecognizer(preferOnDevice: Boolean): SpeechRecognizer? {
        val appContext = context.applicationContext
        return try {
            if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "createSpeechRecognizer failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun buildIntent(language: SidekickVoiceLanguage, preferOnDevice: Boolean): Intent =
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
            if (preferOnDevice) {
                // Only a hint for the system service; it is not a guarantee that the audio
                // stays on the device, which is why the UI says "request" rather than
                // "offline".
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            val tag = language.tag
            if (tag != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull()
                if (locale != null && locale.language.isNotEmpty()) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.language)
                }
            } else if (Build.VERSION.SDK_INT >= 34) {
                // Auto: let the service decide when it can report the language it used.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED, true)
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
            terminalDelivered = true
            val pending = listener
            listener = null
            releaseRecognizer()
            pending?.onFailure(mapError(error))
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

        else -> SidekickSpeechFailure.UNKNOWN
    }

    companion object {
        private const val TAG = "SidekickSpeech"
    }
}
