package com.sherwood.wta.ui.sidekick

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.sidekick.SidekickContextSource
import com.sherwood.wta.core.sidekick.SidekickEngine
import com.sherwood.wta.core.sidekick.SidekickEnvironment
import com.sherwood.wta.core.sidekick.SidekickPrompt
import com.sherwood.wta.core.sidekick.SidekickSettings
import com.sherwood.wta.core.sidekick.SidekickStreamEvent
import com.sherwood.wta.core.sidekick.SidekickTurn
import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentBudget
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentReader
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentRejection
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentResult
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment
import com.sherwood.wta.core.sidekick.input.SidekickSpeechFailure
import com.sherwood.wta.core.sidekick.input.SidekickTextAttachment
import com.sherwood.wta.core.sidekick.input.VoiceDictation
import com.sherwood.wta.core.sidekick.research.SidekickHttp
import com.sherwood.wta.core.sidekick.research.SidekickResearch
import com.sherwood.wta.core.sidekick.research.SidekickSearch
import com.sherwood.wta.core.sidekick.research.SidekickWebPolicy
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
     * Whether the resolved model can accept images.
     *
     * No longer gates the attachment buttons — refusing the pick was the bug. It only
     * drives [imagesWillBeOmitted], the hint that says an image will not be sent, and the
     * engine's decision to drop image bytes from the request.
     */
    val visionSupported: Boolean = true,

    /** Dictation state. The interim transcript is written straight into [draft]. */
    val speechListening: Boolean = false,
    /**
     * A Whisper take is being uploaded and transcribed. The engine has no partial
     * results, so this is the only honest state between "stopped" and "got text".
     */
    val speechTranscribing: Boolean = false,
    /**
     * Whether the assistant may read the public web for this question.
     *
     * Surfaced so the panel can show the globe as on/off without a second preference read
     * on every recomposition.
     */
    val researchEnabled: Boolean = true,
    /** The read-only research step currently running, if any. */
    val researchStatus: SidekickStreamEvent.StatusKind? = null,

    /** False when no engine can dictate right now. */
    val speechAvailable: Boolean = true,
    /**
     * Why dictation is unavailable, in the panel's own words. A disabled microphone
     * with no reason reads as a broken button, and the fix differs per engine.
     */
    val speechHint: String? = null
) {
    val canSend: Boolean
        get() = !streaming && !speechListening && !speechTranscribing &&
            (draft.isNotBlank() || pendingAttachments.isNotEmpty())

    /**
     * True when the staged images cannot be read by the bound model.
     *
     * The panel uses this for a hint, not a gate: blocking the attach was the bug, so the
     * image is accepted and this only explains what will happen to it.
     */
    val imagesWillBeOmitted: Boolean
        get() = !visionSupported && pendingAttachments.any { it is SidekickImageAttachment }
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
    private val dictation = VoiceDictation(application, settings, viewModelScope)
    private val researchHttp = SidekickHttp()

    /** Draft as it was when dictation started; interim text is appended to it. */
    private var voiceBaseDraft: String = ""

    private val initialSpeech = speechStatus()

    private val _ui = MutableStateFlow(
        SidekickUiState(
            enabled = settings.enabled,
            includeContext = settings.includeAgentContext,
            fabAnchor = settings.fabAnchor,
            speechAvailable = initialSpeech.available,
            speechHint = initialSpeech.hint,
            researchEnabled = settings.webResearchEnabled
        )
    )
    val ui: StateFlow<SidekickUiState> = _ui.asStateFlow()

    private var answerJob: Job? = null

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
        // Closing the panel must also end dictation: the recognizer holds an audio
        // session, so leaving it running keeps the microphone indicator lit on a screen
        // where the user can no longer see or stop the recording.
        if (_ui.value.speechListening || _ui.value.speechTranscribing) cancelVoiceInput()
        _ui.update { it.copy(open = false) }
    }

    /**
     * Called when the app leaves the foreground.
     *
     * A dictation that keeps running behind another app holds the microphone with no UI
     * to stop it, so the session is asked for its final transcript instead.
     */
    fun onAppBackgrounded() {
        if (_ui.value.speechListening) stopVoiceInput()
    }

    /** Re-reads preferences after the settings screen changed them. */
    fun refreshFromSettings() {
        val status = speechStatus()
        _ui.update {
            it.copy(
                enabled = settings.enabled,
                includeContext = settings.includeAgentContext,
                fabAnchor = settings.fabAnchor,
                // Switching dictation engines (or saving a new server URL) changes
                // whether the mic can work at all; the panel must not keep an old answer.
                speechAvailable = status.available,
                speechHint = status.hint,
                researchEnabled = settings.webResearchEnabled
            )
        }
        if (_ui.value.open) refreshBinding()
    }

    /** Outcome of checking whether the selected dictation engine can run. */
    private data class SpeechStatus(val available: Boolean, val hint: String?)

    /**
     * Whether the selected dictation engine can run right now, and why not when it cannot.
     *
     * The engine-specific rule (service present vs. server configured) lives in
     * [VoiceDictation] so the panel and the agent workspace cannot disagree about it.
     */
    private fun speechStatus(): SpeechStatus {
        val availability = dictation.availability()
        return SpeechStatus(availability.available, availability.hint)
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
                    // Whatever is staged stays staged. Dropping a screenshot because the
                    // bound model happens to be text-only is exactly what made attaching
                    // feel broken; the panel explains the omission instead, and the engine
                    // leaves the image out of the request.
                    it.copy(
                        bindingLabel = result.binding.label,
                        bindingError = null,
                        visionSupported = result.binding.supportsVision
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
            state.copy(turns = turns, streaming = false, researchStatus = null)
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
    }

    // ------------------------------------------------------------ dictation

    /**
     * Starts dictation with the selected engine.
     *
     * Both engines need `RECORD_AUDIO`; the caller requests it before calling this, and
     * each path reports a permission failure if it is missing anyway.
     */
    fun startVoiceInput() {
        val state = _ui.value
        if (state.streaming || state.speechListening || state.speechTranscribing) return
        voiceBaseDraft = state.draft
        _ui.update { it.copy(speechListening = true, error = null) }
        if (!dictation.start(DictationBridge())) {
            // The controller already reported why; only the availability flag can still be
            // stale (a service uninstalled, a server URL removed).
            val availability = dictation.availability()
            _ui.update { it.copy(speechListening = false, speechAvailable = availability.available) }
        }
    }

    /** Ends dictation and keeps the final transcript. */
    fun stopVoiceInput() {
        if (!dictation.isActive()) return
        dictation.stop()
    }

    /** Ends dictation and discards the transcript (or the take, for Whisper). */
    fun cancelVoiceInput() {
        val state = _ui.value
        if (!state.speechListening && !state.speechTranscribing) return
        dictation.cancel()
        _ui.update { it.copy(speechListening = false, speechTranscribing = false) }
    }

    /**
     * Bridges controller callbacks into the panel state.
     *
     * One listener for both engines: the panel does not need to know which engine produced
     * the text, and the previous split (one set of handlers per engine) is how the two
     * paths drifted apart.
     */
    private inner class DictationBridge : VoiceDictation.Listener {
        override fun onListening() {
            _ui.update {
                it.copy(speechListening = true, speechTranscribing = false, error = null)
            }
        }

        override fun onPartial(spoken: String) {
            _ui.update {
                it.copy(
                    // The interim transcript is written straight into the draft so the user
                    // reads what the recognizer heard, exactly like typing.
                    draft = VoiceDictation.compose(voiceBaseDraft, spoken)
                )
            }
        }

        override fun onTranscribing() {
            _ui.update { it.copy(speechListening = false, speechTranscribing = true) }
        }

        override fun onResult(spoken: String) {
            _ui.update {
                it.copy(
                    speechListening = false,
                    speechTranscribing = false,
                    draft = VoiceDictation.compose(voiceBaseDraft, spoken)
                )
            }
        }

        override fun onFailure(failure: SidekickSpeechFailure) {
            _ui.update {
                it.copy(
                    speechListening = false,
                    speechTranscribing = false,
                    // The dictation may have replaced the draft with interim text; put the
                    // user's typed text back so a failed attempt loses nothing.
                    draft = voiceBaseDraft,
                    error = VoiceDictation.messageFor(failure),
                    speechAvailable = failure != SidekickSpeechFailure.NO_SERVICE
                )
            }
        }
    }

    // ------------------------------------------------------------ asking

    fun send() {
        val state = _ui.value
        if (state.streaming || state.speechListening || state.speechTranscribing) return
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
                engine.answer(
                    binding = binding,
                    history = history,
                    question = question,
                    contextDigest = digest?.text,
                    environment = environment,
                    attachments = attachments,
                    research = researchForThisTurn(),
                    idleTimeoutMs = STREAM_IDLE_TIMEOUT_MS
                ).collect { event ->
                    when (event) {
                        is SidekickStreamEvent.Text -> {
                            text.append(event.delta)
                            replaceLastAnswer(text.toString())
                        }
                        // Shown while a search or a page read runs; a silent second of
                        // network work reads as a frozen panel.
                        is SidekickStreamEvent.Status ->
                            _ui.update { it.copy(researchStatus = event.kind) }
                        is SidekickStreamEvent.Failure -> failure = event.message
                        SidekickStreamEvent.Stalled -> stalled = true
                        SidekickStreamEvent.Finished -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Sidekick answer failed: ${t.javaClass.simpleName}")
                failure = t.message ?: t.javaClass.simpleName
            }

            _ui.update { it.copy(researchStatus = null) }
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

    /**
     * Builds the read-only research capability for one turn, or `null` when it is off.
     *
     * A fresh instance per turn keeps the search endpoint in sync with the settings dialog
     * without holding a stale backend for the lifetime of the view model.
     */
    private fun researchForThisTurn(): SidekickResearch? {
        if (!settings.webResearchEnabled) return null
        return SidekickResearch(
            http = researchHttp,
            search = SidekickSearch(researchHttp, searxEndpoint = settings.searchEndpoint),
            searchEnabled = true
        )
    }

    /** Turns web research on or off; the panel's globe reflects the new value immediately. */
    fun setResearchEnabled(enabled: Boolean) {
        settings.webResearchEnabled = enabled
        _ui.update { it.copy(researchEnabled = enabled) }
    }

    /** Current SearXNG base URL, or `null` for the built-in DuckDuckGo backend. */
    fun searchEndpoint(): String? = settings.searchEndpoint

    /** Validates and saves an optional SearXNG base URL. Returns false when it is not allowed. */
    fun setSearchEndpoint(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            settings.searchEndpoint = null
            return true
        }
        if (SidekickWebPolicy.check(trimmed) !is SidekickWebPolicy.Verdict.Allowed) return false
        settings.searchEndpoint = trimmed
        return true
    }

    /** Failure while a placeholder answer turn is already on screen. */
    private fun fail(message: String) {
        _ui.update { state ->
            val turns = state.turns.toMutableList()
            val last = turns.lastOrNull()
            if (last != null && last.role == SidekickTurn.Role.ASSISTANT && last.text.isBlank()) {
                turns.removeAt(turns.lastIndex)
            }
            state.copy(turns = turns, streaming = false, error = message, researchStatus = null)
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
        // Cancels the live take, deletes any WAV on disk and releases the recognizer —
        // an audio session left open keeps the microphone indicator lit after the panel
        // is gone.
        dictation.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "SidekickViewModel"

        /** Same idle window the agent uses: a silent provider must not hang the panel. */
        private const val STREAM_IDLE_TIMEOUT_MS = 90_000L

        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SidekickViewModel(app) as T
        }
    }
}
