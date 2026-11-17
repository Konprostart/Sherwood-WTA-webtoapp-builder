package com.sherwood.wta.core.telegram

import android.content.Context
import com.sherwood.wta.core.frontend.TokenCipher
import com.sherwood.wta.core.frontend.TokenProtection
import com.sherwood.wta.core.logging.AppLogger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Everything the Telegram harness remembers between runs, except the bot token.
 *
 * The bot token lives in [TelegramBotTokenStore] encrypted at rest; this class only holds
 * non-secret configuration and the pairing state that decides *which* chats may command
 * the phone.
 */
class TelegramSettings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch. Off means the foreground service is stopped and polling never runs. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /**
     * Chats allowed to send commands.
     *
     * Empty is a safe state: an unpaired bot answers a single "not paired" line and
     * nothing else, so a stranger who finds the bot gains nothing.
     */
    var pairedChats: Set<Long>
        get() = prefs.getStringSet(KEY_PAIRED, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
        set(value) {
            prefs.edit()
                .putStringSet(KEY_PAIRED, value.map { it.toString() }.toSet())
                .apply()
        }

    /**
     * Whether a paired chat may start a build.
     *
     * Starting a build spends CI minutes on the user's account, so it is a separate
     * switch rather than an implied right of pairing. Everything read-only (status,
     * repositories, runs, vault) stays available either way.
     */
    var allowRemoteBuilds: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_BUILDS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ALLOW_BUILDS, value).apply()
        }

    /**
     * Whether finished CI runs are pushed into the paired chats.
     *
     * On by default: a command centre that reports nothing when a build ends is only
     * half useful. The switch exists so a noisy repository can be silenced without
     * turning the bot off.
     */
    var notifyBuilds: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_BUILDS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFY_BUILDS, value).apply()
        }

    /** Highest Telegram update id already handled; persisted so a restart never replays. */
    var lastUpdateId: Long
        get() = prefs.getLong(KEY_LAST_UPDATE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_UPDATE, value).apply()
        }

    /** Cached bot username, so the UI can show `@name` without a network call. */
    var botUsername: String?
        get() = prefs.getString(KEY_BOT_USERNAME, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_BOT_USERNAME) else putString(KEY_BOT_USERNAME, value)
            }.apply()
        }

    val pairingCode: PairingCode?
        get() {
            val code = prefs.getString(KEY_PAIR_CODE, null) ?: return null
            val expires = prefs.getLong(KEY_PAIR_EXPIRES, 0L)
            return PairingCode(code, expires)
        }

    /** Starts a fresh pairing window. Any previously issued code stops working. */
    fun newPairingCode(now: Long = System.currentTimeMillis()): PairingCode {
        val code = PairingCode.generate()
        val expiresAt = now + PAIRING_WINDOW_MS
        prefs.edit()
            .putString(KEY_PAIR_CODE, code)
            .putLong(KEY_PAIR_EXPIRES, expiresAt)
            .putInt(KEY_PAIR_ATTEMPTS, 0)
            .apply()
        return PairingCode(code, expiresAt)
    }

    fun clearPairingCode() {
        prefs.edit()
            .remove(KEY_PAIR_CODE)
            .remove(KEY_PAIR_EXPIRES)
            .remove(KEY_PAIR_ATTEMPTS)
            .apply()
    }

    /**
     * Redeems a code typed into the bot chat.
     *
     * The code is single-use, and a wrong guess is budgeted: after [PAIRING_MAX_ATTEMPTS]
     * mismatches the window is closed. Consuming the code on the *first* wrong guess would
     * let any stranger who finds the bot cancel the user's pairing, so the attempt cap is
     * the compromise between brute-force resistance and that denial of service.
     */
    fun redeemPairingCode(
        typed: String,
        chatId: Long,
        now: Long = System.currentTimeMillis()
    ): PairingOutcome {
        val stored = pairingCode ?: return PairingOutcome.NoActiveCode
        if (now > stored.expiresAtEpochMs) {
            clearPairingCode()
            return PairingOutcome.Expired
        }
        val candidate = typed.trim()
        if (candidate.length != stored.value.length ||
            !MessageDigest.isEqual(
                candidate.toByteArray(StandardCharsets.UTF_8),
                stored.value.toByteArray(StandardCharsets.UTF_8)
            )
        ) {
            val attempts = prefs.getInt(KEY_PAIR_ATTEMPTS, 0) + 1
            if (attempts >= PAIRING_MAX_ATTEMPTS) {
                clearPairingCode()
                return PairingOutcome.Expired
            }
            prefs.edit().putInt(KEY_PAIR_ATTEMPTS, attempts).apply()
            return PairingOutcome.Mismatch
        }
        clearPairingCode()
        pairedChats = pairedChats + chatId
        return PairingOutcome.Paired
    }

    fun unpair(chatId: Long): Set<Long> {
        val remaining = pairedChats - chatId
        pairedChats = remaining
        return remaining
    }

    data class PairingCode(val value: String, val expiresAtEpochMs: Long) {
        companion object {
            private val random = SecureRandom()

            /** Six digits, zero-padded, so every code has the same length to compare. */
            fun generate(): String = (random.nextInt(1_000_000)).toString().padStart(6, '0')
        }
    }

    enum class PairingOutcome { Paired, Mismatch, Expired, NoActiveCode }

    companion object {
        internal const val PREFS = "sherwood_telegram"
        internal const val KEY_ENABLED = "enabled"
        internal const val KEY_PAIRED = "paired_chats"
        internal const val KEY_ALLOW_BUILDS = "allow_remote_builds"
        internal const val KEY_NOTIFY_BUILDS = "notify_builds"
        internal const val KEY_LAST_UPDATE = "last_update_id"
        internal const val KEY_BOT_USERNAME = "bot_username"
        internal const val KEY_PAIR_CODE = "pairing_code"
        internal const val KEY_PAIR_EXPIRES = "pairing_expires_at"
        internal const val KEY_PAIR_ATTEMPTS = "pairing_attempts"

        /** How long a pairing code stays valid after the user taps "Pair". */
        const val PAIRING_WINDOW_MS = 10 * 60 * 1000L

        /** Wrong guesses tolerated before the pairing window is closed. */
        const val PAIRING_MAX_ATTEMPTS = 5

        @Volatile
        private var INSTANCE: TelegramSettings? = null

        fun getInstance(context: Context): TelegramSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelegramSettings(context.applicationContext).also { INSTANCE = it }
            }
    }
}

