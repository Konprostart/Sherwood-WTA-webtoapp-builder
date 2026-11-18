package com.sherwood.wta.core.sidekick

import android.content.Context
import com.google.gson.JsonParser
import com.sherwood.wta.core.agent.engine.collectWithIdleTimeout
import com.sherwood.wta.core.agent.llm.ChatRequest
import com.sherwood.wta.core.agent.llm.DefaultLlmGateway
import com.sherwood.wta.core.agent.llm.LlmEvent
import com.sherwood.wta.core.agent.llm.LlmGateway
import com.sherwood.wta.core.agent.llm.LlmMessage
import com.sherwood.wta.core.agent.llm.LlmToolCall
import com.sherwood.wta.core.ai.AiConfigManager
import com.sherwood.wta.core.agent.tool.ImageAttachment
import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment
import com.sherwood.wta.core.sidekick.research.SidekickResearch
import com.sherwood.wta.core.sidekick.research.SidekickResearchToolbox
import com.sherwood.wta.data.model.AiFeature
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.SavedModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** What the Sidekick needs from model settings, in one read. */
data class SidekickModelSnapshot(
    val models: List<SavedModel>,
    val keys: List<ApiKeyConfig>,
    val defaultModelId: String?
)

/**
 * Reads models, credentials and the default model.
 *
 * An interface rather than three direct [AiConfigManager] flows so the resolution rules
 * (pin, fall back, fail closed) are unit-testable without a Context or a DataStore.
 */
fun interface SidekickModelSource {
    suspend fun load(): SidekickModelSnapshot
}

private class AiConfigModelSource(private val configManager: AiConfigManager) : SidekickModelSource {
    override suspend fun load(): SidekickModelSnapshot = SidekickModelSnapshot(
        models = configManager.savedModelsFlow.first(),
        keys = configManager.apiKeysFlow.first(),
        defaultModelId = configManager.defaultModelIdFlow.first()
    )
}

/**
 * Runs a Sidekick question against whichever model the user pointed it at.
 *
 * This is deliberately NOT an [com.sherwood.wta.core.agent.engine.AgentEngine] turn: no
 * session persistence, no continuation loop, and no tools that can write anything. The one
 * capability it does have — read-only web research — is bounded here (a fixed number of
 * rounds and tool calls) so a consultation cannot turn into a long autonomous session.
 *
 * Built through [create]; the constructor takes only the two collaborators so the
 * resolution rules can be tested without a Context, a DataStore or a network.
 */
