package com.sherwood.wta.core.agent.llm

import android.util.Base64
import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sherwood.wta.core.network.NetworkModule
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.util.GsonProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID

/**
 * Local Ollama driver (`POST /api/chat`, NDJSON streaming).
 *
 * Ollama is the offline path for the agent, so this provider is deliberately strict about
 * failure: a streamed `{"error": ...}` (unknown model, model not loaded, out of memory)
 * becomes an [LlmEvent.Error] instead of an empty successful turn. Before this, a missing
 * model looked like a model that answered nothing.
 *
 * `num_ctx` is sent explicitly so the server allocates the context the app's token
 * estimator assumes; without it Ollama silently uses a small default and the tail of a
 * long conversation is dropped server-side.
 */
internal class OllamaProvider(@Suppress("UNUSED_PARAMETER") context: Context) : LlmProvider {

    private val gson = GsonProvider.gson
    private val client get() = NetworkModule.streamingClient

    override fun supports(provider: AiProvider) = provider == AiProvider.OLLAMA

    override fun chatStream(req: ChatRequest): Flow<LlmEvent> = callbackFlow {
        val url = HttpHelpers.joinUrl(HttpHelpers.baseUrl(req.apiKey), "/api/chat")
        val body = buildRequestBody(req)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))

        HttpHelpers.applyAuth(request, req.apiKey)
        trySend(LlmEvent.Started)
        // Captured so the streaming reader keeps the ProducerScope receiver when it runs
        // inside OkHttp's callback thread.
        val channel = this
        val call = client.newCall(request.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(LlmEvent.Error(e.message ?: "Network error"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    val error = HttpHelpers.llmHttpError(
                        response.code,
                        errorBody,
                        response.header("Retry-After"),
                        response.header("retry-after-ms")
                    )
                    response.body?.close()
                    trySend(error)
                    close()
                    return
                }
                try {
                    channel.readStream(response)
                } finally {
                    runCatching { response.body?.close() }
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private fun kotlinx.coroutines.channels.ProducerScope<LlmEvent>.readStream(
        response: Response
    ) {
        val source = response.body?.source()
        if (source == null) {
            trySend(LlmEvent.Error("Empty response body"))
            return
        }
        var hasTools = false
        var done = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) continue
            val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
            if (json == null) {
                // A keep-alive or a server-side notice that is not JSON; the next line
                // still carries the stream.
                continue
            }
            val streamError = json.stringOrNull("error")
            if (!streamError.isNullOrBlank()) {
                // Ollama reports model/loading failures mid-stream, after a 200.
                trySend(LlmEvent.Error(streamError))
                return
            }
            // A local server is third-party code: a non-conforming reply must become an
            // LlmEvent.Error, not an exception thrown out of the OkHttp callback (which
            // would take the process down).
            val parsed = runCatching {
                json.get("message")?.takeIf { it.isJsonObject }?.asJsonObject?.let { message ->
                    message.stringOrNull("content")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { trySend(LlmEvent.TextDelta(it)) }
                    message.get("tool_calls")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.forEach { element ->
                            val call = parseToolCall(element) ?: return@forEach
                            hasTools = true
                            trySend(LlmEvent.ToolCallBegin(call.first, call.second))
                            if (call.third.isNotEmpty()) {
                                trySend(LlmEvent.ToolCallArgsDelta(call.first, call.third))
                            }
                            trySend(LlmEvent.ToolCallEnd(call.first, call.second, call.third))
                        }
                }
                val doneValue = json.get("done")
                if (doneValue != null && doneValue.isJsonPrimitive && doneValue.asBoolean && !done) {
                    done = true
                    trySend(LlmEvent.Done(if (hasTools) FinishReason.TOOL_CALLS else FinishReason.STOP))
                }
            }
            if (parsed.isFailure) {
                trySend(LlmEvent.Error("Malformed stream line from the local model server"))
                return
            }
        }
        if (!done) {
            trySend(LlmEvent.Done(if (hasTools) FinishReason.TOOL_CALLS else FinishReason.STOP))
        }
    }

    /** A string member that tolerates a non-primitive (or absent) value. */
    private fun com.google.gson.JsonObject.stringOrNull(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asString }.getOrNull()
    }

    /** `(id, name, argumentsJson)` for one tool call, or null when it is malformed. */
    private fun parseToolCall(element: com.google.gson.JsonElement): Triple<String, String, String>? {
        val obj = runCatching { element.asJsonObject }.getOrNull() ?: return null
        val function = runCatching { obj.getAsJsonObject("function") }.getOrNull() ?: return null
        val name = function.stringOrNull("name") ?: return null
        val arguments = function.get("arguments")
        val argumentsJson = when {
            arguments == null || arguments.isJsonNull -> "{}"
            arguments.isJsonPrimitive -> arguments.asString.ifBlank { "{}" }
            else -> gson.toJson(arguments)
        }
        // Ollama does not assign tool-call ids; the engine needs a stable one to match
        // the result back to the call.
        val id = obj.get("id")?.asString?.takeIf { it.isNotBlank() } ?: "oll_${UUID.randomUUID()}"
        return Triple(id, name, argumentsJson)
    }

    private fun buildRequestBody(req: ChatRequest): JsonObject = JsonObject().apply {
        addProperty("model", req.model.id)
        addProperty("stream", true)
        add("messages", JsonArray().apply {
            req.messages.forEach { message ->
                add(JsonObject().apply {
                    addProperty(
                        "role",
                        when (message.role) {
                            LlmMessage.Role.SYSTEM -> "system"
                            LlmMessage.Role.USER -> "user"
                            LlmMessage.Role.ASSISTANT -> "assistant"
                            LlmMessage.Role.TOOL -> "tool"
                        }
                    )
                    addProperty("content", message.content)
                    if (message.images.isNotEmpty()) {
                        add("images", JsonArray().apply {
                            message.images.forEach {
                                add(
                                    com.google.gson.JsonPrimitive(
                                        Base64.encodeToString(it.bytes, Base64.NO_WRAP)
                                    )
                                )
                            }
                        })
                    }
                    if (message.toolCalls.isNotEmpty()) {
                        add("tool_calls", JsonArray().apply {
                            message.toolCalls.forEach { call ->
                                add(JsonObject().apply {
                                    add("function", JsonObject().apply {
                                        addProperty("name", call.name)
                                        add("arguments", runCatching {
                                            JsonParser.parseString(call.argumentsJson)
                                        }.getOrElse { JsonObject() })
                                    })
                                })
                            }
                        })
                    }
                })
            }
        })
        add("options", JsonObject().apply {
            addProperty("temperature", req.temperature)
            // The context the app budgets for, clamped to what a local model can hold.
            addProperty("num_ctx", req.model.contextLength.coerceIn(1024, 32768))
            req.maxTokens?.let { addProperty("num_predict", it.coerceAtLeast(1)) }
        })
        // Keep the model resident between turns: unloading it on every request would
        // make a local loop unusably slow.
        addProperty("keep_alive", "5m")
        if (req.useTools && req.tools.isNotEmpty()) {
            add("tools", JsonArray().apply {
                req.tools.forEach { tool ->
                    add(JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)
                            add("parameters", tool.parametersSchema)
                        })
                    })
                }
            })
        }
    }
}
