package com.sherwood.wta.core.agent.autoprompt

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.agent.profile.AgentProfile
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import org.junit.Test

/**
 * Pins the pre-flight decision for one Auto Prompt run.
 *
 * This is the code that decides whether the Run button may start anything and which model,
 * API key and agent profile the turn will use — the blocked cases are the user-visible
 * "no API key", "no model", "switch is off" behaviour.
 */
class AutoPromptRunPlanTest {

    private val defaultProfile = AgentProfile.DEFAULT
    private val pinnedProfile = AgentProfile(
        id = "pinned-profile",
        name = "Pinned",
        modelId = "profile-model"
    )
    private val profiles = listOf(defaultProfile, pinnedProfile)

    private fun model(id: String): SavedModel = SavedModel(
        id = id,
        model = AiModel(id = "$id-model", name = id, provider = AiProvider.CUSTOM),
        apiKeyId = "key-1",
        capabilities = listOf(ModelCapability.TEXT)
    )

    private val models = listOf(model("default-model"), model("profile-model"), model("prompt-model"))
    private val keys = listOf(ApiKeyConfig(id = "key-1", provider = AiProvider.CUSTOM, apiKey = "k"))

    private fun prompt(
        enabled: Boolean = true,
        agentProfileId: String? = null,
        modelId: String? = null
    ) = AutoPrompt(name = "Prompt", text = "Body", enabled = enabled, agentProfileId = agentProfileId, modelId = modelId)

    private fun evaluate(
        prompt: AutoPrompt,
        masterEnabled: Boolean = true,
        models: List<SavedModel> = this.models,
        keys: List<ApiKeyConfig> = this.keys,
        profiles: List<AgentProfile> = this.profiles,
        activeProfileId: String = AgentProfile.DEFAULT_ID,
        defaultModelId: String? = "default-model"
    ) = AutoPromptRunPlan.evaluate(
        prompt = prompt,
        masterEnabled = masterEnabled,
        models = models,
        apiKeys = keys,
        profiles = profiles,
        activeProfileId = activeProfileId,
        defaultModelId = defaultModelId
    )

    @Test
    fun `the master switch blocks every run`() {
        val decision = evaluate(prompt(), masterEnabled = false)
        assertThat(decision).isInstanceOf(AutoPromptRunPlan.Decision.Blocked::class.java)
        assertThat((decision as AutoPromptRunPlan.Decision.Blocked).reason)
            .isEqualTo(AutoPromptRunPlan.BlockedReason.MASTER_DISABLED)
    }

    @Test
    fun `a switched off prompt is blocked even when the feature is on`() {
        val decision = evaluate(prompt(enabled = false))
        assertThat((decision as AutoPromptRunPlan.Decision.Blocked).reason)
            .isEqualTo(AutoPromptRunPlan.BlockedReason.PROMPT_DISABLED)
    }

    @Test
    fun `no agent capable model is reported as a missing model`() {
        val decision = evaluate(prompt(), models = emptyList())
        assertThat((decision as AutoPromptRunPlan.Decision.Blocked).reason)
            .isEqualTo(AutoPromptRunPlan.BlockedReason.NO_MODEL)
    }

    @Test
    fun `a model without a key is reported as a missing key`() {
        val decision = evaluate(prompt(), keys = emptyList())
        assertThat((decision as AutoPromptRunPlan.Decision.Blocked).reason)
            .isEqualTo(AutoPromptRunPlan.BlockedReason.NO_API_KEY)
    }

    @Test
    fun `a pinned prompt model and agent profile are what the run uses`() {
        val decision = evaluate(
            prompt(agentProfileId = "pinned-profile", modelId = "prompt-model")
        )
        val ready = decision as AutoPromptRunPlan.Decision.Ready
        assertThat(ready.model.id).isEqualTo("prompt-model")
        assertThat(ready.profile.id).isEqualTo("pinned-profile")
        assertThat(ready.apiKey.id).isEqualTo("key-1")
    }

    @Test
    fun `the active profile supplies the model when the prompt pins nothing`() {
        val decision = evaluate(
            prompt(),
            activeProfileId = "pinned-profile",
            defaultModelId = "default-model"
        )
        val ready = decision as AutoPromptRunPlan.Decision.Ready
        assertThat(ready.profile.id).isEqualTo("pinned-profile")
        assertThat(ready.model.id).isEqualTo("profile-model")
    }

    @Test
    fun `a deleted agent profile falls back to the active one`() {
        val decision = evaluate(prompt(agentProfileId = "deleted-profile"))
        val ready = decision as AutoPromptRunPlan.Decision.Ready
        assertThat(ready.profile.id).isEqualTo(AgentProfile.DEFAULT_ID)
    }

    @Test
    fun `a deleted pinned model falls back to the profile model`() {
        val decision = evaluate(
            prompt(agentProfileId = "pinned-profile", modelId = "deleted-model")
        )
        val ready = decision as AutoPromptRunPlan.Decision.Ready
        assertThat(ready.model.id).isEqualTo("profile-model")
    }

    @Test
    fun `an empty profile list still yields the built-in default`() {
        val decision = evaluate(prompt(), profiles = emptyList())
        val ready = decision as AutoPromptRunPlan.Decision.Ready
        assertThat(ready.profile).isEqualTo(AgentProfile.DEFAULT)
    }
}
