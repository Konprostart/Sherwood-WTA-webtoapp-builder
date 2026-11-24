package com.sherwood.wta.core.agent.autoprompt

import com.sherwood.wta.core.agent.profile.AgentModelResolver
import com.sherwood.wta.core.agent.profile.AgentProfile
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.SavedModel

/**
 * The per-run choices an Auto Prompt imposes on the agent turn.
 *
 * Both values are overrides, not mutations: nothing is written to the global AI settings
 * or to the active profile. Passed to the agent dispatch so a prompt pinned to a model or
 * an agent profile runs that way once, then the app is back to its normal configuration.
 */
data class AutoPromptTurnOverrides(
    val profileId: String? = null,
    val modelId: String? = null
)

/**
 * Pre-flight for one Auto Prompt run.
 *
 * The point of a separate decision object is that "can this run?" and "what will it run
 * on?" are answered by the same code the dispatch later uses, so the UI cannot promise a
 * model the turn would not actually pick. It is pure: no Android, no network — which is
 * also what makes the offline/API-key cases testable.
 *
 * A pinned model or profile that no longer exists falls through to the next choice instead
 * of blocking the run, matching how the agent already treats a stale profile.
 */
object AutoPromptRunPlan {

    enum class BlockedReason {
        /** The master switch is off; nothing may run. */
        MASTER_DISABLED,

        /** This particular prompt is switched off. */
        PROMPT_DISABLED,

        /** No saved model supports the agent feature. */
        NO_MODEL,

        /** The chosen model exists but has no API key configured. */
        NO_API_KEY
    }

    sealed class Decision {
        data class Ready(
            val model: SavedModel,
            val apiKey: ApiKeyConfig,
            val profile: AgentProfile
        ) : Decision()

        data class Blocked(val reason: BlockedReason) : Decision()
    }

    fun evaluate(
        prompt: AutoPrompt,
        masterEnabled: Boolean,
        models: List<SavedModel>,
        apiKeys: List<ApiKeyConfig>,
        profiles: List<AgentProfile>,
        activeProfileId: String,
        defaultModelId: String?
    ): Decision {
        if (!masterEnabled) return Decision.Blocked(BlockedReason.MASTER_DISABLED)
        if (!prompt.enabled) return Decision.Blocked(BlockedReason.PROMPT_DISABLED)

        val profile = selectProfile(prompt, profiles, activeProfileId)
        val model = AgentModelResolver.resolve(
            models = models,
            explicitModelId = prompt.modelId,
            profileModelId = profile.modelId,
            defaultModelId = defaultModelId
        ) ?: return Decision.Blocked(BlockedReason.NO_MODEL)

        val key = apiKeys.firstOrNull { it.id == model.apiKeyId }
            ?: return Decision.Blocked(BlockedReason.NO_API_KEY)

        return Decision.Ready(model = model, apiKey = key, profile = profile)
    }

    /**
     * The prompt's bound agent profile when it still exists, otherwise the active one,
     * otherwise the built-in default. Never returns null: a run always has a profile.
     */
    fun selectProfile(
        prompt: AutoPrompt,
        profiles: List<AgentProfile>,
        activeProfileId: String
    ): AgentProfile {
        val bound = prompt.agentProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (bound != null) return bound
        return profiles.firstOrNull { it.id == activeProfileId }
            ?: profiles.firstOrNull { it.id == AgentProfile.DEFAULT_ID }
            ?: AgentProfile.DEFAULT
    }
}