/**
 * Encrypted storage for the Telegram bot token.
 *
 * A bot token is a full credential: whoever holds it can read the bot's messages and
 * send new ones. It is therefore never written in plain text, never logged, and never
 * put in an Intent extra. It reuses [TokenCipher] so it gets exactly the same at-rest
 * protection as the git tokens, including the explicit "degraded" report when the
 * Android Keystore is unavailable.
 */
class TelegramBotTokenStore(private val context: Context) {

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun hasToken(): Boolean = prefs.contains(KEY_TOKEN)

    fun protection(): TokenProtection =
        TokenCipher.protection(prefs.getString(KEY_TOKEN, null))

    fun currentToken(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        val plaintext = runCatching {
            TokenCipher.decrypt(context, stored, associatedData())
        }.getOrNull()
        if (plaintext == null) {
            AppLogger.w(TAG, "Stored Telegram token could not be decrypted")
            return null
        }
        if (!TelegramTokenFormat.isValid(plaintext)) {
            AppLogger.w(TAG, "Stored Telegram token has an unsupported format")
            return null
        }
        return plaintext.trim()
    }

    fun saveToken(raw: String): Result<Unit> {
        val token = raw.trim()
        if (!TelegramTokenFormat.isValid(token)) {
            return Result.failure(IllegalArgumentException("Unsupported Telegram bot token format"))
        }
        val encrypted = try {
            TokenCipher.encrypt(context, token, associatedData())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Telegram token could not be encrypted", e)
            return Result.failure(e)
        }
        val botId = TelegramTokenFormat.botId(token)
        val previousBotId = prefs.getString(KEY_BOT_ID, null)
        if (!prefs.edit().putString(KEY_TOKEN, encrypted).putString(KEY_BOT_ID, botId).commit()) {
            val failure = IllegalStateException("SharedPreferences commit returned false")
            AppLogger.e(TAG, "Telegram token could not be saved", failure)
            return Result.failure(failure)
        }
        // Update ids are per bot: keeping the old offset would make every update of a
        // different bot look already-handled and the fresh bot would seem dead.
        if (previousBotId != botId) {
            TelegramSettings.getInstance(context).lastUpdateId = 0L
        }
        return Result.success(Unit)
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_BOT_ID).commit()
        // A later token starts a new update stream.
        TelegramSettings.getInstance(context).lastUpdateId = 0L
    }

    private fun associatedData(): ByteArray =
        "$ASSOCIATED_DATA".toByteArray(StandardCharsets.UTF_8)

    companion object {
        private const val TAG = "TelegramBotTokenStore"
        internal const val PREFS = "sherwood_telegram_credentials"
        internal const val KEY_TOKEN = "bot_token"
        internal const val KEY_BOT_ID = "bot_id"
        private const val ASSOCIATED_DATA = "sherwood-wta/telegram-bot/v1"
    }
}

/** Format gate for a Telegram bot token: `<bot id>:<secret>`. */
object TelegramTokenFormat {

    /**
     * A bot token is the numeric bot id, a colon, and a URL-safe secret of roughly 35
     * characters. Length varies between tokens, so the pattern bounds it instead of
     * pinning one value. Whitespace is rejected rather than trimmed here — the caller
     * trims before validating, and an inner space always means a broken paste.
     */
    private val PATTERN = Regex("^[0-9]{5,12}:[A-Za-z0-9_-]{30,50}$")

    fun isValid(token: String): Boolean = PATTERN.matches(token.trim())

    /**
     * The bot id half of a token, which is public. Used only for display, so the UI can
     * confirm *which* bot was connected without ever showing the secret.
     */
    fun botId(token: String): String? = token.trim()
        .substringBefore(':', missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}
