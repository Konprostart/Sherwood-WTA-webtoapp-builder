package com.sherwood.wta.core.studio.git

import com.sherwood.wta.core.frontend.GitHubTokenFormat

/**
 * Validation rules for a personal access token, per hosting platform.
 *
 * A token is rejected before it reaches the Keystore, so a pasted password, URL or
 * client secret cannot be stored by accident. The rules differ per host because the
 * token shapes do, and applying GitHub's `ghp_` prefix rule to a Gitea server would
 * reject every real Gitea token (they are bare hexadecimal strings).
 */
object GitTokenFormat {

    /**
     * Gitea and Forgejo tokens carry no prefix: 40 hex characters by default, but the
     * length varies by version and by what the account created. Accept any run of
     * printable ASCII without whitespace in the range those hosts can emit — being
     * stricter would reject a valid token and there is no prefix to key off.
     */
    private val BARE_TOKEN = Regex("^[A-Za-z0-9_-]{20,255}$")

    fun normalize(raw: String): String = raw.trim()

    fun isValid(provider: GitProviderId, raw: String?): Boolean {
        val token = raw?.let(::normalize) ?: return false
        if (token.any { it.isWhitespace() }) return false
        return when (provider) {
            GitProviderId.GITHUB -> GitHubTokenFormat.isValid(token)
            GitProviderId.GITEA, GitProviderId.FORGEJO -> BARE_TOKEN.matches(token)
        }
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
