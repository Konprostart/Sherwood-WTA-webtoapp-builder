package com.sherwood.wta.core.frontend

import android.content.Context
import android.util.Base64
import com.sherwood.wta.core.crypto.EnhancedCrypto
import com.sherwood.wta.core.logging.AppLogger
import java.nio.charset.StandardCharsets

/** Supplies the GitHub PAT for a request. Returns null when no token is configured. */
fun interface GitHubTokenProvider {
    fun currentToken(): String?
}

/**
 * Validation and display helpers for Personal Access Tokens.
 *
 * Only GitHub token shapes are accepted here. Anything that is not a classic
 * (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`) or fine-grained (`github_pat_`) token is
 * rejected before it reaches the Keystore, so a pasted password or URL cannot be
 * stored by accident.
 */
object GitHubTokenFormat {

    private val CLASSIC = Regex("^gh[pousr]_[A-Za-z0-9]{20,255}$")
    private val FINE_GRAINED = Regex("^github_pat_[A-Za-z0-9_]{20,500}$")

    fun normalize(raw: String): String = raw.trim()

    fun isValid(raw: String?): Boolean {
        val token = raw?.let(::normalize) ?: return false
        if (token.length !in 20..512) return false
        if (token.any { it.isWhitespace() }) return false
        return CLASSIC.matches(token) || FINE_GRAINED.matches(token)
    }

    /** Short, non-reversible label for UI display. Never used for logging. */
    fun masked(raw: String?): String {
        val token = raw?.let(::normalize).orEmpty()
        return when {
            token.isEmpty() -> ""
            token.length <= 8 -> "****"
            else -> token.take(4) + "\u2026" + token.takeLast(4)
        }
    }
}

/**
 * Stores a GitHub PAT encrypted with the app's Android Keystore master key.
 *
 * The ciphertext lives in a dedicated SharedPreferences file (never Room), and the
 * plaintext is only materialised for the duration of a request. Deliberately no
 * token value is ever passed to [AppLogger].
 */
class GitHubTokenStore(private val context: Context) : GitHubTokenProvider {

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun currentToken(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        val plaintext = runCatching { decrypt(stored) }.getOrNull()
        if (plaintext == null) {
            AppLogger.w(TAG, "Stored GitHub token could not be decrypted")
            return null
        }
        if (!GitHubTokenFormat.isValid(plaintext)) {
            AppLogger.w(TAG, "Stored GitHub token has an unsupported format")
            return null
        }
        return GitHubTokenFormat.normalize(plaintext)
    }

    fun saveToken(rawToken: String): Result<Unit> {
        val token = GitHubTokenFormat.normalize(rawToken)
        if (!GitHubTokenFormat.isValid(token)) {
            return Result.failure(IllegalArgumentException("Unsupported GitHub token format"))
        }
        val encrypted = runCatching { encrypt(token) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Token encryption is unavailable"))
        if (!prefs.edit().putString(KEY_TOKEN, encrypted).commit()) {
            return Result.failure(IllegalStateException("Token could not be saved"))
        }
        return Result.success(Unit)
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).commit()
    }

    fun hasToken(): Boolean = prefs.contains(KEY_TOKEN)

    private fun encrypt(plaintext: String): String {
        val key = EnhancedCrypto.getOrCreateMasterKey(context)
            ?: error("Keystore master key is unavailable")
        val ciphertext = EnhancedCrypto.encrypt(
            plaintext.toByteArray(StandardCharsets.UTF_8),
            key,
            ASSOCIATED_DATA
        )
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val key = EnhancedCrypto.getOrCreateMasterKey(context)
            ?: error("Keystore master key is unavailable")
        val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
        val plaintext = EnhancedCrypto.decrypt(ciphertext, key, ASSOCIATED_DATA)
        return String(plaintext, StandardCharsets.UTF_8)
    }

    companion object {
        private const val TAG = "GitHubTokenStore"

        internal const val PREFS = "sherwood_git"
        internal const val KEY_TOKEN = "github_pat"

        private val ASSOCIATED_DATA =
            "sherwood-wta/github-pat/v1".toByteArray(StandardCharsets.UTF_8)
    }
}
