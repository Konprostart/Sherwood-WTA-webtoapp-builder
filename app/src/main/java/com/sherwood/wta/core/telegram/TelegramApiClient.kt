package com.sherwood.wta.core.telegram

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sherwood.wta.core.network.NetworkModule
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------- wire models

data class TelegramBotInfo(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("username") val username: String = "",
    @SerializedName("first_name") val firstName: String? = null
)

data class TelegramUser(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("username") val username: String? = null,
    @SerializedName("first_name") val firstName: String? = null
)

data class TelegramChat(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("type") val type: String = "",
    @SerializedName("title") val title: String? = null,
    @SerializedName("username") val username: String? = null
) {
    val isPrivate: Boolean get() = type == "private"

    /** A short, non-identifying label for logs and the pairing list. */
    val displayName: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: "chat $id"
}

data class TelegramMessage(
    @SerializedName("message_id") val messageId: Long = 0,
    @SerializedName("chat") val chat: TelegramChat? = null,
    @SerializedName("from") val from: TelegramUser? = null,
    @SerializedName("date") val date: Long = 0,
    @SerializedName("text") val text: String? = null
)

data class TelegramUpdate(
    @SerializedName("update_id") val updateId: Long = 0,
    @SerializedName("message") val message: TelegramMessage? = null,
    @SerializedName("callback_query") val callbackQuery: TelegramCallbackQuery? = null
)

/**
 * A tap on an inline-keyboard button.
 *
 * The tap is delivered as its own update type, not as a message. [message] is the bot's
 * message the keyboard is attached to, which is where the chat id (and therefore the
 * pairing check) comes from.
 */
data class TelegramCallbackQuery(
    @SerializedName("id") val id: String = "",
    @SerializedName("from") val from: TelegramUser? = null,
    @SerializedName("message") val message: TelegramMessage? = null,
    @SerializedName("data") val data: String? = null
)

// ---------------------------------------------------------------- failures

/**
 * Why a call failed. Typed, because the polling loop reacts differently to each one:
 * a bad token must stop the loop entirely, a conflict means another instance is polling,
 * and a rate limit has an authoritative wait time.
 */
sealed class TelegramFailure {
    /** The token is wrong or was revoked. Polling must stop; retrying cannot help. */
    data class InvalidToken(val description: String) : TelegramFailure()

    /** Another getUpdates call is in flight (a second app instance, or a webhook). */
    data class Conflict(val description: String) : TelegramFailure()

    /** Telegram asked us to slow down; [retryAfterSeconds] is its own number. */
    data class RateLimited(val retryAfterSeconds: Int) : TelegramFailure()

    /** Any other API-level rejection, e.g. an unreachable chat or malformed request. */
    data class Api(val code: Int, val description: String) : TelegramFailure()

    /** Transport failure: DNS, TLS, timeout, no network. */
    data class Network(val message: String) : TelegramFailure()

    /** The caller's coroutine was cancelled; not an error to report. */
    data object Cancelled : TelegramFailure()
}

class TelegramException(val failure: TelegramFailure) : Exception(
    when (failure) {
        is TelegramFailure.InvalidToken -> "Telegram rejected the bot token"
        is TelegramFailure.Conflict -> "Another Telegram poller is already running"
        is TelegramFailure.RateLimited -> "Telegram rate limit"
        is TelegramFailure.Api -> "Telegram API error ${failure.code}"
        is TelegramFailure.Network -> failure.message
        TelegramFailure.Cancelled -> "Cancelled"
    }
)

/**
 * Minimal Telegram Bot API client for the harness.
 *
 * Only the four calls the harness needs are implemented (identity, long polling, send
 * message, send document). No third-party Telegram library is used: the API is two
 * endpoints behind a bearer-in-path URL, and a library would add a dependency to the
 * host APK for no benefit.
 *
 * Nothing here logs the token, and the token never appears in a request *body* — it is
 * part of the URL path, which is what Telegram requires.
 */
