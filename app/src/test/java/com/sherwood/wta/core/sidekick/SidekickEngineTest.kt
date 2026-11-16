package com.sherwood.wta.core.sidekick

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.agent.llm.LlmEvent
import com.sherwood.wta.core.agent.llm.LlmGateway
import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment
import com.sherwood.wta.core.sidekick.input.SidekickTextAttachment
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Model resolution and the wire payload are the two places a "quick assistant" can silently
 * become something else: calling the wrong account, or sending a request the provider
 * treats as an agent turn. Both are pinned here without a network or a Context.
 */
class SidekickEngineTest {

    private class FakeGateway : LlmGateway {
        val requests = mutableListOf<com.sherwood.wta.core.agent.llm.ChatRequest>()
        override fun chatStream(req: com.sherwood.wta.core.agent.llm.ChatRequest): Flow<LlmEvent> {
            requests += req
            return emptyFlow()
        }
    }

    private class FakeSource(
        private val snapshot: SidekickModelSnapshot
    ) : SidekickModelSource {
        override suspend fun load(): SidekickModelSnapshot = snapshot
    }

    private fun model(
        id: String,
        name: String = id,
        alias: String? = null,
        apiKeyId: String = "key-1",
        capability: ModelCapability = ModelCapability.TEXT
    ) = SavedModel(
        id = id,
        model = AiModel(id = id, name = name, provider = AiProvider.OPENAI),
        apiKeyId = apiKeyId,
        alias = alias,
        capabilities = listOf(capability)
    )

    private fun key(id: String = "key-1") = ApiKeyConfig(id = id, provider = AiProvider.OPENAI, apiKey = "sk-test")

    private fun engine(
        models: List<SavedModel>,
        keys: List<ApiKeyConfig> = listOf(key()),
        defaultModelId: String? = null,
        gateway: LlmGateway = FakeGateway()
    ) = SidekickEngine(
        source = FakeSource(SidekickModelSnapshot(models, keys, defaultModelId)),
        gateway = gateway
    )

    @Test
    fun `a pinned model wins over the agent default`() = runBlocking {
        val result = engine(
            models = listOf(model("a"), model("b")),
            defaultModelId = "a"
        ).resolve("b")

        val binding = (result as SidekickEngine.BindingResult.Ok).binding
        assertThat(binding.model.id).isEqualTo("b")
        assertThat(binding.pinned).isTrue()
    }

    @Test
    fun `a pinned model that no longer exists falls back to the agent model`() = runBlocking {
        val result = engine(
            models = listOf(model("a"), model("b")),
            defaultModelId = "b"
        ).resolve("deleted")

        val binding = (result as SidekickEngine.BindingResult.Ok).binding
        assertThat(binding.model.id).isEqualTo("b")
        // It is following the agent now, not the vanished pin.
        assertThat(binding.pinned).isFalse()
    }

    @Test
    fun `following the agent prefers the configured default model`() = runBlocking {
        val result = engine(
            models = listOf(model("a"), model("b"), model("c")),
            defaultModelId = "c"
        ).resolve(null)

        assertThat((result as SidekickEngine.BindingResult.Ok).binding.model.id).isEqualTo("c")
    }

    @Test
    fun `following the agent skips models that cannot chat`() = runBlocking {
        val imageOnly = model("img", capability = ModelCapability.IMAGE_GENERATION)
        val chat = model("chat")
        val result = engine(
            models = listOf(imageOnly, chat),
            defaultModelId = "img"
        ).resolve(null)

        // "img" is the default but is not agent-capable, so it must not answer questions.
        assertThat((result as SidekickEngine.BindingResult.Ok).binding.model.id).isEqualTo("chat")
    }

    @Test
    fun `no models is reported as missing instead of throwing`() = runBlocking {
        assertThat(engine(models = emptyList()).resolve(null))
            .isEqualTo(SidekickEngine.BindingResult.MissingModel)
    }

    @Test
    fun `a deleted API key is reported instead of sending a request`() = runBlocking {
        val result = engine(models = listOf(model("a")), keys = emptyList()).resolve(null)
        assertThat(result).isEqualTo(SidekickEngine.BindingResult.MissingApiKey)
    }

