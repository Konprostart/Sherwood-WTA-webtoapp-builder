package com.sherwood.wta.core.sidekick

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.sherwood.wta.core.agent.llm.ChatRequest
import com.sherwood.wta.core.agent.llm.LlmEvent
import com.sherwood.wta.core.agent.llm.LlmGateway
import com.sherwood.wta.core.agent.llm.LlmMessage
import com.sherwood.wta.core.agent.llm.ToolDeclaration
import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment
import com.sherwood.wta.core.sidekick.research.SidekickResearchToolbox
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The bounded research loop and its two recoveries.
 *
 * A silent agent loop is the worst failure mode for a small assistant: it can turn one
 * question into an unbounded number of billed requests. Every cap here is asserted.
 */
class SidekickAnswerLoopTest {

    private class ScriptedGateway(private val scripts: List<List<LlmEvent>>) : LlmGateway {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0

        override fun chatStream(req: ChatRequest): Flow<LlmEvent> {
            requests += req
            val script = scripts.getOrElse(index) { listOf(LlmEvent.Done(com.sherwood.wta.core.agent.llm.FinishReason.STOP)) }
            index++
            return flow { script.forEach { emit(it) } }
        }
    }

    private class FakeToolbox(
        prefetched: String? = null,
        private val results: MutableList<String> = mutableListOf()
    ) : SidekickResearchToolbox {
        val calls = mutableListOf<Pair<String, String>>()
        private val prefetchedText = prefetched

        override val toolDeclarations = listOf(
            ToolDeclaration(
                name = SidekickResearchNames.SEARCH,
                description = "search",
                parametersSchema = JsonParser.parseString("""{"type":"object"}""")
            )
        )

        override fun hasLinks(question: String): Boolean = prefetchedText != null

        override suspend fun prefetch(question: String): String? = prefetchedText

        override suspend fun execute(name: String, argumentsJson: String): String {
            calls += name to argumentsJson
            return results.removeFirstOrNull() ?: "tool-result"
        }
    }

    private fun model(id: String = "chat", vision: Boolean = false) = SavedModel(
        id = "saved-$id",
        model = AiModel(id = id, name = id, provider = AiProvider.OPENAI),
        apiKeyId = "key-1",
        capabilities = if (vision) {
            listOf(ModelCapability.TEXT, ModelCapability.MULTIMODAL)
        } else {
            listOf(ModelCapability.TEXT)
        }
    )

    private fun engineWith(gateway: LlmGateway, saved: SavedModel): Pair<SidekickEngine, SidekickEngine.Binding> {
        val snapshot = SidekickModelSnapshot(listOf(saved), listOf(key()), null)
        val engine = SidekickEngine(
            source = object : SidekickModelSource {
                override suspend fun load(): SidekickModelSnapshot = snapshot
            },
            gateway = gateway
        )
        val binding = runBlocking {
            (engine.resolve(null) as SidekickEngine.BindingResult.Ok).binding
        }
        return engine to binding
    }

    private fun key() = ApiKeyConfig(id = "key-1", provider = AiProvider.OPENAI, apiKey = "sk-test")

    private fun toolCall(id: String = "1", name: String = SidekickResearchNames.SEARCH, args: String = """{"query":"x"}"""): List<LlmEvent> = listOf(
        LlmEvent.ToolCallBegin(id, name),
        LlmEvent.ToolCallArgsDelta(id, args),
        LlmEvent.ToolCallEnd(id, name, args),
        LlmEvent.Done(com.sherwood.wta.core.agent.llm.FinishReason.TOOL_CALLS)
    )

    private fun text(value: String) = listOf(
        LlmEvent.TextDelta(value),
        LlmEvent.Done(com.sherwood.wta.core.agent.llm.FinishReason.STOP)
    )

    private fun imageAttachment() =
        SidekickImageAttachment(name = "shot.jpg", bytes = byteArrayOf(1, 2, 3))

