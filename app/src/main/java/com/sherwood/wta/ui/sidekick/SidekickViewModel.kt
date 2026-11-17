package com.sherwood.wta.ui.sidekick

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sherwood.wta.core.agent.engine.collectWithIdleTimeout
import com.sherwood.wta.core.agent.llm.LlmEvent
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.sidekick.SidekickContextSource
import com.sherwood.wta.core.sidekick.SidekickEngine
import com.sherwood.wta.core.sidekick.SidekickEnvironment
import com.sherwood.wta.core.sidekick.SidekickPrompt
import com.sherwood.wta.core.sidekick.SidekickSettings
import com.sherwood.wta.core.sidekick.SidekickTurn
import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentBudget
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentReader
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentRejection
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentResult
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment
import com.sherwood.wta.core.sidekick.input.SidekickSpeechFailure
import com.sherwood.wta.core.sidekick.input.SidekickSpeechListener
import com.sherwood.wta.core.sidekick.input.SidekickSpeechRecognizer
import com.sherwood.wta.core.sidekick.input.SidekickTextAttachment
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the floating assistant panel.
 *
 * [bindingLabel] is what the header shows; it is resolved rather than assumed so the panel
 * cannot claim to be using a model that was deleted from AI settings.
 */
data class SidekickUiState(
    val enabled: Boolean = false,
    val open: Boolean = false,
    val turns: List<SidekickTurn> = emptyList(),
    val draft: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
    val bindingLabel: String? = null,
    /**
     * Why no model can answer right now, or `null` when one can.
     *
     * A message rather than a flag: "no model configured" and "the model's API key was
     * deleted" need different fixes, and collapsing them into one boolean made the panel
     * send the user to the wrong settings screen.
     */
    val bindingError: String? = null,
    val includeContext: Boolean = true,
    val contextTitle: String? = null,
    /** Vertical anchor of the floating button as a fraction of its travel, 0 = top. */
    val fabAnchor: Float = SidekickSettings.DEFAULT_FAB_ANCHOR,

    /** Images and text files staged for the next question, cleared on send. */
    val pendingAttachments: List<SidekickAttachment> = emptyList(),
    /**
     * Whether the resolved model accepts images. Drives the attachment buttons: an
     * image into a text-only model is a guaranteed provider error, so the button is
     * disabled with a reason instead of failing after the pick.
     */
    val visionSupported: Boolean = true,

    /** Dictation state. The interim transcript is written straight into [draft]. */
    val speechListening: Boolean = false,
    /** False when no recognition service exists; the mic button then explains why. */
    val speechAvailable: Boolean = true
) {
    val canSend: Boolean
        get() = !streaming && !speechListening && (draft.isNotBlank() || pendingAttachments.isNotEmpty())
}

/**
 * Drives the Sidekick overlay.
 *
 * Kept out of [com.sherwood.wta.ui.agent.AgentViewModel] on purpose: the Sidekick lives
 * above every screen (it is hosted next to the navigation graph, not inside the agent
 * screen), and sharing the agent's view model would tie an app-wide overlay to a screen
 * that may never have been opened.
 */
class SidekickViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SidekickSettings.getInstance(application)
    private val engine = SidekickEngine.create(application)
    private val contextSource = SidekickContextSource(application)
    private val attachmentReader = SidekickAttachmentReader(application)
    private val speech = SidekickSpeechRecognizer(application)

    /** Draft as it was when dictation started; interim text is appended to it. */
    private var voiceBaseDraft: String = ""

    private val _ui = MutableStateFlow(
        SidekickUiState(
            enabled = settings.enabled,
            includeContext = settings.includeAgentContext,
            fabAnchor = settings.fabAnchor,
            speechAvailable = speech.isAvailable()
        )
    )
    val ui: StateFlow<SidekickUiState> = _ui.asStateFlow()

    private var answerJob: Job? = null

    /** Safety net for a recognizer that never delivers a final result after stop. */
    private var voiceStopJob: Job? = null
    private var detachSettingsListener: (() -> Unit)? = null

    init {
        // Enabling the assistant happens on the settings screen, which is a different
        // navigation destination in the same activity — no Activity resume fires, so the
        // overlay has to hear about the change directly.
        detachSettingsListener = settings.observe { refreshFromSettings() }
    }

    // ------------------------------------------------------------ panel

    fun open() {
        _ui.update { it.copy(open = true) }
        refreshBinding()
    }

    fun close() {
        _ui.update { it.copy(open = false) }
    }

    /** Re-reads preferences after the settings screen changed them. */
    fun refreshFromSettings() {
        _ui.update {
            it.copy(
                enabled = settings.enabled,
                includeContext = settings.includeAgentContext,
                fabAnchor = settings.fabAnchor
            )
        }
        if (_ui.value.open) refreshBinding()
    }

    /**
     * Persists where the floating button was dropped. The overlay covers every screen, so
     * "put it back where I left it" is the difference between a helper and an obstacle.
     */
    fun setFabAnchor(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        settings.fabAnchor = clamped
        _ui.update { it.copy(fabAnchor = clamped) }
    }

    private fun refreshBinding() {
        viewModelScope.launch {
            when (val result = engine.resolve(settings.modelId)) {
                is SidekickEngine.BindingResult.Ok -> _ui.update {
                    val vision = result.binding.supportsVision
                    val hadImages = it.pendingAttachments.any { a -> a is SidekickImageAttachment }
                    it.copy(
                        bindingLabel = result.binding.label,
                        bindingError = null,
                        visionSupported = vision,
                        // A model switch must not leave an unreadable image staged: the next
                        // request would be rejected by the provider. The drop is reported,
                        // because a photo quietly disappearing looks like a bug.
                        pendingAttachments = if (vision) {
                            it.pendingAttachments
                        } else {
                            it.pendingAttachments.filterNot { attachment ->
                                attachment is SidekickImageAttachment
                            }
                        },
                        error = if (!vision && hadImages) {
                            Strings.sidekickVisionUnsupported
                        } else {
                            it.error
                        }
                    )
                }
                SidekickEngine.BindingResult.MissingModel -> _ui.update {
                    it.copy(
                        bindingLabel = null,
                        bindingError = Strings.sidekickMissingModel
                    )
                }
                SidekickEngine.BindingResult.MissingApiKey -> _ui.update {
                    it.copy(
                        bindingLabel = null,
                        bindingError = Strings.sidekickMissingApiKey
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ composer

    fun setDraft(text: String) {
        _ui.update { it.copy(draft = text) }
    }

    fun fillDraft(text: String) {
        _ui.update { it.copy(draft = text, error = null) }
    }

    fun clear() {
        if (_ui.value.streaming) return
        _ui.update { it.copy(turns = emptyList(), error = null, pendingAttachments = emptyList()) }
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    /** Surfaces a message the panel generated itself (permission denied, picker cancel). */
    fun reportError(message: String) {
        _ui.update { it.copy(error = message) }
    }

    fun stop() {
        answerJob?.cancel()
        answerJob = null
        // A stopped request leaves an empty assistant placeholder behind; dropping it here
        // keeps the transcript honest instead of showing a blank bubble.
        _ui.update { state ->
            val turns = state.turns.toMutableList()
            val last = turns.lastOrNull()
            if (last != null && last.role == SidekickTurn.Role.ASSISTANT && last.text.isBlank()) {
                turns.removeAt(turns.lastIndex)
            }
            state.copy(turns = turns, streaming = false)
        }
    }

    // ------------------------------------------------------------ attachments

    /**
     * Reads picked documents and stages them.
     *
     * Each URI is read off the main thread and can be refused for a concrete reason, so the
     * panel reports the first refusal rather than silently attaching nothing. One bad pick
     * does not stop the others: a user who selects a log and a PDF should still get the log.
     */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val rejections = mutableListOf<SidekickAttachmentRejection>()
            for (uri in uris) {
                when (val result = withContext(Dispatchers.IO) { attachmentReader.read(uri) }) {
                    is SidekickAttachmentResult.Ok -> {
                        val rejection = stage(result.attachment)
                        if (rejection != null) rejections += rejection
                    }
                    is SidekickAttachmentResult.Rejected -> rejections += result.reason
                }
            }
            rejections.firstOrNull()?.let { reason ->
                _ui.update { it.copy(error = rejectionMessage(reason)) }
            }
        }
    }

    /** Stages a photo captured by the system camera. The temp file is owned by the caller. */
    fun addCameraPhoto(file: File) {
        viewModelScope.launch {
            val name = "photo-${System.currentTimeMillis()}.jpg"
            val result = withContext(Dispatchers.IO) {
                com.sherwood.wta.core.sidekick.input.SidekickImageDecoder.fromFile(file, name)
            }
            when (result) {
                is SidekickAttachmentResult.Ok -> {
                    stage(result.attachment)?.let { reason ->
                        _ui.update { it.copy(error = rejectionMessage(reason)) }
                    }
                }
                is SidekickAttachmentResult.Rejected ->
                    _ui.update { it.copy(error = rejectionMessage(result.reason)) }
            }
            runCatching { file.delete() }
        }
    }

    fun removeAttachment(index: Int) {
        _ui.update { state ->
            if (index !in state.pendingAttachments.indices) return@update state
            state.copy(
                pendingAttachments = state.pendingAttachments.filterIndexed { i, _ -> i != index }
            )
        }
    }

    /** Applies the per-message budget; returns the reason when the attachment was refused. */
    private fun stage(attachment: SidekickAttachment): SidekickAttachmentRejection? {
        // The check and the append run inside one `update`: two picks that resolve at the
        // same time (camera + picker, or two quick selections) would otherwise both see the
        // old list and both pass the budget.
        var rejection: SidekickAttachmentRejection? = null
        _ui.update { state ->
            if (attachment is SidekickImageAttachment && !state.visionSupported) {
                rejection = SidekickAttachmentRejection.VISION_UNSUPPORTED
                return@update state
            }
            val budget = SidekickAttachmentBudget.rejection(state.pendingAttachments, attachment)
            if (budget != null) {
                rejection = budget
                return@update state
            }
            val fitted = if (attachment is SidekickTextAttachment) {
                SidekickAttachmentBudget.fitText(state.pendingAttachments, attachment)
            } else {
                attachment
            }
            if (fitted == null) {
                rejection = SidekickAttachmentRejection.BUDGET
                return@update state
            }
            state.copy(pendingAttachments = state.pendingAttachments + fitted, error = null)
        }
        return rejection
    }

    private fun rejectionMessage(reason: SidekickAttachmentRejection): String = when (reason) {
        SidekickAttachmentRejection.TOO_LARGE -> Strings.sidekickAttachmentTooLarge
        SidekickAttachmentRejection.NOT_TEXT -> Strings.sidekickAttachmentNotText
        SidekickAttachmentRejection.UNREADABLE -> Strings.sidekickAttachmentUnreadable
        SidekickAttachmentRejection.EMPTY -> Strings.sidekickAttachmentEmpty
        SidekickAttachmentRejection.BUDGET -> Strings.sidekickAttachmentBudget
        SidekickAttachmentRejection.VISION_UNSUPPORTED -> Strings.sidekickVisionUnsupported
    }

    // ------------------------------------------------------------ dictation

    /**
     * Starts dictation.
     *
     * The recognizer needs `RECORD_AUDIO`; the caller requests it before calling this, and
     * the service reports a permission error if it is missing anyway.
     */
    fun startVoiceInput() {
        if (_ui.value.streaming) return
        if (!speech.isAvailable()) {
            _ui.update {
                it.copy(
                    speechAvailable = false,
                    error = Strings.sidekickSpeechUnavailable
                )
            }
            return
        }
        voiceBaseDraft = _ui.value.draft
        _ui.update { it.copy(speechListening = true, error = null) }
        val started = speech.start(
            language = settings.voiceLanguage,
            preferOnDevice = settings.preferOnDeviceSpeech,
            listener = DictationListener()
        )
        if (!started) {
            _ui.update {
                it.copy(speechListening = false, speechAvailable = speech.isAvailable())
            }
        }
    }

    /** Ends dictation and keeps the final transcript. */
    fun stopVoiceInput() {
        if (!_ui.value.speechListening) return
        speech.stop()
        // `stopListening` asks the service for a final result, but a misbehaving OEM
        // recognizer can go quiet instead of answering. Without a ceiling the panel would
        // stay in "Listening…" until the user reopens it, so the state is released here.
        voiceStopJob?.cancel()
        voiceStopJob = viewModelScope.launch {
            delay(VOICE_STOP_TIMEOUT_MS)
            if (_ui.value.speechListening) {
                speech.cancel()
                _ui.update {
                    it.copy(
                        speechListening = false,
                        draft = voiceBaseDraft,
                        error = Strings.sidekickSpeechNoMatch
                    )
                }
            }
        }
    }

    /** Ends dictation and discards the transcript. */
    fun cancelVoiceInput() {
        if (!_ui.value.speechListening) return
        voiceStopJob?.cancel()
        speech.cancel()
        _ui.update { it.copy(speechListening = false) }
    }

    private inner class DictationListener : SidekickSpeechListener {
        override fun onPartial(text: String) {
            _ui.update {
                it.copy(
                    // The interim transcript is written straight into the draft so the user
                    // reads what the recognizer heard, exactly like typing.
                    draft = composeDraft(voiceBaseDraft, text)
                )
            }
        }

        override fun onResult(text: String) {
            voiceStopJob?.cancel()
            _ui.update {
                it.copy(
                    speechListening = false,
                    draft = composeDraft(voiceBaseDraft, text)
                )
            }
        }

        override fun onFailure(failure: SidekickSpeechFailure) {
            voiceStopJob?.cancel()
            _ui.update {
                it.copy(
                    speechListening = false,
                    // The dictation may have replaced the draft with interim text; put the
                    // user's typed text back so a failed attempt loses nothing.
                    draft = voiceBaseDraft,
                    error = speechFailureMessage(failure),
                    speechAvailable = failure != SidekickSpeechFailure.NO_SERVICE
                )
            }
        }

        private fun speechFailureMessage(failure: SidekickSpeechFailure): String =
            when (failure) {
                SidekickSpeechFailure.NO_SERVICE -> Strings.sidekickSpeechUnavailable
                SidekickSpeechFailure.NETWORK -> Strings.sidekickSpeechNetwork
                SidekickSpeechFailure.NO_MATCH -> Strings.sidekickSpeechNoMatch
                SidekickSpeechFailure.PERMISSION -> Strings.sidekickSpeechPermission
                SidekickSpeechFailure.BUSY -> Strings.sidekickSpeechBusy
                SidekickSpeechFailure.LANGUAGE_UNSUPPORTED -> Strings.sidekickSpeechLanguage
                SidekickSpeechFailure.AUDIO -> Strings.sidekickSpeechAudio
                SidekickSpeechFailure.THROTTLED -> Strings.sidekickSpeechThrottled
                SidekickSpeechFailure.UNKNOWN -> Strings.sidekickSpeechFailed
            }
    }

    private fun composeDraft(base: String, spoken: String): String {
        if (spoken.isBlank()) return base
        if (base.isBlank()) return spoken
        return base.trimEnd() + " " + spoken.trim()
    }

    // ------------------------------------------------------------ asking

    fun send() {
        val state = _ui.value
        if (state.streaming || state.speechListening) return
        val question = state.draft.trim()
        val attachments = state.pendingAttachments
        // Attachments alone are a valid question: "here is the screenshot, what is wrong?"
        if (question.isEmpty() && attachments.isEmpty()) return
        ask(question, attachments)
    }

    private fun ask(question: String, attachments: List<SidekickAttachment>) {
        val hasCurrentImages = attachments.any { it is SidekickImageAttachment }
        // Keep the bytes of the newest questions only. The engine already sends at most
        // MAX_IMAGE_TURNS image blocks, but without this the transcript itself would hold
        // every screenshot of a long conversation in the view model.
        val keepHistoricalImages = (SidekickPrompt.MAX_IMAGE_TURNS - if (hasCurrentImages) 1 else 0)
            .coerceAtLeast(0)
        val history = SidekickPrompt.retainRecentImages(_ui.value.turns, keepHistoricalImages)
        val includeContext = _ui.value.includeContext

        _ui.update { state ->
            val retained = SidekickPrompt.retainRecentImages(state.turns, keepHistoricalImages)
            state.copy(
                draft = "",
                pendingAttachments = emptyList(),
                error = null,
                streaming = true,
                turns = retained +
                    SidekickTurn(SidekickTurn.Role.USER, question, attachments = attachments) +
                    SidekickTurn(SidekickTurn.Role.ASSISTANT, "")
            )
        }

        answerJob = viewModelScope.launch {
            val bindingResult = engine.resolve(settings.modelId)
            val binding = when (bindingResult) {
                is SidekickEngine.BindingResult.Ok -> bindingResult.binding
                SidekickEngine.BindingResult.MissingModel -> {
                    fail(Strings.sidekickMissingModel)
                    return@launch
                }
                SidekickEngine.BindingResult.MissingApiKey -> {
                    fail(Strings.sidekickMissingApiKey)
                    return@launch
                }
            }

            // Both blocks are local reads (preferences + DataStore); never on the main thread.
            val environment = withContext(Dispatchers.IO) {
                SidekickEnvironment.snapshot(getApplication())
            }
            // The session digest is a DataStore read plus decoding; never on the main thread.
            val digest = if (includeContext) {
                withContext(Dispatchers.IO) { contextSource.snapshot() }
            } else {
                null
            }
            _ui.update { it.copy(contextTitle = digest?.title?.takeIf { title -> title.isNotBlank() }) }

            val text = StringBuilder()
            var failure: String? = null
            var stalled = false

            try {
                engine.stream(
                    binding = binding,
                    history = history,
                    question = question,
                    contextDigest = digest?.text,
                    environment = environment,
                    attachments = attachments
                ).collectWithIdleTimeout(
                    idleTimeoutMs = STREAM_IDLE_TIMEOUT_MS,
                    onTimeout = { stalled = true }
                ) { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            text.append(event.delta)
                            replaceLastAnswer(text.toString())
                        }
                        is LlmEvent.Error -> failure = event.message
                        // Sidekick has no tools and does not need reasoning streams; the
                        // remaining events (Started/Done/tool noise) carry no answer text.
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Sidekick answer failed: ${t.javaClass.simpleName}")
                failure = t.message ?: t.javaClass.simpleName
            }

            val answer = text.toString().trim()
            when {
                stalled && answer.isEmpty() -> fail(Strings.sidekickStalled)
                failure != null && answer.isEmpty() -> fail(failure!!)
                // A stall or a provider error after a partial answer keeps the partial
                // answer: throwing it away would lose the only useful output.
                answer.isEmpty() -> fail(Strings.sidekickEmptyAnswer)
                else -> _ui.update { it.copy(streaming = false) }
            }
        }
    }

    /** Failure while a placeholder answer turn is already on screen. */
    private fun fail(message: String) {
        _ui.update { state ->
            val turns = state.turns.toMutableList()
            val last = turns.lastOrNull()
            if (last != null && last.role == SidekickTurn.Role.ASSISTANT && last.text.isBlank()) {
                turns.removeAt(turns.lastIndex)
            }
            state.copy(turns = turns, streaming = false, error = message)
        }
    }

    private fun replaceLastAnswer(answerText: String) {
        _ui.update { state ->
            val turns = state.turns.toMutableList()
            val lastIndex = turns.lastIndex
            if (lastIndex < 0) return@update state
            val last = turns[lastIndex]
            if (last.role != SidekickTurn.Role.ASSISTANT) return@update state
            turns[lastIndex] = last.copy(text = answerText)
            state.copy(turns = turns)
        }
    }

    override fun onCleared() {
        detachSettingsListener?.invoke()
        detachSettingsListener = null
        answerJob?.cancel()
        voiceStopJob?.cancel()
        // A recognizer left alive keeps an audio session open; the microphone indicator can
        // stay lit after the panel is gone.
        speech.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "SidekickViewModel"

        /** Same idle window the agent uses: a silent provider must not hang the panel. */
        private const val STREAM_IDLE_TIMEOUT_MS = 90_000L

        /** How long a stopped dictation may take to deliver its final result. */
        private const val VOICE_STOP_TIMEOUT_MS = 8_000L

        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SidekickViewModel(app) as T
        }
    }
}