    @Test
    fun `the label prefers the alias and falls back to the model name`() = runBlocking {
        val aliased = engine(models = listOf(model("a", alias = "Fast"))).resolve(null)
        assertThat((aliased as SidekickEngine.BindingResult.Ok).binding.label).isEqualTo("Fast")

        val plain = engine(models = listOf(model("b", name = "GPT-4o"))).resolve(null)
        assertThat((plain as SidekickEngine.BindingResult.Ok).binding.label).isEqualTo("GPT-4o")
    }

    @Test
    fun `the request is a tool-free one-shot with the history and the question`() {
        val engine = engine(models = listOf(model("a")))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = listOf(
                SidekickTurn(SidekickTurn.Role.USER, "why did it fail?"),
                SidekickTurn(SidekickTurn.Role.ASSISTANT, "because the SDK is missing")
            ),
            question = "how do I fix it?",
            contextDigest = null
        )

        assertThat(request.useTools).isFalse()
        assertThat(request.tools).isEmpty()
        assertThat(request.temperature).isEqualTo(SidekickEngine.TEMPERATURE)
        assertThat(request.maxTokens).isEqualTo(SidekickEngine.MAX_ANSWER_TOKENS)

        assertThat(request.messages.first().role)
            .isEqualTo(com.sherwood.wta.core.agent.llm.LlmMessage.Role.SYSTEM)
        assertThat(request.messages.last().content).isEqualTo("how do I fix it?")
        assertThat(request.messages.map { it.content })
            .containsAtLeast("why did it fail?", "because the SDK is missing")
    }

    @Test
    fun `the context digest is appended to the system message, not sent as a user turn`() {
        val engine = engine(models = listOf(model("a")))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = emptyList(),
            question = "what happened?",
            contextDigest = "## Recent main-agent session\n[assistant] built the app"
        )

        val system = request.messages.first()
        assertThat(system.role).isEqualTo(com.sherwood.wta.core.agent.llm.LlmMessage.Role.SYSTEM)
        assertThat(system.content).contains("built the app")
        // Exactly one user turn: the question. Context must never look like user input.
        assertThat(request.messages.count { it.role == com.sherwood.wta.core.agent.llm.LlmMessage.Role.USER })
            .isEqualTo(1)
    }

    @Test
    fun `the installation facts ride in the system prompt, never as a user turn`() {
        val engine = engine(models = listOf(model("a")))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = emptyList(),
            question = "which host am I on?",
            contextDigest = null,
            environment = "## This installation (read-only facts)\n- Git hosting: Gitea at git.example.com"
        )

        val system = request.messages.first()
        assertThat(system.role).isEqualTo(com.sherwood.wta.core.agent.llm.LlmMessage.Role.SYSTEM)
        assertThat(system.content).contains("Gitea at git.example.com")
        // Facts about the app must never look like something the user typed.
        assertThat(request.messages.count { it.role == com.sherwood.wta.core.agent.llm.LlmMessage.Role.USER })
            .isEqualTo(1)
    }

    @Test
    fun `stream routes through the gateway once`() {
        val gateway = FakeGateway()
        val engine = engine(models = listOf(model("a")), gateway = gateway)
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        engine.stream(binding, emptyList(), "hello", null).let { flowOfAll(it) }

        assertThat(gateway.requests).hasSize(1)
        assertThat(gateway.requests.single().apiKey.id).isEqualTo("key-1")
    }

    private fun flowOfAll(flow: Flow<LlmEvent>): List<LlmEvent> = runBlocking {
        val out = mutableListOf<LlmEvent>()
        flow.collect { out += it }
        out
    }

    // ------------------------------------------------------------------ attachments

    private fun imageAttachment(name: String = "shot.jpg") =
        SidekickImageAttachment(name = name, bytes = byteArrayOf(1, 2, 3))

    @Test
    fun `images ride on the user turn as vision input`() {
        val engine = engine(models = listOf(model("m", capability = ModelCapability.MULTIMODAL)))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = emptyList(),
            question = "what is wrong here?",
            contextDigest = null,
            attachments = listOf(imageAttachment())
        )

        assertThat(binding.supportsVision).isTrue()
        val last = request.messages.last()
        assertThat(last.images).hasSize(1)
        assertThat(last.images.single().mimeType).isEqualTo("image/jpeg")
        // The vision block belongs to the question, never to the system message.
        assertThat(request.messages.first().images).isEmpty()
    }

    @Test
    fun `a text-only model never receives an image block`() {
        val engine = engine(models = listOf(model("m", capability = ModelCapability.TEXT)))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = emptyList(),
            question = "look at this",
            contextDigest = null,
            attachments = listOf(
                imageAttachment(),
                SidekickTextAttachment(name = "app.log", text = "boom")
            )
        )

        assertThat(binding.supportsVision).isFalse()
        assertThat(request.messages.last().images).isEmpty()
        // The text file is still usable, so it must survive the downgrade.
        assertThat(request.messages.last().content).contains("boom")
    }

    @Test
    fun `a text file is fenced inside the user turn`() {
        val engine = engine(models = listOf(model("m")))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = emptyList(),
            question = "why did this fail?",
            contextDigest = null,
            attachments = listOf(SidekickTextAttachment(name = "app.log", text = "NullPointerException"))
        )

        val user = request.messages.last()
        assertThat(user.content).contains("why did this fail?")
        assertThat(user.content).contains("NullPointerException")
        assertThat(user.content).contains("UNTRUSTED_FILE_DATA")
        // Exactly one user turn: the attachment is data inside it, not a second message.
        assertThat(request.messages.count { it.role == com.sherwood.wta.core.agent.llm.LlmMessage.Role.USER })
            .isEqualTo(1)
    }

    @Test
    fun `an attachment-only question still produces a non-empty user turn`() {
        val engine = engine(models = listOf(model("m", capability = ModelCapability.MULTIMODAL)))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = emptyList(),
            question = "",
            contextDigest = null,
            attachments = listOf(imageAttachment())
        )

        assertThat(request.messages.last().content)
            .isEqualTo(SidekickPrompt.ATTACHMENT_ONLY_QUESTION)
    }

    @Test
    fun `an empty question without attachments stays empty`() {
        val engine = engine(models = listOf(model("m")))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = emptyList(),
            question = "",
            contextDigest = null
        )

        // Nothing was attached, so no instruction may be invented on the user's behalf.
        assertThat(request.messages.last().content).isEmpty()
    }

    @Test
    fun `older turns lose their images and say so`() {
        val engine = engine(models = listOf(model("m", capability = ModelCapability.MULTIMODAL)))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val withImage = { label: String ->
            SidekickTurn(
                role = SidekickTurn.Role.USER,
                text = label,
                attachments = listOf(imageAttachment("$label.jpg"))
            )
        }

        val request = engine.request(
            binding = binding,
            history = listOf(withImage("one"), withImage("two"), withImage("three")),
            question = "and now?",
            contextDigest = null,
            attachments = listOf(imageAttachment("current.jpg"))
        )

        val userTurns = request.messages.filter {
            it.role == com.sherwood.wta.core.agent.llm.LlmMessage.Role.USER
        }
        // three history turns + the question
        assertThat(userTurns).hasSize(4)
        // Only "three" keeps its image alongside the current question.
        assertThat(userTurns[0].images).isEmpty()
        assertThat(userTurns[1].images).isEmpty()
        assertThat(userTurns[2].images).hasSize(1)
        assertThat(userTurns[3].images).hasSize(1)
        // The dropped image is replaced by a marker rather than vanishing silently.
        assertThat(userTurns[0].content).contains(SidekickPrompt.OMITTED_IMAGE_MARKER)
        assertThat(userTurns[1].content).contains(SidekickPrompt.OMITTED_IMAGE_MARKER)
        assertThat(userTurns[2].content).doesNotContain(SidekickPrompt.OMITTED_IMAGE_MARKER)
    }

    @Test
    fun `the attachment rule rides in the system prompt`() {
        val engine = engine(models = listOf(model("m")))
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }

        val request = engine.request(
            binding = binding,
            history = emptyList(),
            question = "read this",
            contextDigest = null,
            attachments = listOf<SidekickAttachment>(
                SidekickTextAttachment(name = "a.log", text = "x")
            )
        )

        assertThat(request.messages.first().content).contains("UNTRUSTED_FILE_DATA")
    }
}
