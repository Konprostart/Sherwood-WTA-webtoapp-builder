package com.sherwood.wta.core.agent.autoprompt

import android.content.Context
import com.sherwood.wta.core.agent.profile.AgentProfileStore
import com.sherwood.wta.core.agent.session.AgentMessage
import com.sherwood.wta.core.agent.session.SessionStore
import com.sherwood.wta.core.ai.AiConfigManager
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.SavedModel
import kotlinx.coroutines.flow.first

/**
 * The AI settings one Auto Prompt run needs.
 *
 * An interface rather than direct [AiConfigManager] use so the run pipeline — pre-flight,
 * session creation, handoff — is testable without the Android keystore that encrypts real
 * API keys.
 */
interface AutoPromptRuntimeConfig {
    suspend fun models(): List<SavedModel>
    suspend fun apiKeys(): List<ApiKeyConfig>
    suspend fun defaultModelId(): String?
}

/** Production wiring: reads the same flows the agent screen uses. */
class AiConfigManagerRuntimeConfig(context: Context) : AutoPromptRuntimeConfig {
    private val configManager = AiConfigManager(context.applicationContext)

    override suspend fun models(): List<SavedModel> = configManager.savedModelsFlow.first()

    override suspend fun apiKeys(): List<ApiKeyConfig> = configManager.apiKeysFlow.first()

    override suspend fun defaultModelId(): String? = configManager.defaultModelIdFlow.first()
}

/**
 * Turns "run this prompt" into a ready agent turn.
 *
 * A run always gets a **new** session: an Auto Prompt is a clean instruction, and merging
 * it into an existing conversation would make the transcript — and the model's context —
 * depend on what the user happened to have open. The session is created here, the user
 * message is persisted before anything is dispatched, and the handoff tells the agent
 * screen to start the turn. If the app dies between those steps the worst case is a
 * session holding the prompt with no reply, which the user can simply send again.
 *
 * The prompt's model and agent-profile bindings are resolved by [AutoPromptRunPlan] —
 * the same decision the dispatch applies — so a run is blocked *before* a session is
 * created when there is no usable model or API key.
 */
class AutoPromptRunner(
    context: Context,
    private val sessionStore: SessionStore,
    private val promptStore: AutoPromptStore = AutoPromptStore.getInstance(context),
    private val profileStore: AgentProfileStore = AgentProfileStore.getInstance(context),
    private val runtime: AutoPromptRuntimeConfig = AiConfigManagerRuntimeConfig(context)
) {

    sealed class PrepareResult {
        data class Ready(val sessionId: String) : PrepareResult()
        data class Blocked(val reason: AutoPromptRunPlan.BlockedReason) : PrepareResult()
    }

    suspend fun prepare(prompt: AutoPrompt): PrepareResult {
        val clean = prompt.normalized()
        val state = promptStore.state()
        val decision = AutoPromptRunPlan.evaluate(
            prompt = clean,
            masterEnabled = state.masterEnabled,
            models = runtime.models(),
            apiKeys = runtime.apiKeys(),
            profiles = profileStore.profiles(),
            activeProfileId = profileStore.activeId,
            defaultModelId = runtime.defaultModelId()
        )
        val ready = when (decision) {
            is AutoPromptRunPlan.Decision.Blocked -> return PrepareResult.Blocked(decision.reason)
            is AutoPromptRunPlan.Decision.Ready -> decision
        }

        val session = sessionStore.create(title = clean.name)
        // Record the model the turn will use on the session itself; the agent applies
        // the override explicitly, this is what makes the session's own configuration
        // truthful when it is reopened later.
        sessionStore.updateConfig(session.id, session.config.copy(textModelId = ready.model.id))
        sessionStore.appendMessage(
            session.id,
            AgentMessage(role = AgentMessage.Role.USER, content = clean.text)
        )
        AutoPromptHandoff.publish(
            AutoPromptHandoff.Request(
                sessionId = session.id,
                promptText = clean.text,
                profileId = ready.profile.id,
                modelId = ready.model.id
            )
        )
        return PrepareResult.Ready(session.id)
    }
}