    @Test
    fun `a tool call is executed once and the answer follows`() = runBlocking {
        val gateway = ScriptedGateway(listOf(toolCall(), text("Here it is.")))
        val toolbox = FakeToolbox()
        val (engine, binding) = engineWith(gateway, model(vision = true))

        val events = engine.answer(
            binding = binding,
            history = emptyList(),
            question = "find kotlin docs",
            contextDigest = null,
            research = toolbox
        ).toList()

        assertThat(events.filterIsInstance<SidekickStreamEvent.Text>().joinToString("") { it.delta })
            .isEqualTo("Here it is.")
        assertThat(events.last()).isEqualTo(SidekickStreamEvent.Finished)
        assertThat(toolbox.calls).hasSize(1)
        assertThat(toolbox.calls.single().first).isEqualTo(SidekickResearchNames.SEARCH)

        // Two requests: one with tools declared, one without.
        assertThat(gateway.requests).hasSize(2)
        assertThat(gateway.requests[0].tools).isNotEmpty()
        assertThat(gateway.requests[0].useTools).isTrue()
        assertThat(gateway.requests[1].tools).isEmpty()
        assertThat(gateway.requests[1].useTools).isFalse()
        // The tool result is answered with its id so the pairing stays valid.
        val toolMessage = gateway.requests[1].messages.last { it.role == LlmMessage.Role.TOOL }
        assertThat(toolMessage.toolCallId).isEqualTo("1")
        assertThat(toolMessage.content).isEqualTo("tool-result")
    }

    @Test
    fun `the total tool-call budget is enforced`() = runBlocking {
        // Every round asks for two calls; only four may ever run.
        val twoCalls = toolCall("1") + toolCall("2")
        val gateway = ScriptedGateway(
            listOf(
                twoCalls,
                twoCalls,
                twoCalls,
                text("done")
            )
        )
        val toolbox = FakeToolbox()
        val (engine, binding) = engineWith(gateway, model())

        val events = engine.answer(
            binding = binding,
            history = emptyList(),
            question = "research everything",
            contextDigest = null,
            research = toolbox
        ).toList()

        assertThat(toolbox.calls.size).isAtMost(SidekickEngine.MAX_TOOL_CALLS)
        assertThat(events.last()).isEqualTo(SidekickStreamEvent.Finished)
    }

    @Test
    fun `an image rejection is retried once without the image`() = runBlocking {
        val gateway = ScriptedGateway(
            listOf(
                listOf(LlmEvent.Error("Invalid image_url in messages", httpCode = 400)),
                text("Answered without the image.")
            )
        )
        val (engine, binding) = engineWith(gateway, model(vision = true))

        val events = engine.answer(
            binding = binding,
            history = emptyList(),
            question = "what is this?",
            contextDigest = null,
            attachments = listOf<SidekickAttachment>(imageAttachment())
        ).toList()

        assertThat(events.any {
            it is SidekickStreamEvent.Status &&
                it.kind == SidekickStreamEvent.StatusKind.RETRY_TEXT_ONLY
        }).isTrue()
        assertThat(events.any { it is SidekickStreamEvent.Text }).isTrue()
        assertThat(gateway.requests).hasSize(2)
        // The retry carries no image block at all.
        assertThat(gateway.requests[1].messages.flatMap { it.images }).isEmpty()
        assertThat(gateway.requests[0].messages.flatMap { it.images }).hasSize(1)
    }

    @Test
    fun `an image rejection with partial text is not retried`() = runBlocking {
        val gateway = ScriptedGateway(
            listOf(
                listOf(
                    LlmEvent.TextDelta("partial"),
                    LlmEvent.Error("unsupported image", httpCode = 422)
                )
            )
        )
        val (engine, binding) = engineWith(gateway, model(vision = true))

        val events = engine.answer(
            binding = binding,
            history = emptyList(),
            question = "what is this?",
            contextDigest = null,
            attachments = listOf<SidekickAttachment>(imageAttachment())
        ).toList()

        // Retrying would duplicate the partial answer, so the turn ends with the failure.
        assertThat(gateway.requests).hasSize(1)
        assertThat(events.last()).isInstanceOf(SidekickStreamEvent.Failure::class.java)
    }

