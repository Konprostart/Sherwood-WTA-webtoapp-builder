package com.sherwood.wta.core.agent.autoprompt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.profile.AgentProfile
import com.sherwood.wta.core.agent.profile.AgentProfileStore
import com.sherwood.wta.core.agent.session.AgentMessage
import com.sherwood.wta.core.agent.session.SessionStore
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end for the part of a run that happens before the agent screen: pre-flight,
 * session creation, persisted user message and the one-shot handoff.
 *
 * The AI runtime is faked so a run can be exercised without the Android keystore that
 * encrypts real API keys; everything else (store, session store, profiles) is real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoPromptRunnerTest {

    private lateinit var ctx: Context
    private lateinit var sessionStore: SessionStore
    private lateinit var promptStore: AutoPromptStore
    private lateinit var profileStore: AgentProfileStore

    private val model = SavedModel(
        id = "model-1",
        model = AiModel(id = "m1", name = "Model 1", provider = AiProvider.CUSTOM),
        apiKeyId = "key-1",
        capabilities = listOf(ModelCapability.TEXT)
    )
    private val otherModel = SavedModel(
        id = "model-2",
        model = AiModel(id = "m2", name = "Model 2", provider = AiProvider.CUSTOM),
        apiKeyId = "key-1",
        capabilities = listOf(ModelCapability.TEXT)
    )
    private val key = ApiKeyConfig(id = "key-1", provider = AiProvider.CUSTOM, apiKey = "k")

    private class FakeRuntime(
        private val models: List<SavedModel>,
        private val keys: List<ApiKeyConfig>,
        private val defaultModelId: String? = null
    ) : AutoPromptRuntimeConfig {
        override suspend fun models(): List<SavedModel> = models
        override suspend fun apiKeys(): List<ApiKeyConfig> = keys
        override suspend fun defaultModelId(): String? = defaultModelId
    }

    @Before
    fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        sessionStore = SessionStore(ctx, ProjectFileManager(ctx))
        sessionStore.clearAll()
        ctx.getSharedPreferences("sherwood_auto_prompts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        promptStore = AutoPromptStore(ctx)
        profileStore = AgentProfileStore.getInstance(ctx)
        profileStore.activeId = AgentProfile.DEFAULT_ID
        AutoPromptHandoff.clear()
    }

    private fun runner(
        models: List<SavedModel> = listOf(model),
        keys: List<ApiKeyConfig> = listOf(key),
        defaultModelId: String? = null
    ) = AutoPromptRunner(
        ctx,
        sessionStore,
        promptStore,
        profileStore,
        FakeRuntime(models, keys, defaultModelId)
    )

    private fun prompt(
        name: String = "Audit",
        text: String = "Check everything",
        enabled: Boolean = true,
        agentProfileId: String? = null,
        modelId: String? = null
    ) = AutoPrompt(name = name, text = text, enabled = enabled, agentProfileId = agentProfileId, modelId = modelId)

    @Test
    fun `a ready prompt creates a session holding the text and publishes the run`() = runBlocking {
        val result = runner().prepare(prompt())

        val ready = result as AutoPromptRunner.PrepareResult.Ready
        val session = sessionStore.get(ready.sessionId)!!
        assertThat(session.title).isEqualTo("Audit")
        assertThat(session.messages).hasSize(1)
        assertThat(session.messages.single().role).isEqualTo(AgentMessage.Role.USER)
        assertThat(session.messages.single().content).isEqualTo("Check everything")
        assertThat(session.config.textModelId).isEqualTo("model-1")

        val handoff = AutoPromptHandoff.consume(ready.sessionId)!!
        assertThat(handoff.promptText).isEqualTo("Check everything")
        assertThat(handoff.modelId).isEqualTo("model-1")
        assertThat(handoff.profileId).isEqualTo(AgentProfile.DEFAULT_ID)

        // The run must not leave a second request behind.
        assertThat(AutoPromptHandoff.consume(ready.sessionId)).isNull()
    }

    @Test
    fun `every run gets its own session`() = runBlocking {
        val first = runner().prepare(prompt(name = "First", text = "One")) as AutoPromptRunner.PrepareResult.Ready
        AutoPromptHandoff.clear()
        val second = runner().prepare(prompt(name = "Second", text = "Two")) as AutoPromptRunner.PrepareResult.Ready

        assertThat(first.sessionId).isNotEqualTo(second.sessionId)
        assertThat(sessionStore.sessionsFlow.first()).hasSize(2)
    }

    @Test
    fun `a switched off prompt is blocked before a session exists`() = runBlocking {
        val result = runner().prepare(prompt(enabled = false))

        assertThat((result as AutoPromptRunner.PrepareResult.Blocked).reason)
            .isEqualTo(AutoPromptRunPlan.BlockedReason.PROMPT_DISABLED)
        assertThat(sessionStore.sessionsFlow.first()).isEmpty()
        assertThat(AutoPromptHandoff.consume("anything")).isNull()
    }

    @Test
    fun `the master switch blocks every run`() = runBlocking {
        promptStore.setMasterEnabled(false).getOrThrow()

        val result = runner().prepare(prompt())

        assertThat((result as AutoPromptRunner.PrepareResult.Blocked).reason)
            .isEqualTo(AutoPromptRunPlan.BlockedReason.MASTER_DISABLED)
        assertThat(sessionStore.sessionsFlow.first()).isEmpty()
    }

    @Test
    fun `a missing API key is blocked before a session exists`() = runBlocking {
        val result = runner(keys = emptyList()).prepare(prompt())

        assertThat((result as AutoPromptRunner.PrepareResult.Blocked).reason)
            .isEqualTo(AutoPromptRunPlan.BlockedReason.NO_API_KEY)
        assertThat(sessionStore.sessionsFlow.first()).isEmpty()
    }

    @Test
    fun `a missing model is blocked before a session exists`() = runBlocking {
        val result = runner(models = emptyList()).prepare(prompt())

        assertThat((result as AutoPromptRunner.PrepareResult.Blocked).reason)
            .isEqualTo(AutoPromptRunPlan.BlockedReason.NO_MODEL)
        assertThat(sessionStore.sessionsFlow.first()).isEmpty()
    }

    @Test
    fun `a pinned model overrides the default for that run`() = runBlocking {
        val result = runner(models = listOf(model, otherModel), defaultModelId = "model-1")
            .prepare(prompt(modelId = "model-2"))

        val ready = result as AutoPromptRunner.PrepareResult.Ready
        assertThat(sessionStore.get(ready.sessionId)!!.config.textModelId).isEqualTo("model-2")
        assertThat(AutoPromptHandoff.peek(ready.sessionId)!!.modelId).isEqualTo("model-2")
    }

    @Test
    fun `a prompt bound to a deleted profile falls back to the active profile`() = runBlocking {
        val result = runner().prepare(prompt(agentProfileId = "deleted-profile"))

        val ready = result as AutoPromptRunner.PrepareResult.Ready
        assertThat(AutoPromptHandoff.peek(ready.sessionId)!!.profileId)
            .isEqualTo(AgentProfile.DEFAULT_ID)
    }
}
