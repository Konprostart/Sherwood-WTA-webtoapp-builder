package com.sherwood.wta.core.sidekick

import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel

/**
 * Whether a saved model can accept an image block.
 *
 * The capability checkbox on the AI settings screen is the authoritative signal, but it is
 * a manual setting and it is frequently wrong in both directions: models added from a
 * custom endpoint default to `TEXT`, and older saved models predate the multimodal flag
 * entirely. Trusting the flag alone is what made the panel refuse every screenshot with
 * "this model cannot read images" even when the model plainly can.
 *
 * So the rule is:
 *
 * 1. An explicit `MULTIMODAL` capability (saved model or its [com.sherwood.wta.data.model.AiModel])
 *    wins immediately.
 * 2. Otherwise a well-known vision model family is treated as vision-capable. Sending an
 *    image to a model that turns out not to accept one is recoverable (the answer runner
 *    retries the turn without images); refusing to send one to a model that can read it is
 *    a dead end the user cannot work around from the panel.
 * 3. Everything else is text-only, and the panel says so without blocking the attach.
 */
object SidekickVision {

    fun supportsImages(model: SavedModel): Boolean {
        if (model.capabilities.contains(ModelCapability.MULTIMODAL)) return true
        if (model.model.capabilities.contains(ModelCapability.MULTIMODAL)) return true
        return looksLikeVisionModel(model.model.id) || looksLikeVisionModel(model.model.name)
    }

    /**
     * Family-prefix match rather than an exact list, so a new generation does not silently
     * lose recognition. Mirrors the inference the model-registry code uses when a model is
     * first added, which keeps the two paths from disagreeing about the same model.
     */
    internal fun looksLikeVisionModel(raw: String?): Boolean {
        val id = raw?.trim()?.lowercase() ?: return false
        if (id.isEmpty()) return false
        return VISION_MARKERS.any { id.contains(it) }
    }

    /**
     * Substrings that identify a vision-capable family across the providers this app talks
     * to. Deliberately conservative about words that appear in unrelated model names
     * (`audio` was in an older copy of this list and matched nothing useful here).
     */
    private val VISION_MARKERS = listOf(
        "vision", "multimodal", "omni",
        "gpt-4o", "gpt-4.1", "gpt-5", "gpt-4-turbo", "chatgpt-4o",
        "gemini", "gemma-3", "gemma3",
        "claude-",
        "pixtral", "llava", "bakllava", "moondream", "minicpm-v", "cogvlm",
        "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "qvq",
        "glm-4v", "glm-5", "step-1v", "step-2v", "yi-vision", "internvl",
        "moonshot-v", "kimi-v", "hunyuan-vision", "grok-2", "grok-3", "grok-4",
        "llama-3.2", "llama-4", "llama3.2", "llama4",
        "mistral-small-3", "phi-3.5-vision", "phi-4-multimodal", "phi-4-reasoning-vision",
        "idefics", "paligemma", "fuyu", "smolvlm", "granite-vision",
        "molmo", "deepseek-vl", "ernie-vl", "doubao-vision", "minimax-vl",
        "o3", "o4-mini"
    )
}
