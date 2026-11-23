package com.sherwood.wta.core.sidekick.input

import android.content.Context
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sidekick.SidekickSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One dictation session, independent of the surface that started it.
 *
 * Both microphones in the app — the floating Sidekick panel and the agent workspace
 * composer — speak to the same engines and must behave identically: the same
 * language-preserving attempt ladder, the same 120 s take ceiling, the same
 * "interim text is the composer text" behaviour, and the same teardown of the recognizer
 * and the WAV file. Keeping that policy in the view models is how the two would drift:
 * one gains a fix and the other keeps the bug. The class below owns the engines, the
 * recorder and the timing; the callers only own the text they are composing into.
 *
 * Two engines:
 * - [SidekickSpeechEngine.SYSTEM] wraps the platform `SpeechRecognizer` (no setup,
 *   partial results, but language support is whatever the phone has).
 * - [SidekickSpeechEngine.WHISPER] records a 16 kHz WAV and posts it to a
 *   Whisper-compatible server the user runs; the only option that guarantees a language
 *   regardless of installed speech packs, and it can stay on the local network.
 *
 * Threading: every method and every [Listener] callback runs on the main thread. The
 * blocking parts (WAV finalisation, upload) hop to IO inside the class, so call sites do
 * not have to know which engine is active.
 */