class TelegramApiClient(
    private val token: String,
    baseClient: OkHttpClient = NetworkModule.defaultClient
) {

    private val gson = Gson()

    /**
     * Long polling holds the request open for `timeout` seconds, so the read timeout has
     * to sit above that or every poll would appear to fail. The shared default client's
     * 30 s read timeout is too tight for a 25 s poll plus latency.
     */
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(POLL_TIMEOUT_SECONDS + 20L, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.telegram.org/bot$token/"

    suspend fun getMe(): Result<TelegramBotInfo> = request(
        method = "getMe",
        form = emptyMap(),
        parse = { element ->
            gson.fromJson(element, TelegramBotInfo::class.java)
        }
    )

    /**
     * Fetches pending updates.
     *
     * [offset] must be one past the highest handled update id; Telegram then confirms
     * everything below it and will not redeliver after a restart.
     */
    suspend fun getUpdates(
        offset: Long,
        timeoutSeconds: Int = POLL_TIMEOUT_SECONDS
    ): Result<List<TelegramUpdate>> = request(
        method = "getUpdates",
        form = buildMap {
            put("offset", offset.toString())
            put("timeout", timeoutSeconds.toString())
            put("limit", "20")
            // Messages carry slash commands; callback queries carry inline-button taps.
            // Nothing else is parsed, so asking for less keeps the parse surface small.
            put("allowed_updates", "[\"message\",\"callback_query\"]")
        },
        parse = { element ->
            element.asJsonArray.mapNotNull { item ->
                runCatching { gson.fromJson(item, TelegramUpdate::class.java) }.getOrNull()
            }
        }
    )

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        disablePreview: Boolean = true,
        keyboard: List<List<TelegramButton>> = emptyList()
    ): Result<Unit> = request(
        method = "sendMessage",
        form = buildMap {
            put("chat_id", chatId.toString())
            put("text", text.take(MAX_MESSAGE_CHARS))
            if (disablePreview) put("disable_web_page_preview", "true")
            replyMarkup(keyboard)?.let { put("reply_markup", it) }
            // The harness never uses Markdown: every reply embeds user and repository
            // strings, and Telegram would reject the message on any stray '_' or '*'.
        },
        parse = { }
    )

    /**
     * Acknowledges a button tap.
     *
     * Telegram keeps the client-side spinner until this call arrives, so it is sent even
     * when the tap is refused. [text], when given, is shown as a one-line toast.
     */
    suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String? = null
    ): Result<Unit> = request(
        method = "answerCallbackQuery",
        form = buildMap {
            put("callback_query_id", callbackQueryId)
            if (!text.isNullOrBlank()) put("text", text.take(180))
        },
        parse = { }
    )

    /**
     * Serialises an inline keyboard. Buttons that would exceed Telegram's callback-data
     * limit are dropped instead of making the whole send fail.
     */
    private fun replyMarkup(keyboard: List<List<TelegramButton>>): String? {
        val rows = keyboard
            .map { row -> row.filter { it.isValid } }
            .filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        val payload = mapOf(
            "inline_keyboard" to rows.map { row ->
                row.map { button ->
                    if (!button.url.isNullOrBlank()) {
                        mapOf("text" to button.label, "url" to button.url)
                    } else {
                        mapOf("text" to button.label, "callback_data" to button.data)
                    }
                }
            }
        )
        return gson.toJson(payload)
    }

    /** Sends a local file (e.g. an APK from the vault) as a chat document. */
    suspend fun sendDocument(
        chatId: Long,
        file: java.io.File,
        caption: String? = null,
        keyboard: List<List<TelegramButton>> = emptyList()
    ): Result<Unit> {
        if (!file.isFile || file.length() == 0L) {
            return Result.failure(TelegramException(TelegramFailure.Network("File is missing")))
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart(
                "document",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            .apply {
                if (!caption.isNullOrBlank()) addFormDataPart("caption", caption.take(900))
                replyMarkup(keyboard)?.let { addFormDataPart("reply_markup", it) }
            }
            .build()
        return execute(
            Request.Builder().url(baseUrl + "sendDocument").post(body).build(),
            parse = { }
        )
    }

    // ------------------------------------------------------------ internals

    private suspend fun <T> request(
        method: String,
        form: Map<String, String>,
        parse: (com.google.gson.JsonElement) -> T
    ): Result<T> {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        return execute(Request.Builder().url(baseUrl + method).post(body).build(), parse)
    }

    private suspend fun <T> execute(
        request: Request,
        parse: (com.google.gson.JsonElement) -> T
    ): Result<T> {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            return Result.failure(TelegramException(TelegramFailure.Network(e.message ?: "Network error")))
        } catch (e: TelegramException) {
            return Result.failure(e)
        }
        val bodyText = response.body
        val envelope = runCatching {
            gson.fromJson(bodyText, Envelope::class.java)
        }.getOrNull()
        if (envelope == null) {
            return Result.failure(
                TelegramException(
                    TelegramFailure.Api(response.code, "Unreadable response from Telegram")
                )
            )
        }
        val payload = envelope.result
        if (envelope.ok && payload != null) {
            return runCatching { parse(payload) }
                .fold(
                    onSuccess = { Result.success(it) },
                    onFailure = {
                        Result.failure(
                            TelegramException(
                                TelegramFailure.Api(response.code, "Could not read the response")
                            )
                        )
                    }
                )
        }
        if (envelope.ok) {
            // Methods that answer `true` rather than an object (sendMessage and friends).
            return runCatching { parse(com.google.gson.JsonPrimitive(true)) }
                .fold(
                    onSuccess = { Result.success(it) },
                    onFailure = {
                        Result.failure(
                            TelegramException(
                                TelegramFailure.Api(response.code, "Could not read the response")
                            )
                        )
                    }
                )
        }
        return Result.failure(TelegramException(classify(response.code, envelope)))
    }

    private fun classify(code: Int, envelope: Envelope): TelegramFailure {
        val description = envelope.description.orEmpty().ifBlank { "Telegram rejected the request" }
        return when (code) {
            401, 404 -> TelegramFailure.InvalidToken(description)
            409 -> TelegramFailure.Conflict(description)
            429 -> TelegramFailure.RateLimited(envelope.parameters?.retryAfter ?: 5)
            else -> TelegramFailure.Api(code, description)
        }
    }

    private data class Envelope(
        @SerializedName("ok") val ok: Boolean = false,
        @SerializedName("result") val result: com.google.gson.JsonElement? = null,
        @SerializedName("error_code") val errorCode: Int? = null,
        @SerializedName("description") val description: String? = null,
        @SerializedName("parameters") val parameters: Parameters? = null
    ) {
        data class Parameters(
            @SerializedName("retry_after") val retryAfter: Int? = null
        )
    }

    private data class HttpResponseData(val code: Int, val body: String)

    /** Suspending OkHttp call whose cancellation cancels the socket, not just the coroutine. */
    private suspend fun Call.await(): HttpResponseData = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) {
                    cont.cancel()
                } else {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                    if (cont.isActive) cont.resume(HttpResponseData(it.code, body))
                }
            }
        })
    }

    companion object {
        /** Telegram's own long-poll window; the read timeout is derived from it. */
        const val POLL_TIMEOUT_SECONDS = 25

        /** Telegram refuses messages above 4096 characters; keep a margin. */
        const val MAX_MESSAGE_CHARS = 3_800
    }
}
