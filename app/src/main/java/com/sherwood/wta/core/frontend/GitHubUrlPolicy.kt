package com.sherwood.wta.core.frontend

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Single gate for every outbound GitHub request made by the repo-bridge layer.
 *
 * Tokens are only ever attached to HTTPS requests; a plain `http://` route (or a URL
 * that carries credentials inline) is rejected before a request is built. Keeping this
 * as a pure object makes the rule unit-testable without Android or a live network.
 */
object GitHubUrlPolicy {

    fun requireHttps(rawUrl: String): HttpUrl {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "URL must not be empty" }
        val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("Malformed URL")
        require(url.scheme == "https") { "Non-HTTPS requests are not allowed" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "URLs with embedded credentials are not allowed"
        }
        return url
    }

    fun isHttps(rawUrl: String): Boolean = runCatching { requireHttps(rawUrl) }.isSuccess
}