class VoiceDictation(
    private val context: Context,
    private val settings: SidekickSettings,
    private val scope: CoroutineScope
) {

    /** Whether dictation can run right now, and why not when it cannot. */
    data class Availability(val available: Boolean, val hint: String?)

    /**
     * A live session's callbacks.
     *
     * [onPartial] may fire many times; [onResult] and [onFailure] are terminal and
     * exactly one of them fires per session that delivered [onListening].
     */
    interface Listener {
        fun onListening()
        fun onPartial(spoken: String)
        fun onTranscribing()
        fun onResult(spoken: String)
        fun onFailure(failure: SidekickSpeechFailure)
    }

    private val speech = SidekickSpeechRecognizer(context)
    private val endpointStore = SidekickSpeechEndpointStore.getInstance(context)
    private val transcriber = SidekickWhisperTranscriber()

    /** The take in progress, when the Whisper engine is selected. */
    private var recorder: SidekickWavRecorder? = null

    /** The engine the live session started with; settings may change mid-session. */
    private var activeEngine: SidekickSpeechEngine? = null

    private var listener: Listener? = null

    private var transcribeJob: Job? = null

    /** Safety net for a recognizer that never delivers a final result after stop. */
    private var stopJob: Job? = null

    /**
     * Takes whose upload has already started.
     *
     * The stop button and the 120 s ceiling can fire almost together; without this the
     * same file would be uploaded twice and the transcript appended twice.
     */
    private val claimedTakes = ConcurrentHashMap.newKeySet<SidekickWavRecorder>()

    private var listening = false
    private var transcribing = false

    /** True while a session is listening or transcribing. */
    fun isActive(): Boolean = listening || transcribing

    /**
     * Whether the selected engine can run right now, and why not when it cannot.
     *
     * For the platform recognizer this is "is there a recognition service"; for Whisper
     * it is "is a server configured and its URL still acceptable". Neither decrypts the
     * API key — that happens on IO when a take is actually uploaded.
     */
    fun availability(): Availability = when (settings.speechEngine) {
        SidekickSpeechEngine.SYSTEM ->
            if (speech.isAvailable()) Availability(true, null)
            else Availability(false, Strings.sidekickSpeechUnavailable)

        SidekickSpeechEngine.WHISPER ->
            if (endpointStore.isUsable()) Availability(true, null)
            else Availability(false, Strings.sidekickSpeechNotConfigured)
    }

    /**
     * Starts listening with the configured engine.
     *
     * Returns `false` when a session is already running or the engine cannot start; in
     * the latter case [Listener.onFailure] has already been told why, so the caller only
     * has to restore its state — not invent an error message.
     */
    fun start(listener: Listener): Boolean {
        if (listening || transcribing) return false
        this.listener = listener
        return when (settings.speechEngine) {
            SidekickSpeechEngine.SYSTEM -> startSystem(listener)
            SidekickSpeechEngine.WHISPER -> startWhisper(listener)
        }
    }

    /** Ends dictation and keeps the final transcript. */
    fun stop() {
        if (transcribing || !listening) return
        when (activeEngine) {
            SidekickSpeechEngine.WHISPER -> stopWhisper()
            else -> stopSystem()
        }
    }

    /** Ends dictation and discards the transcript (or the take, for Whisper). */
    fun cancel() {
        stopJob?.cancel()
        stopJob = null
        transcribeJob?.cancel()
        transcribeJob = null
        speech.cancel()
        val take = recorder
        recorder = null
        // Unlinked without joining the capture thread: cancel() also runs from `onCleared`,
        // where a blocking join would stall the main thread and where the scope may already
        // be cancelled (so no coroutine could finish the job). If an upload was already
        // reading the file, deleting it is exactly what "discard" means; its listener is
        // gone by then, so the failure is silent by design.
        take?.cancelAsync()
        listening = false
        transcribing = false
        activeEngine = null
        listener = null
    }

    /** Releases the recognizer for good. Safe to call more than once. */
    fun release() {
        cancel()
        speech.release()
    }

    // ------------------------------------------------------------ platform recognizer

    private fun startSystem(listener: Listener): Boolean {
        if (!speech.isAvailable()) {
            this.listener = null
            listener.onFailure(SidekickSpeechFailure.NO_SERVICE)
            return false
        }
        begin(SidekickSpeechEngine.SYSTEM, listener)
        val started = speech.start(
            language = settings.voiceLanguage,
            preferOnDevice = settings.preferOnDeviceSpeech,
            listener = SpeechBridge()
        )
        if (!started && listening) {
            // Failed before the first callback: make the state honest again.
            listening = false
            activeEngine = null
        }
        return started
    }

    private fun stopSystem() {
        speech.stop()
        // `stopListening` asks the service for a final result, but a misbehaving OEM
        // recognizer can go quiet instead of answering. Without a ceiling the surface
        // would stay in "Listening…" until it is reopened, so the state is released here.
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(VOICE_STOP_TIMEOUT_MS)
            if (listening) {
                speech.cancel()
                deliverFailure(SidekickSpeechFailure.NO_MATCH)
            }
        }
    }

    // ------------------------------------------------------------ Whisper server

    private fun startWhisper(listener: Listener): Boolean {
        if (!endpointStore.isUsable()) {
            this.listener = null
            listener.onFailure(SidekickSpeechFailure.NOT_CONFIGURED)
            return false
        }
        val take = SidekickWavRecorder(context)
        val failure = take.start(
            onLimitReached = {
                // Capture thread: the recorder has already stopped itself at the ceiling.
                scope.launch { finishWhisperTake(take) }
            }
        )
        if (failure != null) {
            take.cancel()
            this.listener = null
            listener.onFailure(failure)
            return false
        }
        recorder = take
        begin(SidekickSpeechEngine.WHISPER, listener)
        return true
    }

    private fun stopWhisper() {
        val take = recorder ?: return
        recorder = null
        listening = false
        transcribing = true
        listener?.onTranscribing()
        transcribeJob = scope.launch { finishWhisperTake(take) }
    }

    /**
     * Finalizes the take and uploads it.
     *
     * `take.stop()` joins the capture thread and the endpoint read unwraps the API key
     * from the Keystore — both belong on IO, not on the main thread this is launched from.
     */
    private suspend fun finishWhisperTake(take: SidekickWavRecorder) {
        if (!claimedTakes.add(take)) return
        // The take is now owned by this coroutine, not by the next stop/cancel call.
        if (recorder === take) recorder = null
        var leftover: File? = null
        try {
            if (listening) {
                listening = false
                transcribing = true
                listener?.onTranscribing()
            }
            // Stopping is not cancellable on purpose. If the surface closes in the same
            // breath as the stop, a cancelled coroutine would leave the capture thread
            // recording until the 120 s ceiling with the microphone open and no UI.
            val file = withContext(NonCancellable + Dispatchers.IO) { take.stop() }
            leftover = file
            if (file == null) {
                deliverFailure(SidekickSpeechFailure.NO_MATCH)
                return
            }
            val endpoint = withContext(Dispatchers.IO) { endpointStore.endpoint() }
            if (endpoint == null) {
                deliverFailure(SidekickSpeechFailure.NOT_CONFIGURED)
                return
            }
            transcriber.transcribe(endpoint, file, settings.voiceLanguage).fold(
                onSuccess = { text -> deliverResult(text) },
                onFailure = { error ->
                    // A cancelled upload must not be reported as a transcription failure.
                    if (error !is CancellationException) {
                        deliverFailure(error.toSpeechFailure())
                    }
                }
            )
        } finally {
            leftover?.let { file ->
                withContext(NonCancellable + Dispatchers.IO) { runCatching { file.delete() } }
            }
            claimedTakes.remove(take)
        }
    }

    // ------------------------------------------------------------ session plumbing

    private fun begin(engine: SidekickSpeechEngine, listener: Listener) {
        activeEngine = engine
        listening = true
        transcribing = false
        listener.onListening()
    }

    private fun deliverResult(spoken: String) {
        val pending = listener ?: return
        finishSession()
        pending.onResult(spoken)
    }

    private fun deliverFailure(failure: SidekickSpeechFailure) {
        val pending = listener ?: return
        finishSession()
        pending.onFailure(failure)
    }

    private fun finishSession() {
        stopJob?.cancel()
        stopJob = null
        listening = false
        transcribing = false
        activeEngine = null
        listener = null
    }

    /** Bridges the recognizer callbacks onto the listener, with state kept honest. */
    private inner class SpeechBridge : SidekickSpeechListener {

        override fun onPartial(text: String) {
            if (!listening) return
            listener?.onPartial(text)
        }

        override fun onResult(text: String) {
            if (!listening) return
            deliverResult(text)
        }

        override fun onFailure(failure: SidekickSpeechFailure) {
            if (!listening) return
            deliverFailure(failure)
        }
    }

    companion object {
        /** How long a stopped dictation may take to deliver its final result. */
        const val VOICE_STOP_TIMEOUT_MS = 8_000L

        /**
         * Appends [spoken] to [base] with one separating space.
         *
         * Interim transcripts replace the spoken part rather than growing it, so the
         * base text the user typed is never lost and never doubled.
         */
        fun compose(base: String, spoken: String): String {
            if (spoken.isBlank()) return base
            if (base.isBlank()) return spoken
            return base.trimEnd() + " " + spoken.trim()
        }

        /** User-facing copy for a dictation failure. */
        fun messageFor(failure: SidekickSpeechFailure): String = when (failure) {
            SidekickSpeechFailure.NO_SERVICE -> Strings.sidekickSpeechUnavailable
            SidekickSpeechFailure.NETWORK -> Strings.sidekickSpeechNetwork
            SidekickSpeechFailure.NO_MATCH -> Strings.sidekickSpeechNoMatch
            SidekickSpeechFailure.PERMISSION -> Strings.sidekickSpeechPermission
            SidekickSpeechFailure.BUSY -> Strings.sidekickSpeechBusy
            SidekickSpeechFailure.LANGUAGE_UNSUPPORTED -> Strings.sidekickSpeechLanguage
            SidekickSpeechFailure.AUDIO -> Strings.sidekickSpeechAudio
            SidekickSpeechFailure.THROTTLED -> Strings.sidekickSpeechThrottled
            SidekickSpeechFailure.AUTH -> Strings.sidekickSpeechAuth
            SidekickSpeechFailure.ENDPOINT -> Strings.sidekickSpeechEndpoint
            SidekickSpeechFailure.NOT_CONFIGURED -> Strings.sidekickSpeechNotConfigured
            SidekickSpeechFailure.UNKNOWN -> Strings.sidekickSpeechFailed
        }
    }
}
