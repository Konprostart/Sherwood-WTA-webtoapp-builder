package com.sherwood.wta.core.agent.autoprompt

import java.util.UUID

/**
 * The task family a saved Auto Prompt belongs to.
 *
 * The category is descriptive, not behavioural: it is what the user browses by and what
 * the card shows. Running a prompt never branches on it — the prompt text and the bound
 * agent are the behaviour.
 */
enum class AutoPromptCategory {
    RESEARCH,
    CODING,
    TESTING,
    SECURITY,
    MAINTENANCE,
    CUSTOM;

    companion object {
        /** Gson can hand back an unknown or absent value; CUSTOM is the safe bucket. */
        fun fromStored(value: String?): AutoPromptCategory =
            entries.firstOrNull { it.name == value } ?: CUSTOM
    }
}

/**
 * One saved instruction the user can run against the agent.
 *
 * **A prompt never runs itself.** The master switch in [AutoPromptStore] only says
 * whether the feature may run at all; the per-prompt switch says whether that one prompt
 * may be run. The only trigger is the Run action, which hands the prompt to the agent in
 * a dedicated session. There is no scheduler in the app, so none is implied here.
 */
data class AutoPrompt(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Optional sentence shown on the card. */
    val description: String = "",
    /** The instruction sent to the agent. */
    val text: String = "",
    val enabled: Boolean = true,
    val category: AutoPromptCategory = AutoPromptCategory.CUSTOM,
    /**
     * Existing [com.sherwood.wta.core.agent.profile.AgentProfile] to run with, or null to
     * use whatever profile is active. Profiles already own the model, tools, temperature
     * and prompt addendum, so binding to one does not duplicate that configuration.
     */
    val agentProfileId: String? = null,
    /**
     * Model pinned for this prompt, or null to follow the agent profile / AI settings.
     * Resolved against the saved models; a model deleted from settings falls back rather
     * than making the prompt unrunnable.
     */
    val modelId: String? = null
) {

    fun normalized(): AutoPrompt = copy(
        id = id.trim().ifBlank { UUID.randomUUID().toString() },
        name = name.trim(),
        description = description.trim(),
        text = text.trim()
    )

    fun validationError(): ValidationError? = when {
        name.isBlank() -> ValidationError.NAME_REQUIRED
        text.isBlank() -> ValidationError.TEXT_REQUIRED
        else -> null
    }

    /**
     * Normalises a record read back from storage.
     *
     * Gson bypasses the constructor, so every field here can be null in memory even
     * though the types say otherwise — and a hand-edited or partially written document
     * must not crash the screen. The same defensive shape is used by the session models.
     */
    @Suppress("USELESS_CAST")
    fun sanitized(): AutoPrompt {
        val safeId = (id as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        return AutoPrompt(
            id = safeId,
            name = (name as? String).orEmpty(),
            description = (description as? String).orEmpty(),
            text = (text as? String).orEmpty(),
            enabled = (enabled as? Boolean) ?: true,
            category = (category as? AutoPromptCategory) ?: AutoPromptCategory.CUSTOM,
            agentProfileId = (agentProfileId as? String)?.takeIf { it.isNotBlank() },
            modelId = (modelId as? String)?.takeIf { it.isNotBlank() }
        )
    }

    enum class ValidationError { NAME_REQUIRED, TEXT_REQUIRED }
}

/**
 * Text search over a prompt list.
 *
 * Matching is case-insensitive across the three fields the user can see on the card, so
 * searching for a phrase found only in the body still finds the prompt.
 */
object AutoPromptSearch {
    fun matches(prompt: AutoPrompt, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return prompt.name.contains(needle, ignoreCase = true) ||
            prompt.description.contains(needle, ignoreCase = true) ||
            prompt.text.contains(needle, ignoreCase = true) ||
            prompt.category.name.contains(needle, ignoreCase = true)
    }

    fun filter(prompts: List<AutoPrompt>, query: String): List<AutoPrompt> =
        if (query.trim().isEmpty()) prompts else prompts.filter { matches(it, query) }
}
