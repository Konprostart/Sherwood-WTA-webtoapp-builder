package com.sherwood.wta.core.agent.local

import android.content.Context
import com.sherwood.wta.core.agent.profile.AgentProfile
import com.sherwood.wta.core.agent.profile.AgentProfileStore
import com.sherwood.wta.core.ai.AiConfigManager
import com.sherwood.wta.data.model.AiFeature
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import kotlinx.coroutines.flow.first

/** What a successful binding produced, for the settings screen to display. */
data class LocalModelBinding(
    val modelName: String,
    val baseUrl: String,
    val savedModelId: String,
    val profileId: String
)

/**
 * Connects a local Ollama model to the agent.
 *
 * A local model is usable only when three existing pieces line up: a provider entry (so
 * the model resolves to an endpoint), a saved model (so the agent's model picker lists
 * it), and an [AgentProfile] pinned to it (so the agent actually runs on it). This class
 * writes all three and is the only place that does.
 *
 * It never touches a cloud provider: the profile it activates points at the saved local
 * model, and the provider entry it creates exists only for the Ollama base URL the user
 * entered.
 */
class LocalAgentModels(private val context: Context) {

    private val configManager = AiConfigManager(context)
    private val profiles = AgentProfileStore.getInstance(context)
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastBaseUrl(): String =
        prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: OllamaLocalClient.DEFAULT_BASE_URL

    fun rememberBaseUrl(rawUrl: String) {
        OllamaEndpoint.parse(rawUrl)?.let {
            prefs.edit().putString(KEY_BASE_URL, it.baseUrl).apply()
        }
    }

    /** The active binding, or null when the agent is not pinned to a local model. */
    fun activeBinding(): AgentProfile? {
        val active = profiles.active()
        return active.takeIf { it.id.startsWith(PROFILE_PREFIX) && !it.modelId.isNullOrBlank() }
    }

    fun activeModelName(): String? = activeBinding()?.let { profile ->
        profile.name.removePrefix(LOCAL_NAME_PREFIX).ifBlank { profile.modelId }
    }

    suspend fun bind(rawUrl: String, model: OllamaModel): Result<LocalModelBinding> {
        val endpoint = OllamaEndpoint.parse(rawUrl)
            ?: return Result.failure(OllamaProbeFailure.InvalidUrl(rawUrl.trim()))
        if (model.name.isBlank()) {
            return Result.failure(IllegalArgumentException("Model name is required"))
        }

        val key = ensureProvider(endpoint.baseUrl) ?: return Result.failure(
            IllegalStateException("Could not save the local provider configuration")
        )
        val saved = ensureSavedModel(key, model) ?: return Result.failure(
            IllegalStateException("Could not save the local model")
        )

        val profileId = profileIdFor(model.name)
        val profile = AgentProfile(
            id = profileId,
            name = "$LOCAL_NAME_PREFIX${model.name}",
            modelId = saved.id,
            runnerId = AgentProfile.RUNNER_LLM
        )
        profiles.save(profile).getOrElse { return Result.failure(it) }
        profiles.activeId = profileId
        rememberBaseUrl(endpoint.baseUrl)

        return Result.success(
            LocalModelBinding(
                modelName = model.name,
                baseUrl = endpoint.baseUrl,
                savedModelId = saved.id,
                profileId = profileId
            )
        )
    }

    /** Puts the agent back on the model selected in AI settings. */
    fun resetToDefault() {
        profiles.activeId = AgentProfile.DEFAULT_ID
    }

    private suspend fun ensureProvider(baseUrl: String): ApiKeyConfig? {
        val existing = configManager.apiKeysFlow.first().firstOrNull { key ->
            key.provider == AiProvider.OLLAMA &&
                (key.baseUrl?.let { OllamaEndpoint.parse(it)?.baseUrl } == baseUrl)
        }
        if (existing != null) return existing
        val created = ApiKeyConfig(
            provider = AiProvider.OLLAMA,
            apiKey = "",
            baseUrl = baseUrl,
            alias = LOCAL_PROVIDER_ALIAS
        )
        return if (configManager.addApiKey(created)) created else null
    }

    private suspend fun ensureSavedModel(
        key: ApiKeyConfig,
        model: OllamaModel
    ): SavedModel? {
        val capabilities = capabilitiesFor(model)
        configManager.savedModelsFlow.first().firstOrNull {
            it.apiKeyId == key.id && it.model.id == model.name
        }?.let { return it }

        val saved = SavedModel(
            model = AiModel(
                id = model.name,
                name = model.name,
                provider = AiProvider.OLLAMA,
                capabilities = capabilities,
                // The provider is local, so the model's context is not a billing tier.
                // 8k is what the driver requests as `num_ctx`; keeping the estimate and
                // the request in step avoids sending a prompt the server would truncate.
                contextLength = LOCAL_CONTEXT_LENGTH,
                isCustom = true
            ),
            apiKeyId = key.id,
            capabilities = capabilities,
            featureMappings = mapOf(
                ModelCapability.TEXT to setOf(
                    AiFeature.AGENT,
                    AiFeature.MODULE_DEVELOPMENT,
                    AiFeature.TRANSLATION,
                    AiFeature.GENERAL
                )
            )
        )
        return if (configManager.saveModel(saved)) saved else null
    }

    private fun capabilitiesFor(model: OllamaModel): List<ModelCapability> = buildList {
        add(ModelCapability.TEXT)
        if (model.isMultimodal) add(ModelCapability.MULTIMODAL)
    }

    companion object {
        private const val PREFS = "sherwood_local_models"
        private const val KEY_BASE_URL = "base_url"
        private const val PROFILE_PREFIX = "local-"
        private const val LOCAL_NAME_PREFIX = "Local · "
        private const val LOCAL_PROVIDER_ALIAS = "Ollama (local)"
        private const val LOCAL_CONTEXT_LENGTH = 8192

        /** Stable profile id for a model, so re-binding updates one profile. */
        fun profileIdFor(modelName: String): String {
            val slug = modelName.lowercase()
                .map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '-' }
                .joinToString("")
                .trim('-', '.')
                .take(48)
                .ifBlank { "model" }
            return "$PROFILE_PREFIX$slug"
        }
    }
}
