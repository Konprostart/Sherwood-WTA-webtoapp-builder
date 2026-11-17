package com.sherwood.wta.core.sidekick.input

import com.google.gson.JsonParser
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Uploads one WAV take to a Whisper-compatible server.
 *
 * Two request shapes, chosen by [SidekickSpeechProtocol]:
 *
 * ```
 * OpenAI-compatible  POST {base}/audio/transcriptions   multipart: file, model, language
 * whisper.cpp        POST {base}/inference              multipart: file, language
 * ```
 *
 * The file is streamed from disk (`asRequestBody`), never read into a `ByteArray` — a
 * 120 s take is 3.8 MB and there is no reason for it to touch the heap.
 *
 * Security:
 * - Redirects are not followed. A 30x on a transcription URL is a misconfiguration, and
 *   following it would replay the `Authorization` header (and the audio) at a host the
 *   user never configured.
 * - The HTTP client used here has no logging interceptor; no method in this class logs
 *   the key or the audio.
 * - The URL must already have passed [SidekickSpeechEndpointPolicy].
 */
class SidekickWhisperTranscriber(
    private val client: OkHttpClient = defaultClient()
) {

    /**
     * Transcribes [audio].
     *
     * @return the transcript, or a [SidekickSpeechException] carrying the reason.
     */
    suspend fun transcribe(
        endpoint: SidekickSpeechEndpoint,
        audio: File,
        language: SidekickVoiceLanguage
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!audio.isFile || audio.length() <= WavFormat.HEADER_BYTES) {
            return@withContext failure(SidekickSpeechFailure.AUDIO)
        }
        val response = try {
            execute(request(endpoint, language, audio))
        } catch (e: IOException) {
            AppLogger.w(TAG, "Transcription request failed: ${e.javaClass.simpleName}")
            return@withContext failure(SidekickSpeechFailure.NETWORK)
        }
        // Already on IO: reading a bounded body here keeps it off the main thread.
        response.use { result ->
            val payload = readBounded(result)
            if (!result.isSuccessful) {
                AppLogger.w(TAG, "Transcription rejected with HTTP ${result.code}")
                return@withContext failure(statusFailure(result.code))
            }
            when (val parsed = parsePayload(payload)) {
                is Payload.Broken -> {
                    AppLogger.w(TAG, "Transcription response was not the expected shape")
                    return@withContext failure(SidekickSpeechFailure.ENDPOINT)
                }

                is Payload.Text -> {
                    val text = parsed.value?.trim()
                    if (text.isNullOrEmpty()) {
                        return@withContext failure(SidekickSpeechFailure.NO_MATCH)
                    }
                    return@withContext Result.success(text)
                }
            }
        }
    }

    /**
     * Checks that the server answers the configured endpoint.
     *
     * Sends one second of silence to the real transcription URL rather than probing a
     * `/models` path: a server can be reachable, authenticated and still have the wrong
     * protocol, and only the real request proves the protocol, path and key together.
     * Any 2xx counts — silence legitimately transcribes to an empty string.
     */
    suspend fun probe(endpoint: SidekickSpeechEndpoint): Result<Unit> = withContext(Dispatchers.IO) {
        val probeFile = try {
            File.createTempFile("sidekick-probe-", ".wav").apply {
                writeBytes(WavFormat.headerBytes(PROBE_PCM_BYTES.toLong()) + ByteArray(PROBE_PCM_BYTES.toInt()))
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Speech probe file could not be created", t)
            return@withContext failureUnit(SidekickSpeechFailure.AUDIO)
        }
        try {
            val response = try {
                execute(request(endpoint, SidekickVoiceLanguage.AUTO, probeFile))
            } catch (e: IOException) {
                return@withContext failureUnit(SidekickSpeechFailure.NETWORK)
            }
            response.use { result ->
                // Drain the body so the socket is released instead of held until GC.
                readBounded(result)
                if (!result.isSuccessful) {
                    return@withContext failureUnit(statusFailure(result.code))
                }
                return@withContext Result.success(Unit)
            }
        } finally {
            runCatching { probeFile.delete() }
        }
    }

    /**
     * Runs the call and suspends, so cancelling the caller's coroutine actually cancels
     * the HTTP call instead of leaving a 3.8 MB upload running to its timeout.
     */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isCancelled) {
                        response.close()
                    } else {
                        continuation.resume(response)
                    }
                }
            })
        }

    private fun request(
        endpoint: SidekickSpeechEndpoint,
        language: SidekickVoiceLanguage,
        audio: File
    ): Request {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", FILE_NAME, audio.asRequestBody(WAV_MEDIA_TYPE))
            .addFormDataPart("response_format", "json")
            .apply {
                if (endpoint.protocol == SidekickSpeechProtocol.OPENAI_COMPATIBLE) {
                    addFormDataPart("model", endpoint.model)
                }
                SidekickSpeechEndpointPolicy.whisperLanguage(language)?.let { code ->
                    addFormDataPart("language", code)
                }
            }
            .build()

        val builder = Request.Builder()
            .url(endpoint.transcriptionUrl)
            .post(multipart)
            .header("Accept", "application/json")
        endpoint.token?.takeIf { it.isNotBlank() }?.let { key ->
            builder.header("Authorization", "Bearer $key")
        }
        return builder.build()
    }

    /** Reads at most [MAX_RESPONSE_BYTES], so a misbehaving server cannot exhaust memory. */
    private fun readBounded(response: Response): String {
        val body = response.body ?: return ""
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        try {
            while (total < MAX_RESPONSE_BYTES) {
                val read = source.read(buffer, MAX_RESPONSE_BYTES - total)
                if (read == -1L) break
                total += read
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Transcription body could not be read: ${e.javaClass.simpleName}")
        }
        return buffer.readUtf8()
    }

    private sealed interface Payload {
        /** A `{"text": ...}` object, or a plain-text body. [value] may be blank. */
        data class Text(val value: String?) : Payload

        /** Looked like JSON but was not the documented shape — wrong endpoint or protocol. */
        data object Broken : Payload
    }

    private fun parsePayload(payload: String): Payload {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return Payload.Text(null)
        if (!trimmed.startsWith("{")) return Payload.Text(trimmed)
        val json = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
            ?: return Payload.Broken
        val element = json.get("text") ?: return Payload.Broken
        if (!element.isJsonPrimitive) return Payload.Broken
        return Payload.Text(element.asString)
    }

    private fun statusFailure(code: Int): SidekickSpeechFailure = when {
        code == 401 || code == 403 -> SidekickSpeechFailure.AUTH
        code == 429 -> SidekickSpeechFailure.THROTTLED
        // 3xx: redirects are not followed on purpose, so this is the URL being wrong.
        code in 300..399 -> SidekickSpeechFailure.ENDPOINT
        // 404: path mismatch; 400/413/415/422: the server refused this request shape.
        code in 400..499 -> SidekickSpeechFailure.ENDPOINT
        else -> SidekickSpeechFailure.NETWORK
    }

    private fun failure(kind: SidekickSpeechFailure): Result<String> =
        Result.failure(SidekickSpeechException(kind))

    private fun failureUnit(kind: SidekickSpeechFailure): Result<Unit> =
        Result.failure(SidekickSpeechException(kind))

    companion object {
        private const val TAG = "SidekickWhisper"

        private const val FILE_NAME = "audio.wav"
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

        private const val MAX_RESPONSE_BYTES = 512L * 1024L

        /** One second of 16 kHz mono silence, used by [probe]. */
        private const val PROBE_PCM_BYTES = 16_000 * 2

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            // A redirect would replay the bearer key at a host the user never entered.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** A transcription failure carrying the reason the UI maps to copy. */
class SidekickSpeechException(val failure: SidekickSpeechFailure) : Exception(failure.name)

/** Maps any throwable from the transcriber to a UI reason. */
fun Throwable.toSpeechFailure(): SidekickSpeechFailure =
    (this as? SidekickSpeechException)?.failure ?: SidekickSpeechFailure.UNKNOWN