class SidekickEngine internal constructor(
    private val source: SidekickModelSource,
    private val gateway: LlmGateway
) {

    /** A resolved (model, credential) pair, ready to send. */
    data class Binding(
        val model: SavedModel,
        val key: ApiKeyConfig,
        val label: String,
        /** True when the user pinned this model instead of following the agent's. */
        val pinned: Boolean
    ) {
        /**
         * Whether this model can accept images.
         *
         * Uses [SidekickVision], not the raw capability list: the capability is a manual
         * setting and a model added from a custom endpoint defaults to text-only even when
         * it reads screenshots perfectly well.
         */
        val supportsVision: Boolean
            get() = SidekickVision.supportsImages(model)
    }

    sealed class BindingResult {
        data class Ok(val binding: Binding) : BindingResult()

        /** Nothing to call: no models configured, or none usable for chat. */
        data object MissingModel : BindingResult()

        /** A model exists but its provider credential was deleted. */
        data object MissingApiKey : BindingResult()
    }

    /**
     * Resolves the model the Sidekick should answer with.
     *
     * A pinned model wins, but only while it still exists — a stored id that was deleted
     * from AI settings falls back to the agent's model instead of pinning a dead one.
     */
    suspend fun resolve(preferredModelId: String?): BindingResult {
        // A cancelled resolve must stay cancelled: swallowing it here turned "the user
        // pressed Stop" into a "no model configured" banner.
        val snapshot = try {
            source.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            SidekickModelSnapshot(emptyList(), emptyList(), null)
        }
        if (snapshot.models.isEmpty()) return BindingResult.MissingModel

        // A pinned model must be able to answer chat. Without this check an image model
        // could be pinned and every question would fail at the provider instead of
        // falling back to a model that can answer.
        val pinned = preferredModelId
            ?.takeIf { it.isNotBlank() }
            ?.let { id ->
                snapshot.models.firstOrNull { it.id == id && it.supportsFeature(AiFeature.AGENT) }
            }

        val chosen = pinned ?: followAgentModel(snapshot)
        if (chosen == null) return BindingResult.MissingModel

        val key = snapshot.keys.firstOrNull { it.id == chosen.apiKeyId }
            ?: return BindingResult.MissingApiKey

        return BindingResult.Ok(
            Binding(
                model = chosen,
                key = key,
                label = chosen.alias?.takeIf { it.isNotBlank() } ?: chosen.model.name,
                pinned = pinned != null
            )
        )
    }

    /**
     * The same choice the main agent makes, without the profile indirection: the default
     * model when it can still answer chat, otherwise the first model marked for the agent.
     */
    private fun followAgentModel(snapshot: SidekickModelSnapshot): SavedModel? {
        val chatCapable = snapshot.models.filter { it.supportsFeature(AiFeature.AGENT) }
        // No chat-capable model is a real state (only an image model configured); falling
        // back to any saved model would bind one that cannot answer.
        if (chatCapable.isEmpty()) return null
        val defaultId = snapshot.defaultModelId
        return chatCapable.firstOrNull { it.id == defaultId } ?: chatCapable.first()
    }

    /**
     * One question, streamed straight to the provider with no research and no loop.
     *
     * Kept for callers that only need the raw event stream; the panel uses [answer].
     */
    fun stream(
        binding: Binding,
        history: List<SidekickTurn>,
        question: String,
        contextDigest: String?,
        environment: String? = null,
        attachments: List<SidekickAttachment> = emptyList()
    ): Flow<LlmEvent> = gateway.chatStream(
        request(binding, history, question, contextDigest, environment, attachments)
    )

    /**
     * Answers [question], optionally using [research] for a bounded number of read rounds.
     *
     * Flow shape rather than a single string so the panel can show "searching…" while the
     * model works and can stream the final answer token by token.
     *
     * Two recoveries happen here rather than in the UI:
     *
     * - Images attached to a model that turns out to reject them: the turn is retried once
     *   with the images removed and the user is told, instead of losing the question.
     * - A tool call that fails: the failure text is handed back to the model as the tool
     *   result, so it can recover or say so. It never aborts the whole turn.
     */
    fun answer(
        binding: Binding,
        history: List<SidekickTurn>,
        question: String,
        contextDigest: String?,
        environment: String? = null,
        attachments: List<SidekickAttachment> = emptyList(),
        research: SidekickResearchToolbox? = null,
        idleTimeoutMs: Long = STREAM_IDLE_TIMEOUT_MS
    ): Flow<SidekickStreamEvent> = flow {
        var forceTextOnly = false
        var prefetch: String? = null

        // Deterministic half of research: a link in the question is read before the model
        // is asked anything, so the answer is grounded even if the model never calls a tool.
        if (research != null && research.hasLinks(question)) {
            emit(SidekickStreamEvent.Status(SidekickStreamEvent.StatusKind.READING_PAGE))
            prefetch = research.prefetch(question)
        }

        fun buildRequest(textOnly: Boolean): ChatRequest = request(
            binding = binding,
            history = history,
            question = question,
            contextDigest = contextDigest,
            environment = environment,
            attachments = attachments,
            research = research != null,
            forceTextOnly = textOnly,
            prefetch = prefetch
        )

        // Built once per attempt: `request()` mints a fresh fence nonce, so rebuilding it
        // just to read apiKey/model would produce a differently-fenced prompt than the one
        // that is actually sent.
        var template = buildRequest(forceTextOnly)
        var messages = template.messages
        val researchTools = research?.toolDeclarations.orEmpty()
        val activeResearch = research
        var rounds = 0
        var toolCallsDone = 0
        var retriedWithoutImages = false
        var toolsRejected = false

        while (true) {
            val toolsExhausted = toolCallsDone >= MAX_TOOL_CALLS
            val canUseTools = !toolsRejected && research != null &&
                rounds < MAX_RESEARCH_ROUNDS && !toolsExhausted
            val request = template.copy(
                messages = messages,
                tools = if (canUseTools) researchTools else emptyList(),
                useTools = canUseTools
            )

            val pending = LinkedHashMap<String, Pair<String, StringBuilder>>()
            var hardError: LlmEvent.Error? = null
            var emittedText = false
            var stalled = false

            try {
                gateway.chatStream(request).collectWithIdleTimeout(
                    idleTimeoutMs = idleTimeoutMs,
                    onTimeout = { stalled = true }
                ) { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            emittedText = true
                            emit(SidekickStreamEvent.Text(event.delta))
                        }
                        is LlmEvent.ToolCallBegin ->
                            pending[event.id] = event.name to StringBuilder()
                        is LlmEvent.ToolCallArgsDelta ->
                            pending[event.id]?.second?.append(event.argsDelta)
                        is LlmEvent.ToolCallEnd -> {
                            if (event.name.isNotEmpty()) {
                                val entry = pending.getOrPut(event.id) { event.name to StringBuilder() }
                                if (event.argumentsJson.length >= entry.second.length) {
                                    entry.second.clear()
                                    entry.second.append(event.argumentsJson)
                                }
                            }
                        }
                        is LlmEvent.Error -> hardError = event
                        // Thinking and lifecycle events carry no answer text.
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }

            val error = hardError
            if (error != null) {
                // A provider that rejects the image payload is recoverable: the same
                // question usually answers fine without the image, and losing the turn is
                // worse than answering around it.
                if (
                    !retriedWithoutImages && !emittedText && !forceTextOnly &&
                    attachments.any { it is SidekickImageAttachment } &&
                    looksLikeImageRejection(error.message, error.httpCode)
                ) {
                    retriedWithoutImages = true
                    forceTextOnly = true
                    emit(SidekickStreamEvent.Status(SidekickStreamEvent.StatusKind.RETRY_TEXT_ONLY))
                    template = buildRequest(true)
                    messages = template.messages
                    continue
                }
                // A provider or model that does not accept tool declarations fails the
                // whole turn otherwise; retrying the same question without tools keeps the
                // assistant usable (research simply does not happen for that model).
                if (
                    !toolsRejected && !emittedText && pending.isEmpty() && canUseTools &&
                    looksLikeToolRejection(error.message, error.httpCode)
                ) {
                    toolsRejected = true
                    continue
                }
                emit(SidekickStreamEvent.Failure(error.message))
                return@flow
            }
            if (stalled && !emittedText) {
                emit(SidekickStreamEvent.Stalled)
                return@flow
            }
            // No tool calls, or no budget left for them: this round is the answer. A model
            // that emitted calls when tools were not declared is answered as-is rather than
            // having its calls executed after the budget closed.
            if (pending.isEmpty() || !canUseTools) {
                emit(SidekickStreamEvent.Finished)
                return@flow
            }

            // The model asked for tools. Add its call turn, then every result, and loop.
            rounds++
            val calls = pending.map { (id, pair) ->
                LlmToolCall(id = id, name = pair.first, argumentsJson = sanitizeArgumentsJson(pair.second.toString()))
            }
            messages = messages + LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                content = "",
                toolCalls = calls
            )
            val remainingBudget = MAX_TOOL_CALLS - toolCallsDone
            val executed = calls.take(maxOf(remainingBudget, 0))
            val skipped = calls.drop(executed.size)
            val results = ArrayList<LlmMessage>(calls.size)
            for (call in executed) {
                toolCallsDone++
                emit(
                    SidekickStreamEvent.Status(
                        kind = if (call.name == SidekickResearch.TOOL_SEARCH) {
                            SidekickStreamEvent.StatusKind.SEARCHING
                        } else {
                            SidekickStreamEvent.StatusKind.READING_PAGE
                        }
                    )
                )
                // A tool failure becomes the tool's answer so the model can recover; a
                // cancellation is rethrown, or closing the panel would leave the loop
                // running the remaining calls.
                val text = try {
                    activeResearch?.execute(call.name, call.argumentsJson)
                        ?: "Research is unavailable."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    "The ${call.name} tool failed."
                }
                results += LlmMessage(
                    role = LlmMessage.Role.TOOL,
                    content = clipToolText(text),
                    toolCallId = call.id,
                    name = call.name
                )
            }
            for (call in skipped) {
                // Answering the call keeps the assistant/tool message pairing valid for
                // providers that reject an unanswered tool call.
                results += LlmMessage(
                    role = LlmMessage.Role.TOOL,
                    content = "Research budget exhausted for this question; answer with what you have.",
                    toolCallId = call.id,
                    name = call.name
                )
            }
            messages = messages + results
        }
    }

    /** Exposed for tests: the exact wire payload, with no provider involved. */
    internal fun request(
        binding: Binding,
        history: List<SidekickTurn>,
        question: String,
        contextDigest: String?,
        environment: String? = null,
        attachments: List<SidekickAttachment> = emptyList(),
        research: Boolean = false,
        forceTextOnly: Boolean = false,
        prefetch: String? = null
    ): ChatRequest {
        // Fail closed at the wire: a text-only model must never receive an image block it
        // cannot read, even if the panel somehow let one through.
        val acceptsImages = binding.supportsVision && !forceTextOnly
        val usable = if (acceptsImages) {
            attachments
        } else {
            attachments.filterNot { it is SidekickImageAttachment }
        }
        val sentImages = usable.count { it is SidekickImageAttachment }
        val droppedImages = attachments.count { it is SidekickImageAttachment } - sentImages
        val currentImages = usable.filterIsInstance<SidekickImageAttachment>()
        val fileBlocks = SidekickPrompt.renderTextAttachments(
            usable,
            nonce = SidekickPrompt.newFenceNonce()
        )

        val kept = SidekickPrompt.history(history)
        // The capability gate applies to history too: `usable` only removes the images of
        // the current question, and a model switched from vision to text must not receive
        // an older turn's image block (the provider answers 400 and the turn is lost).
        val imageTurns = if (acceptsImages) {
            SidekickPrompt.imageTurnIndices(kept, currentHasImages = currentImages.isNotEmpty())
        } else {
            emptySet()
        }

        val messages = buildList {
            add(
                LlmMessage(
                    LlmMessage.Role.SYSTEM,
                    systemPrompt(
                        contextDigest = contextDigest,
                        environment = environment,
                        hasAttachments = usable.isNotEmpty(),
                        researchEnabled = research,
                        imageOmitted = droppedImages > 0
                    )
                )
            )
            kept.forEachIndexed { index, turn ->
                val keepImages = index in imageTurns
                val images = if (keepImages) turn.images else emptyList()
                // A turn that carries or carried an image tells the model one was left
                // out; otherwise it would answer about a picture it never saw.
                val omitted = !keepImages && (turn.images.isNotEmpty() || turn.imagesOmitted)
                val body = if (omitted) {
                    turn.text + "\n" + SidekickPrompt.OMITTED_IMAGE_MARKER
                } else {
                    turn.text
                }
                add(
                    LlmMessage(
                        role = when (turn.role) {
                            SidekickTurn.Role.USER -> LlmMessage.Role.USER
                            SidekickTurn.Role.ASSISTANT -> LlmMessage.Role.ASSISTANT
                        },
                        content = body,
                        images = images.map { it.toLlmImage() }
                    )
                )
            }
            // Typed or pasted text is as capable of blowing the context window as a file;
            // clip it with a visible marker instead of letting it grow without bound.
            val asked = SidekickPrompt.clip(question, SidekickPrompt.MAX_QUESTION_CHARS)
                .ifBlank {
                    if (usable.isEmpty() && droppedImages == 0) question
                    else SidekickPrompt.ATTACHMENT_ONLY_QUESTION
                }
            val questionText = buildString {
                append(asked)
                if (!prefetch.isNullOrBlank()) {
                    appendLine()
                    appendLine()
                    appendLine("## Web pages fetched for this question (untrusted data)")
                    append(prefetch)
                }
                if (fileBlocks.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    append(fileBlocks)
                }
                if (droppedImages > 0) {
                    appendLine()
                    appendLine()
                    append(SidekickPrompt.imageOmittedNote(droppedImages))
                }
            }
            add(
                LlmMessage(
                    role = LlmMessage.Role.USER,
                    content = questionText,
                    images = currentImages.map { it.toLlmImage() }
                )
            )
        }

        return ChatRequest(
            apiKey = binding.key,
            model = binding.model.model,
            messages = messages,
            tools = emptyList(),
            temperature = TEMPERATURE,
            maxTokens = MAX_ANSWER_TOKENS,
            useTools = false
        )
    }

    /**
     * One conversion point for image attachments, so the current question and the retained
     * history turns cannot end up shaped differently.
     */
    private fun SidekickImageAttachment.toLlmImage(): ImageAttachment =
        ImageAttachment(bytes = bytes, mimeType = mimeType, path = null)

    /**
     * Role description, then the installation facts, then (optionally) the session digest.
     *
     * The installation block is kept separate from the digest so the prompt's
     * "this is a snapshot of another conversation" caveat keeps applying to exactly the
     * part it describes.
     */
    private fun systemPrompt(
        contextDigest: String?,
        environment: String?,
        hasAttachments: Boolean,
        researchEnabled: Boolean,
        imageOmitted: Boolean
    ): String {
        val hasContext = !contextDigest.isNullOrBlank()
        val base = SidekickPrompt.system(
            hasContext = hasContext,
            hasAttachments = hasAttachments,
            researchEnabled = researchEnabled,
            imageOmitted = imageOmitted
        )
        return buildString {
            append(base)
            if (!environment.isNullOrBlank()) {
                appendLine()
                appendLine()
                append(environment)
            }
            if (hasContext) {
                appendLine()
                appendLine()
                append(contextDigest)
            }
        }
    }

    companion object {
        /** Low: an explanation should not wander. */
        const val TEMPERATURE = 0.3f

        /**
         * A consulting answer is a few paragraphs, not a document. The cap also keeps a
         * runaway provider from billing a long generation the user did not ask for.
         */
        const val MAX_ANSWER_TOKENS = 1_600

        /** How many tool-calling rounds are allowed before the model must answer. */
        const val MAX_RESEARCH_ROUNDS = 3

        /** Total tool executions per question, across all rounds. */
        const val MAX_TOOL_CALLS = 4

        /** A tool result handed back to the model is clipped to this. */
        const val MAX_TOOL_MESSAGE_CHARS = 12_000

        /** Same idle window the agent uses: a silent provider must not hang the panel. */
        const val STREAM_IDLE_TIMEOUT_MS = 90_000L

        fun create(context: Context): SidekickEngine = SidekickEngine(
            source = AiConfigModelSource(AiConfigManager(context.applicationContext)),
            gateway = DefaultLlmGateway.create(context.applicationContext)
        )

        /**
         * Whether a provider error is specifically about the image payload.
         *
         * Only 4xx errors whose text names an image/vision/media concept qualify: a generic
         * "invalid request" must not cause a second billed request, and a 401/429 has its
         * own handling upstream.
         */
        internal fun looksLikeImageRejection(message: String?, httpCode: Int?): Boolean {
            val code = httpCode ?: return false
            if (code !in 400..499) return false
            val text = message?.lowercase() ?: return false
            return IMAGE_REJECTION_MARKERS.any { text.contains(it) }
        }

        private val IMAGE_REJECTION_MARKERS = listOf(
            "image", "vision", "multimodal", "media type", "media_type", "image_url",
            "text-only", "text only", "unsupported content", "invalid content type"
        )

        /**
         * Whether a provider error is about the `tools` field itself.
         *
         * Only 4xx: a 5xx or a rate limit is not a capability problem and retrying without
         * tools would hide the real failure.
         */
        internal fun looksLikeToolRejection(message: String?, httpCode: Int?): Boolean {
            val code = httpCode ?: return false
            if (code !in 400..499) return false
            val text = message?.lowercase() ?: return false
            return TOOL_REJECTION_MARKERS.any { text.contains(it) }
        }

        private val TOOL_REJECTION_MARKERS = listOf(
            "tool", "function calling", "function_calling", "functions"
        )

        /** Parses a possibly truncated argument stream into valid JSON; `{}` when unusable. */
        internal fun sanitizeArgumentsJson(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "{}"
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) return "{}"
            return runCatching {
                val element = JsonParser.parseString(trimmed.substring(start, end + 1))
                if (element.isJsonObject) element.toString() else "{}"
            }.getOrDefault("{}")
        }

        internal fun clipToolText(text: String): String =
            if (text.length <= MAX_TOOL_MESSAGE_CHARS) text
            else SidekickPrompt.safeTake(text, MAX_TOOL_MESSAGE_CHARS) + "\n…[tool output truncated]"
    }
}
