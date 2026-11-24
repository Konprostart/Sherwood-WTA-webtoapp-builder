package com.sherwood.wta.core.agent.profile

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import org.junit.Test

class AgentModelResolverTest {

    private fun model(id: String, agentCapable: Boolean = true): SavedModel = SavedModel(
        id = id,
        model = AiModel(id = "$id-model", name = id, provider = AiProvider.CUSTOM),
        apiKeyId = "key-1",
        capabilities = if (agentCapable) {
            listOf(ModelCapability.TEXT)
        } else {
            listOf(ModelCapability.IMAGE_GENERATION)
        }
    )

    @Test
    fun `an explicit model wins over the profile and the default`() {
        val resolved = AgentModelResolver.resolve(
            models = listOf(model("default"), model("profile"), model("explicit")),
            explicitModelId = "explicit",
            profileModelId = "profile",
            defaultModelId = "default"
        )
        assertThat(resolved?.id).isEqualTo("explicit")
    }

    @Test
    fun `the profile model wins when no explicit model is set`() {
        val resolved = AgentModelResolver.resolve(
            models = listOf(model("default"), model("profile")),
            profileModelId = "profile",
            defaultModelId = "default"
        )
        assertThat(resolved?.id).isEqualTo("profile")
    }

    @Test
    fun `the configured default wins when nothing else is set`() {
        val resolved = AgentModelResolver.resolve(
            models = listOf(model("first"), model("chosen")),
            defaultModelId = "chosen"
        )
        assertThat(resolved?.id).isEqualTo("chosen")
    }

    @Test
    fun `a deleted explicit model falls through to the profile`() {
        val resolved = AgentModelResolver.resolve(
            models = listOf(model("profile")),
            explicitModelId = "deleted",
            profileModelId = "profile"
        )
        assertThat(resolved?.id).isEqualTo("profile")
    }

    @Test
    fun `a deleted profile model falls through to the default`() {
        val resolved = AgentModelResolver.resolve(
            models = listOf(model("kept"), model("default")),
            profileModelId = "deleted",
            defaultModelId = "default"
        )
        assertThat(resolved?.id).isEqualTo("default")
    }

    @Test
    fun `the first agent capable model is the last resort`() {
        val resolved = AgentModelResolver.resolve(models = listOf(model("a"), model("b")))
        assertThat(resolved?.id).isEqualTo("a")
    }

    @Test
    fun `models without the agent capability are never used`() {
        val resolved = AgentModelResolver.resolve(
            models = listOf(model("image-only", agentCapable = false)),
            explicitModelId = "image-only"
        )
        assertThat(resolved).isNull()
    }

    @Test
    fun `blank ids are ignored`() {
        val resolved = AgentModelResolver.resolve(
            models = listOf(model("real")),
            explicitModelId = "   ",
            profileModelId = "",
            defaultModelId = null
        )
        assertThat(resolved?.id).isEqualTo("real")
    }
}
