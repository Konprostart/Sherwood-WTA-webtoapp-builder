package com.sherwood.wta.core.studio.git

import android.content.Context
import com.sherwood.wta.core.frontend.GitHubTokenStore
import com.sherwood.wta.core.frontend.GitTokenProvider
import com.sherwood.wta.core.frontend.TokenCipher
import com.sherwood.wta.core.frontend.TokenProtection
import com.sherwood.wta.core.logging.AppLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets

/**
 * One credential slot: a hosting platform plus the server it points at.
 *
 * The host is part of the key because a self-hosted Gitea or Forgejo instance is a
 * different server from the next one; one token per provider would silently send the
 * wrong secret to the wrong host.
 */
data class GitCredentialKey(val provider: GitProviderId, val host: String) {

    /** Stable storage identifier; also the AES-GCM associated data suffix. */
    val slot: String get() = "${provider.name.lowercase()}:$host"

    companion object {
        /**
         * Derives the slot from a base URL. Only HTTPS is accepted — the token travels
         * in a header, and a cleartext self-hosted server would expose it on the
         * network. Returns null for a URL that is not HTTPS or not a URL at all.
         */
        fun of(provider: GitProviderId, baseUrl: String): GitCredentialKey? {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https") return null
            val host = url.host.lowercase()
            val authority = if (url.port == 443) host else "$host:${url.port}"
            return GitCredentialKey(provider, authority)
        }
    }
}

/**
 * Stores one personal access token per [GitCredentialKey], encrypted at rest.
 *
 * GitHub keeps its original slot and its original storage key: an installation that is
 * already connected stays connected, with no migration step and no window in which the
 * token is unavailable. Every other provider gets its own encrypted slot in a separate
 * preferences file, with associated data derived from the slot so a ciphertext copied
 * into another slot fails to decrypt instead of authenticating as the wrong server.
 *
 * No method here logs a token value.
 */
class GitCredentialStore(private val context: Context) {

    private val github = GitHubTokenStore(context)

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun token(key: GitCredentialKey): String? {
        if (key.provider == GitProviderId.GITHUB) {
            return github.currentToken()
        }
        val stored = prefs.getString(preferenceKey(key), null) ?: return null
        val plaintext = runCatching {
            TokenCipher.decrypt(context, stored, associatedData(key))
        }.getOrNull()
        if (plaintext == null) {
            AppLogger.w(TAG, "Stored ${key.provider} token could not be decrypted")
            return null
        }
        if (!GitTokenFormat.isValid(key.provider, plaintext)) {
            AppLogger.w(TAG, "Stored ${key.provider} token has an unsupported format")
            return null
        }
        return GitTokenFormat.normalize(plaintext)
    }

    fun saveToken(key: GitCredentialKey, rawToken: String): Result<Unit> {
        val token = GitTokenFormat.normalize(rawToken)
        if (!GitTokenFormat.isValid(key.provider, token)) {
            return Result.failure(
                IllegalArgumentException("Unsupported ${key.provider} token format")
            )
        }
        if (key.provider == GitProviderId.GITHUB) {
            return github.saveToken(token)
        }
        val encrypted = try {
            TokenCipher.encrypt(context, token, associatedData(key))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Token could not be encrypted", e)
            return Result.failure(e)
        }
        if (!prefs.edit().putString(preferenceKey(key), encrypted).commit()) {
            val failure = IllegalStateException("SharedPreferences commit returned false")
            AppLogger.e(TAG, "Token could not be saved", failure)
            return Result.failure(failure)
        }
        return Result.success(Unit)
    }

    fun clearToken(key: GitCredentialKey) {
        if (key.provider == GitProviderId.GITHUB) {
            github.clearToken()
            return
        }
        prefs.edit().remove(preferenceKey(key)).commit()
    }

    fun hasToken(key: GitCredentialKey): Boolean =
        if (key.provider == GitProviderId.GITHUB) {
            github.hasToken()
        } else {
            prefs.contains(preferenceKey(key))
        }

    fun protection(key: GitCredentialKey): TokenProtection =
        if (key.provider == GitProviderId.GITHUB) {
            github.protection()
        } else {
            TokenCipher.protection(prefs.getString(preferenceKey(key), null))
        }

    /** The token source to hand to a provider's HTTP layer. */
    fun tokenProvider(key: GitCredentialKey): GitTokenProvider =
        GitTokenProvider { token(key) }

    private fun preferenceKey(key: GitCredentialKey): String = "token:${key.slot}"

    private fun associatedData(key: GitCredentialKey): ByteArray =
        "$ASSOCIATED_DATA_PREFIX|${key.slot}".toByteArray(StandardCharsets.UTF_8)

    companion object {
        private const val TAG = "GitCredentialStore"
        private const val PREFS = "sherwood_git_credentials"
        private const val ASSOCIATED_DATA_PREFIX = "sherwood-wta/git-token/v1"
    }
}