    @Test
    fun `a provider failure is surfaced`() = runBlocking {
        val gateway = ScriptedGateway(listOf(listOf(LlmEvent.Error("bad key", httpCode = 401))))
        val (engine, binding) = engineWith(gateway, model())

        val events = engine.answer(
            binding = binding,
            history = emptyList(),
            question = "hi",
            contextDigest = null
        ).toList()

        assertThat(events.last()).isEqualTo(SidekickStreamEvent.Failure("bad key"))
    }

    @Test
    fun `a provider that rejects tools is retried without them`() = runBlocking {
        val gateway = ScriptedGateway(
            listOf(
                listOf(LlmEvent.Error("This model does not support tools", httpCode = 400)),
                text("Answer without research.")
            )
        )
        val (engine, binding) = engineWith(gateway, model())

        val events = engine.answer(
            binding = binding,
            history = emptyList(),
            question = "hi",
            contextDigest = null,
            research = FakeToolbox()
        ).toList()

        assertThat(events.any { it is SidekickStreamEvent.Text }).isTrue()
        assertThat(gateway.requests).hasSize(2)
        assertThat(gateway.requests[0].tools).isNotEmpty()
        // Research is dropped for the turn rather than failing the whole question.
        assertThat(gateway.requests[1].tools).isEmpty()
        assertThat(gateway.requests[1].useTools).isFalse()
    }

    @Test
    fun `pasted links are read before the first request`() = runBlocking {
        val gateway = ScriptedGateway(listOf(text("Read it.")))
        val toolbox = FakeToolbox(prefetched = "Source: repo\n<<<UNTRUSTED_WEB_DATA>>>\nbody")
        val (engine, binding) = engineWith(gateway, model())

        val events = engine.answer(
            binding = binding,
            history = emptyList(),
            question = "explain https://github.com/a/b",
            contextDigest = null,
            research = toolbox
        ).toList()

        assertThat(events.first()).isEqualTo(
            SidekickStreamEvent.Status(SidekickStreamEvent.StatusKind.READING_PAGE)
        )
        val user = gateway.requests.single().messages.last()
        assertThat(user.content).contains("Web pages fetched for this question")
        assertThat(user.content).contains("UNTRUSTED_WEB_DATA")
    }

    @Test
    fun `without research no tools are declared`() = runBlocking {
        val gateway = ScriptedGateway(listOf(text("plain answer")))
        val (engine, binding) = engineWith(gateway, model())

        engine.answer(
            binding = binding,
            history = emptyList(),
            question = "hi",
            contextDigest = null,
            research = null
        ).toList()

        assertThat(gateway.requests.single().tools).isEmpty()
        assertThat(gateway.requests.single().useTools).isFalse()
    }

    @Test
    fun `a text-only model is told the image was not sent`() = runBlocking {
        val gateway = ScriptedGateway(listOf(text("ok")))
        val (engine, binding) = engineWith(gateway, model(vision = false))

        engine.answer(
            binding = binding,
            history = emptyList(),
            question = "look",
            contextDigest = null,
            attachments = listOf<SidekickAttachment>(imageAttachment())
        ).toList()

        val first = gateway.requests.single()
        val user = first.messages.last()
        assertThat(user.images).isEmpty()
        assertThat(user.content).contains(SidekickPrompt.imageOmittedNote(1))
        // The rule that stops it guessing at the image rides in the system prompt.
        assertThat(first.messages.first().content).contains("NOT sent")
    }
}

/** Tool names, pinned once for the tests. */
private object SidekickResearchNames {
    const val SEARCH = "web_search"
}
