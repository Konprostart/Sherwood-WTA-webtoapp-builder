package com.sherwood.wta.core.sidekick

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import org.junit.Test

/**
 * The capability checkbox is a manual setting and is wrong often enough that trusting it
 * alone made the panel refuse every screenshot. These pin the two-way rule: an explicit
 * capability always wins, and well-known vision families are recognised without it.
 */
class SidekickVisionTest {

    private fun savedModel(
        id: String,
        name: String = id,
        capabilities: List<ModelCapability> = listOf(ModelCapability.TEXT),
        modelCapabilities: List<ModelCapability> = listOf(ModelCapability.TEXT)
    ) = SavedModel(
        id = "saved-$id",
        model = AiModel(
            id = id,
            name = name,
            provider = AiProvider.OPENAI,
            capabilities = modelCapabilities
        ),
        apiKeyId = "key",
        capabilities = capabilities
    )

    @Test
    fun `an explicit multimodal capability wins immediately`() {
        assertThat(
            SidekickVision.supportsImages(
                savedModel("plain-text-model", capabilities = listOf(ModelCapability.MULTIMODAL))
            )
        ).isTrue()
    }

    @Test
    fun `the underlying model capability is honoured too`() {
        assertThat(
            SidekickVision.supportsImages(
                savedModel(
                    "plain-text-model",
                    modelCapabilities = listOf(ModelCapability.TEXT, ModelCapability.MULTIMODAL)
                )
            )
        ).isTrue()
    }

    @Test
    fun `known vision families are recognised without the checkbox`() {
        val families = listOf(
            "gpt-4o-mini",
            "gpt-5",
            "claude-3-5-sonnet",
            "gemini-2.0-flash",
            "llava:13b",
            "qwen2.5-vl-72b",
            "glm-4v",
            "llama-3.2-11b-vision",
            "moondream2",
            "pixtral-12b"
        )
        families.forEach { id ->
            assertThat(SidekickVision.supportsImages(savedModel(id)))
                .isTrue()
        }
    }

    @Test
    fun `a text-only family stays text-only`() {
        val textOnly = listOf(
            "m",
            "gpt-3.5-turbo",
            "mistral-7b-instruct",
            "llama-3.1-70b",
            "deepseek-chat",
            "qwen2.5-72b"
        )
        textOnly.forEach { id ->
            assertThat(SidekickVision.supportsImages(savedModel(id)))
                .isFalse()
        }
    }

    @Test
    fun `the display name is considered when the id is opaque`() {
        assertThat(
            SidekickVision.supportsImages(
                savedModel("unknown-id-1", name = "Llama 3.2 Vision 11B")
            )
        ).isTrue()
    }

    @Test
    fun `an empty identifier is not a vision match`() {
        assertThat(SidekickVision.looksLikeVisionModel(null)).isFalse()
        assertThat(SidekickVision.looksLikeVisionModel("")).isFalse()
        assertThat(SidekickVision.looksLikeVisionModel("   ")).isFalse()
    }
}
