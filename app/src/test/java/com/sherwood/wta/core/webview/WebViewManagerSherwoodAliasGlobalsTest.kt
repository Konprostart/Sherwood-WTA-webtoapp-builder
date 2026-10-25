package com.sherwood.wta.core.webview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.adblock.AdBlocker
import com.sherwood.wta.data.model.WebViewConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Dual-expose contract (additive only): every legacy `__webtoapp_*__` page
 * global keeps working AND its `__sherwood_wta_*__` mirror must be present,
 * so pages can adopt the new namespace without breaking old consumers.
 * A file:// page reaches the compatibility batch (local-runtime pages skip
 * it) and is not in conservative script mode, so all five polyfill families
 * inject from a single onPageStarted pass.
 */
@RunWith(RobolectricTestRunner::class)
class WebViewManagerSherwoodAliasGlobalsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingWebView(context: Context) : WebView(context) {
        val scripts = mutableListOf<String>()

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String?>?) {
            scripts.add(script)
            super.evaluateJavascript(script, resultCallback)
        }
    }

    private val noopCallbacks = object : WebViewCallbacks {
        override fun onPageStarted(url: String?) {}
        override fun onPageFinished(url: String?) {}
        override fun onProgressChanged(progress: Int) {}
        override fun onTitleChanged(title: String?) {}
        override fun onIconReceived(icon: Bitmap?) {}
        override fun onError(errorCode: Int, description: String) {}
        override fun onSslError(error: String) {}
        override fun onExternalLink(url: String) {}
        override fun onShowCustomView(view: android.view.View?, callback: WebChromeClient.CustomViewCallback?) {}
        override fun onHideCustomView() {}
        override fun onGeolocationPermission(origin: String?, callback: GeolocationPermissions.Callback?) {}
        override fun onPermissionRequest(request: PermissionRequest?) {}
        override fun onShowFileChooser(
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean = false

        override fun onDownloadStart(
            url: String,
            userAgent: String,
            contentDisposition: String,
            mimeType: String,
            contentLength: Long
        ) {}
    }

    private fun scriptBundleOnPageStart(): String {
        val webView = RecordingWebView(context)
        val config = WebViewConfig(
            enableNotificationPolyfill = true,
            popupBlockerEnabled = true
        )
        val manager = WebViewManager(context, AdBlocker())
        manager.configureWebView(webView, config, noopCallbacks, pluginsEnabled = false)
        shadowOf(webView).webViewClient.onPageStarted(webView, "file:///android_asset/app/index.html", null)
        return webView.scripts.joinToString("\n")
    }

    @Test
    fun `orientation polyfill exposes both namespaces`() {
        val bundle = scriptBundleOnPageStart()

        assertThat(bundle).contains("__webtoapp_orientation_polyfill__ = true")
        assertThat(bundle).contains("__sherwood_wta_orientation_polyfill__ = true")
    }

    @Test
    fun `zoom polyfill exposes both namespaces`() {
        val bundle = scriptBundleOnPageStart()

        assertThat(bundle).contains("__webtoapp_zoom_polyfill__ = true")
        assertThat(bundle).contains("__sherwood_wta_zoom_polyfill__ = true")
    }

    @Test
    fun `clipboard polyfill exposes both namespaces`() {
        val bundle = scriptBundleOnPageStart()

        assertThat(bundle).contains("__webtoapp_clipboard_polyfill__ = true")
        assertThat(bundle).contains("__sherwood_wta_clipboard_polyfill__ = true")
    }

    @Test
    fun `notification polyfill exposes both namespaces`() {
        val bundle = scriptBundleOnPageStart()

        assertThat(bundle).contains("__webtoapp_notification_polyfill__ = true")
        assertThat(bundle).contains("__sherwood_wta_notification_polyfill__ = true")
    }

    @Test
    fun `popup blocker exposes both namespaces and aliased controls`() {
        val bundle = scriptBundleOnPageStart()

        assertThat(bundle).contains("__webtoapp_popup_blocker_enabled__ = true")
        assertThat(bundle).contains("__sherwood_wta_popup_blocker_enabled__ = true")
        assertThat(bundle).contains(
            "__sherwood_wta_toggle_popup_blocker__ = window.__webtoapp_toggle_popup_blocker__"
        )
        assertThat(bundle).contains(
            "__sherwood_wta_popup_blocker_stats__ = window.__webtoapp_popup_blocker_stats__"
        )
    }

    @Test
    fun `legacy toggle keeps the sherwood enabled flag in sync`() {
        val bundle = scriptBundleOnPageStart()

        assertThat(bundle).contains("window.__webtoapp_popup_blocker_enabled__ = enabled")
        assertThat(bundle).contains("window.__sherwood_wta_popup_blocker_enabled__ = enabled")
    }
}
