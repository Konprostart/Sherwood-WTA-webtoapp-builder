package com.sherwood.wta.ui.sidekick

import android.app.Application
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
import com.sherwood.wta.core.sidekick.SidekickSettings
import com.sherwood.wta.core.sidekick.SidekickTurn
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
    val modelId: String? = null,
    /** Vertical anchor of the floating button as a fraction of its travel, 0 = top. */
    val fabAnchor: Float = SidekickSettings.DEFAULT_FAB_ANCHOR
) {
    val canSend: Boolean get() = draft.isNotBlank() && !streaming
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

    private val _ui = MutableStateFlow(
        SidekickUiState(
            enabled = settings.enabled,
            includeContext = settings.includeAgentContext,
            modelId = settings.modelId,
            fabAnchor = settings.fabAnchor
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
        _ui.update { it.copy(open = false) }
    }

    fun toggle() {
        if (_ui.value.open) close() else open()
    }

    /** Re-reads preferences after the settings screen changed them. */
    fun refreshFromSettings() {
        _ui.update {
            it.copy(
                enabled = settings.enabled,
                includeContext = settings.includeAgentContext,
                modelId = settings.modelId,
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

    fun setIncludeContext(include: Boolean) {
        settings.includeAgentContext = include
        _ui.update { it.copy(includeContext = include) }
    }

    fun setModel(modelId: String?) {
        settings.modelId = modelId
        _ui.update { it.copy(modelId = modelId) }
        refreshBinding()
    }

    private fun refreshBinding() {
        viewModelScope.launch {
            when (val result = engine.resolve(settings.modelId)) {
                is SidekickEngine.BindingResult.Ok -> _ui.update {
                    it.copy(
                        bindingLabel = result.binding.label,
                        bindingError = null
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
        _ui.update { it.copy(turns = emptyList(), error = null) }
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
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

    // ------------------------------------------------------------ asking

    fun send() {
        val state = _ui.value
        if (state.streaming) return
        val question = state.draft.trim()
        if (question.isEmpty()) return
        ask(question)
    }

    private fun ask(question: String) {
        val history = _ui.value.turns
        val includeContext = _ui.value.includeContext

        _ui.update {
            it.copy(
                draft = "",
                error = null,
                streaming = true,
                turns = it.turns +
                    SidekickTurn(SidekickTurn.Role.USER, question) +
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
                    environment = environment
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
