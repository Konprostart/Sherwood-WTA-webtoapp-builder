package com.sherwood.wta.core.agent.profile

import com.sherwood.wta.data.model.AiFeature
import com.sherwood.wta.data.model.SavedModel

/**
 * Picks the text model one agent turn runs on.
 *
 * The priority order is a contract, not an incidental detail: an explicit choice made
 * for this particular run wins over the active profile, which wins over the model
 * chosen in AI settings. Every step falls through to the next when the requested model
 * was deleted from settings, so a stale profile or a stale Auto Prompt never makes the
 * agent refuse to run.
 *
 * Extracted from [com.sherwood.wta.ui.agent.AgentViewModel] so the same order governs
 * the UI's pre-flight check and the actual dispatch — a pre-flight that accepted a
 * model the turn would then ignore would be worse than no pre-flight at all.
 */
object AgentModelResolver {

    fun resolve(
        models: List<SavedModel>,
        explicitModelId: String? = null,
        profileModelId: String? = null,
        defaultModelId: String? = null
    ): SavedModel? {
        val candidates = models.filter { it.supportsFeature(AiFeature.AGENT) }
        if (candidates.isEmpty()) return null

        for (requested in listOf(explicitModelId, profileModelId, defaultModelId)) {
            val id = requested?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            candidates.firstOrNull { it.id == id }?.let { return it }
        }
        return candidates.first()
    }
}
