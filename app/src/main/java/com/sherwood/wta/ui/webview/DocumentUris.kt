package com.sherwood.wta.ui.webview

import android.net.Uri

/**
 * Single source of truth for the internal document-URI identity used by
 * launch intents (recents reuse, preview session keys, pinned shortcuts).
 *
 * The data URI is an opaque document key: no code parses the scheme, and
 * [WebViewActivity.buildLaunchIntent] passes any scheme through untouched
 * (legacy `webtoapp://` URIs from older pinned shortcuts keep working).
 * Writers must use this object so every NEW identity carries the
 * `sherwood-wta` scheme; the legacy scheme is never emitted again.
 */
object DocumentUris {
    const val SCHEME = "sherwood-wta"
    const val LEGACY_SCHEME = "webtoapp"

    fun forApp(appId: Long): Uri = Uri.parse("$SCHEME://webapp/$appId")

    fun forUrl(url: String): Uri = Uri.parse("$SCHEME://url/${url.hashCode()}")

    fun forPreviewJson(webAppJson: String): Uri =
        Uri.parse("$SCHEME://preview/${webAppJson.hashCode()}")

    fun forTest(testUrl: String): Uri = Uri.parse("$SCHEME://test/${testUrl.hashCode()}")

    fun forAppPreview(appId: Long): Uri = Uri.parse("$SCHEME://preview/app/$appId")

    fun forUrlPreview(url: String): Uri =
        Uri.parse("$SCHEME://preview/url").buildUpon().appendQueryParameter("u", url).build()
}
