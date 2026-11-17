package com.sherwood.wta.core.sidekick.input

import android.content.Context
import com.sherwood.wta.core.frontend.TokenCipher
import com.sherwood.wta.core.frontend.TokenProtection
import com.sherwood.wta.core.logging.AppLogger
import java.nio.charset.StandardCharsets

/**
 * Storage for the Whisper-compatible server the Sidekick dictates to.
 *
 * The URL, protocol and model are plain preferences — they are not secrets. The API key
 * is AES-GCM encrypted through the same [TokenCipher] the git credentials use, with its
 * own associated data so a ciphertext copied out of this slot cannot decrypt as a git
 * token (or the other way around).
 *
 * Nothing here logs a key. [token] caches the decrypted value because the Keystore
 * unwrap is slow enough to be visible on the main thread; callers still run the first
 * read on IO.
 */
class SidekickSpeechEndpointStore internal constructor(context: Context) {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenLoaded = false

    var protocol: SidekickSpeechProtocol
        get() = SidekickSpeechProtocol.fromId(prefs.getString(KEY_PROTOCOL, null))
        set(value) {
            prefs.edit().putString(KEY_PROTOCOL, value.id).apply()
        }

    /** The base URL as the user typed it; validated on read, not trusted as stored. */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()
        }

    /** Model name for OpenAI-compatible servers; ignored by whisper.cpp. */
    var model: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.trim()).apply()
        }

    /** Whether a key is stored, without decrypting it (safe for UI state). */
    fun hasToken(): Boolean = prefs.getString(KEY_TOKEN, null) != null

    /** Decrypted API key, or `null` when none is stored or it cannot be read. */
    fun token(): String? {
        if (tokenLoaded) return cachedToken
        val stored = prefs.getString(KEY_TOKEN, null)
        if (stored == null) {
            tokenLoaded = true
            cachedToken = null
            return null
        }
        val plaintext = runCatching {
            TokenCipher.decrypt(appContext, stored, associatedData())
        }.getOrNull()
        if (plaintext.isNullOrBlank()) {
            AppLogger.w(TAG, "Stored speech endpoint key could not be decrypted")
            tokenLoaded = true
            cachedToken = null
            return null
        }
        tokenLoaded = true
        cachedToken = plaintext
        return plaintext
    }

    /** How the stored key is protected, for the settings hint. `NONE` when unset. */
    fun tokenProtection(): TokenProtection =
        TokenCipher.protection(prefs.getString(KEY_TOKEN, null))

    /**
     * Stores an API key, or clears it when [rawToken] is blank.
     *
     * Refuses a key that would be sent over cleartext: the URL policy allows `http://`
     * only for LAN servers *without* a key, so saving one here would silently make the
     * stored configuration invalid.
     */
    fun saveToken(rawToken: String): Result<Unit> {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            clearToken()
            return Result.success(Unit)
        }
        if (token.length > MAX_TOKEN_LENGTH) {
            return Result.failure(IllegalArgumentException("Speech endpoint key is too long"))
        }
        val validation = SidekickSpeechEndpointPolicy.validate(baseUrl, hasToken = true)
        if (validation is SidekickSpeechEndpointPolicy.Validation.Invalid) {
            return Result.failure(
                IllegalArgumentException("Speech endpoint URL does not allow a key")
            )
        }
        val encrypted = try {
            TokenCipher.encrypt(appContext, token, associatedData())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Speech endpoint key could not be encrypted", e)
            return Result.failure(e)
        }
        if (!prefs.edit().putString(KEY_TOKEN, encrypted).commit()) {
            return Result.failure(IllegalStateException("SharedPreferences commit returned false"))
        }
        tokenLoaded = true
        cachedToken = token
        return Result.success(Unit)
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).commit()
        tokenLoaded = true
        cachedToken = null
    }

    /**
     * Saves the server identity, validating the URL against the stored key.
     *
     * The URL is validated before it becomes active: a cleartext public host or a URL
     * with embedded credentials must never be stored as "configured" and then fail on
     * the first dictation.
     */
    fun saveServer(
        protocol: SidekickSpeechProtocol,
        baseUrl: String,
        model: String
    ): SaveResult {
        val validation = SidekickSpeechEndpointPolicy.validate(baseUrl, hasToken())
        if (validation is SidekickSpeechEndpointPolicy.Validation.Invalid) {
            return SaveResult.Invalid(validation.reason)
        }
        val normalized = (validation as SidekickSpeechEndpointPolicy.Validation.Ok).value.baseUrl
        val saved = prefs.edit()
            .putString(KEY_PROTOCOL, protocol.id)
            .putString(KEY_BASE_URL, normalized)
            .putString(KEY_MODEL, model.trim())
            .commit()
        if (!saved) {
            AppLogger.e(TAG, "Speech endpoint settings could not be saved")
            return SaveResult.StorageFailed
        }
        return SaveResult.Saved
    }

    /** Outcome of [saveServer]. */
    sealed interface SaveResult {
        data object Saved : SaveResult
        data class Invalid(val reason: SidekickEndpointError) : SaveResult
        data object StorageFailed : SaveResult
    }

    /**
     * The configured endpoint, or `null` when it is unset or no longer valid.
     *
     * Validity is re-evaluated on every read against the *stored* key, so a key saved
     * later cannot silently turn a cleartext URL into an accepted one.
     */
    fun endpoint(): SidekickSpeechEndpoint? {
        if (baseUrl.isBlank()) return null
        val hasKey = hasToken()
        val validation = SidekickSpeechEndpointPolicy.validate(baseUrl, hasKey)
        if (validation !is SidekickSpeechEndpointPolicy.Validation.Ok) return null
        val storedModel = model.trim()
        val effectiveModel = when (protocol) {
            // OpenAI-shaped servers need a model id; `whisper-1` is what every hosted
            // Whisper API accepts, and self-hosted OpenAI servers usually accept it too.
            SidekickSpeechProtocol.OPENAI_COMPATIBLE ->
                storedModel.ifBlank { DEFAULT_OPENAI_MODEL }
            // whisper.cpp serves exactly one GGML model and rejects an unknown `model`
            // field, so it is never sent.
            SidekickSpeechProtocol.WHISPER_CPP -> storedModel
        }
        return SidekickSpeechEndpoint(
            protocol = protocol,
            baseUrl = validation.value.baseUrl,
            model = effectiveModel,
            token = if (hasKey) token() else null
        )
    }

    /**
     * Whether a server has been configured and its URL is still acceptable.
     *
     * Deliberately does not decrypt the key: this is called while composing UI, and the
     * Keystore unwrap is slow enough to be visible on the main thread. A stored key that
     * cannot be decrypted surfaces as an auth failure the first time it is used.
     */
    fun isUsable(): Boolean {
        if (baseUrl.isBlank()) return false
        return SidekickSpeechEndpointPolicy.validate(baseUrl, hasToken()) is
            SidekickSpeechEndpointPolicy.Validation.Ok
    }

    /**
     * Why [endpoint] is `null`, for the settings hint. `null` when the endpoint is fine
     * or when nothing has been configured yet.
     */
    fun configurationError(): SidekickEndpointError? {
        if (baseUrl.isBlank()) return null
        val validation = SidekickSpeechEndpointPolicy.validate(baseUrl, hasToken())
        return (validation as? SidekickSpeechEndpointPolicy.Validation.Invalid)?.reason
    }

    private fun associatedData(): ByteArray = AAD.toByteArray(StandardCharsets.UTF_8)

    companion object {
        private const val TAG = "SidekickSpeechEndpoint"
        private const val PREFS = "sherwood_sidekick_speech"

        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_TOKEN = "token"

        const val DEFAULT_OPENAI_MODEL = "whisper-1"

        private const val MAX_TOKEN_LENGTH = 8192

        /** Associated data for the AES-GCM key wrap; changing it orphans stored keys. */
        private const val AAD = "sherwood-wta/sidekick-speech/v1"

        @Volatile
        private var INSTANCE: SidekickSpeechEndpointStore? = null

        fun getInstance(context: Context): SidekickSpeechEndpointStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SidekickSpeechEndpointStore(context.applicationContext)
                    .also { INSTANCE = it }
            }
    }
}
