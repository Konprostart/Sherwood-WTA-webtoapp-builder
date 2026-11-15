package com.sherwood.wta.core.agent.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sherwood.wta.core.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One model installed on an Ollama server. */
data class OllamaModel(
    val name: String,
    val sizeBytes: Long = 0L,
    val parameterSize: String? = null,
    val quantization: String? = null,
    val family: String? = null
) {
    /**
     * Vision models are the only multimodal local models the app can feed images to.
     * Matched by the families Ollama ships rather than by a whitelist of exact tags.
     */
    val isMultimodal: Boolean
        get() {
            val id = name.lowercase()
            return VISION_MARKERS.any { id.contains(it) }
        }

    val displaySize: String
        get() = when {
            sizeBytes >= 1_000_000_000L -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000L -> "%.0f MB".format(sizeBytes / 1_000_000.0)
            else -> ""
        }

    companion object {
        private val VISION_MARKERS = listOf(
            "llava", "bakllava", "moondream", "llama3.2-vision", "minicpm-v",
            "qwen2-vl", "qwen2.5-vl", "gemma3", "granite3.2-vision", "mistral-small3.1"
        )
    }
}

/** What a successful probe learned about the server. */
data class OllamaServer(
    val version: String?,
    val models: List<OllamaModel>
)

/** Typed reason a local probe could not be used, so the UI can say something useful. */
sealed class OllamaProbeFailure : Exception() {
    data class InvalidUrl(val detail: String) : OllamaProbeFailure() {
        override val message: String get() = detail
    }

    data class Unreachable(val detail: String) : OllamaProbeFailure() {
        override val message: String get() = detail
    }

    data class Rejected(val code: Int, val detail: String) : OllamaProbeFailure() {
        override val message: String get() = detail
    }

    data class Unreadable(val detail: String) : OllamaProbeFailure() {
        override val message: String get() = detail
    }
}

/**
 * A deliberately small driver for a local Ollama server.
 *
 * Unlike the generic model-list path, this never falls back to a bundled catalogue: a
 * local probe that fails must say so, because showing cloud models for an unreachable
 * local server is exactly the confusion the offline mode is meant to remove.
 *
 * Only `GET /api/version` and `GET /api/tags` are used, so the module works against any
 * Ollama version and against a llama.cpp/Ollama-compatible server. No request carries a
 * credential beyond an optional bearer token, and URLs with embedded userinfo are
 * rejected before any network call.
 */
class OllamaLocalClient(
    baseClient: OkHttpClient = NetworkModule.defaultClient
) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // A LAN server is reached directly; following a redirect to a different scheme
        // or host would send the request somewhere the user did not point the app at.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun probe(rawUrl: String): Result<OllamaServer> = withContext(Dispatchers.IO) {
        val endpoint = OllamaEndpoint.parse(rawUrl)
            ?: return@withContext Result.failure(
                OllamaProbeFailure.InvalidUrl(rawUrl.trim())
            )

        val version = runCatching { getJson("${endpoint.baseUrl}/api/version", endpoint) }
            .getOrElse { return@withContext Result.failure(it) }
        val tags = runCatching { getJson("${endpoint.baseUrl}/api/tags", endpoint) }
            .getOrElse { return@withContext Result.failure(it) }

        val models = OllamaResponseParser.parseModels(tags)
        Result.success(
            OllamaServer(
                version = version.get("version")?.asString?.takeIf { it.isNotBlank() },
                models = models
            )
        )
    }

    private fun getJson(url: String, endpoint: OllamaEndpoint): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw OllamaProbeFailure.Unreachable(e.message ?: "Network error")
        }
        response.use { res ->
            val body = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
            if (!res.isSuccessful) {
                throw OllamaProbeFailure.Rejected(
                    res.code,
                    body.take(300).ifBlank { res.message }
                )
            }
            return runCatching { JsonParser.parseString(body).asJsonObject }
                .getOrElse { throw OllamaProbeFailure.Unreadable("Response was not JSON") }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:11434"
    }
}

/**
 * Normalises a server address. Kept separate from the HTTP client so it is testable
 * without a server, and so the UI can reject a bad URL before storing it.
 */
object OllamaEndpoint {

    data class Parsed(val baseUrl: String, val host: String, val port: Int, val secure: Boolean)

    fun parse(raw: String): Parsed? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Reject a traversal path before OkHttp normalises it away.
        if (trimmed.contains("/../") || trimmed.endsWith("/..") ||
            trimmed.contains("/./") || trimmed.endsWith("/.")
        ) {
            return null
        }
        // Credentials in a URL are not allowed: the app never needs them for a LAN
        // Ollama, and they would end up in logs and error messages.
        if (trimmed.contains('@')) return null
        if (trimmed.contains('?') || trimmed.contains('#')) return null

        val url = runCatching { trimmed.toHttpUrlOrNull() }.getOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        // A path is allowed (reverse proxy under /ollama), but not a traversal segment.
        if (url.pathSegments.any { it == "." || it == ".." }) return null

        val normalised = url.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
        return Parsed(
            baseUrl = normalised,
            host = url.host,
            port = url.port,
            secure = url.isHttps
        )
    }

    fun isLocalHost(raw: String): Boolean {
        val parsed = parse(raw) ?: return false
        return parsed.host == "127.0.0.1" || parsed.host == "localhost" || parsed.host == "::1"
    }
}

/** Response parsing isolated for unit tests. */
object OllamaResponseParser {

    fun parseModels(json: JsonObject): List<OllamaModel> {
        val array = json.getAsJsonArray("models") ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val obj = element.asJsonObject
                val name = obj.get("name")?.asString
                    ?: obj.get("model")?.asString
                    ?: return@runCatching null
                val details = obj.getAsJsonObject("details")
                OllamaModel(
                    name = name,
                    sizeBytes = obj.get("size")?.asLong ?: 0L,
                    parameterSize = details?.get("parameter_size")?.asString,
                    quantization = details?.get("quantization_level")?.asString,
                    family = details?.get("family")?.asString
                )
            }.getOrNull()
        }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
    }

    fun parseVersion(json: JsonObject): String? =
        json.get("version")?.asString?.takeIf { it.isNotBlank() }
}
