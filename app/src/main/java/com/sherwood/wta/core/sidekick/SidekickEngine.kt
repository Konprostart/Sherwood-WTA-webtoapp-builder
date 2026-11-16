package com.sherwood.wta.core.sidekick

import android.content.Context
import com.sherwood.wta.core.agent.llm.ChatRequest
import com.sherwood.wta.core.agent.llm.DefaultLlmGateway
import com.sherwood.wta.core.agent.llm.LlmEvent
import com.sherwood.wta.core.agent.llm.LlmGateway
import com.sherwood.wta.core.agent.llm.LlmMessage
import com.sherwood.wta.core.ai.AiConfigManager
import com.sherwood.wta.core.agent.tool.ImageAttachment
import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment
import com.sherwood.wta.data.model.AiFeature
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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
 * tools, no permission prompts, no session persistence, no continuation loop. A
 * consultation must answer or fail in one shot — anything longer belongs in the main
 * chat, where the rules and the approval dialog already live.
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
         * The panel uses it to disable the attachment buttons with a reason instead of
         * sending a payload the provider would reject with an opaque 400.
         */
        val supportsVision: Boolean
            get() = model.capabilities.contains(ModelCapability.MULTIMODAL)
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
        val snapshot = runCatching { source.load() }
            .getOrElse { SidekickModelSnapshot(emptyList(), emptyList(), null) }
        if (snapshot.models.isEmpty()) return BindingResult.MissingModel

        val pinned = preferredModelId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> snapshot.models.firstOrNull { it.id == id } }

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
        val pool = chatCapable.ifEmpty { snapshot.models }
        val defaultId = snapshot.defaultModelId
        return pool.firstOrNull { it.id == defaultId } ?: pool.firstOrNull()
    }

    /**
     * One question, one stream. The provider flows are cold `callbackFlow`s, so the
     * caller must collect this exactly once.
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

    /** Exposed for tests: the exact wire payload, with no provider involved. */
    internal fun request(
        binding: Binding,
        history: List<SidekickTurn>,
        question: String,
        contextDigest: String?,
        environment: String? = null,
        attachments: List<SidekickAttachment> = emptyList()
    ): ChatRequest {
        // Fail closed at the wire: a text-only model must never receive an image block it
        // cannot read, even if the panel somehow let one through.
        val usable = if (binding.supportsVision) {
            attachments
        } else {
            attachments.filterNot { it is SidekickImageAttachment }
        }
        val currentImages = usable.filterIsInstance<SidekickImageAttachment>()
        val fileBlocks = SidekickPrompt.renderTextAttachments(usable)

        val kept = SidekickPrompt.history(history)
        val imageTurns = SidekickPrompt.imageTurnIndices(kept, currentHasImages = currentImages.isNotEmpty())

        val messages = buildList {
            add(
                LlmMessage(
                    LlmMessage.Role.SYSTEM,
                    systemPrompt(
                        contextDigest = contextDigest,
                        environment = environment,
                        hasAttachments = usable.isNotEmpty()
                    )
                )
            )
            kept.forEachIndexed { index, turn ->
                val keepImages = index in imageTurns
                val images = if (keepImages) turn.images else emptyList()
                val body = if (!keepImages && turn.hasImages) {
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
            val asked = question.ifBlank {
                if (usable.isEmpty()) question else SidekickPrompt.ATTACHMENT_ONLY_QUESTION
            }
            val questionText = if (fileBlocks.isEmpty()) asked else asked + "\n\n" + fileBlocks
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
        hasAttachments: Boolean
    ): String {
        val hasContext = !contextDigest.isNullOrBlank()
        val base = SidekickPrompt.system(
            hasContext = hasContext,
            hasAttachments = hasAttachments
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

        fun create(context: Context): SidekickEngine = SidekickEngine(
            source = AiConfigModelSource(AiConfigManager(context.applicationContext)),
            gateway = DefaultLlmGateway.create(context.applicationContext)
        )
    }
}
