package com.sherwood.wta.core.apkbuilder

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.data.model.ApkExportConfig
import com.sherwood.wta.data.model.NativeBridgeCapabilities
import com.sherwood.wta.data.model.NotificationExportConfig
import com.sherwood.wta.data.model.NotificationType
import com.sherwood.wta.data.model.WebApp
import com.sherwood.wta.data.model.WebViewConfig
import org.junit.Test

/**
 * A page that calls `Notification` only gets a notification when both the polyfill and
 * the NativeBridge notification capability are on. The editor switch used to enable the
 * polyfill alone, so `showWebNotification` answered "permission denied" and nothing was
 * ever posted — the export must derive both from one decision.
 */
class WebNotificationCapabilityExportTest {

    private fun exported(app: WebApp) = app.toApkConfig("com.example.chat")

    @Test
    fun `web notification export turns the bridge capability on`() {
        val config = exported(
            WebApp(
                name = "Chat",
                url = "https://chat.example.com",
                webViewConfig = WebViewConfig(
                    nativeBridgeCapabilities = NativeBridgeCapabilities(notification = false)
                ),
                apkExportConfig = ApkExportConfig(
                    notificationEnabled = true,
                    notificationConfig = NotificationExportConfig(type = NotificationType.WEB_API)
                )
            )
        )

        assertThat(config.enableNotificationPolyfill).isTrue()
        assertThat(config.webViewBehavior.nativeBridgeNotification).isTrue()
    }

    @Test
    fun `editor polyfill turns the bridge capability on`() {
        val config = exported(
            WebApp(
                name = "Chat",
                url = "https://chat.example.com",
                webViewConfig = WebViewConfig(
                    enableNotificationPolyfill = true,
                    nativeBridgeCapabilities = NativeBridgeCapabilities(notification = false)
                )
            )
        )

        assertThat(config.enableNotificationPolyfill).isTrue()
        assertThat(config.webViewBehavior.nativeBridgeNotification).isTrue()
    }

    @Test
    fun `polling export leaves the bridge capability off`() {
        val config = exported(
            WebApp(
                name = "Chat",
                url = "https://chat.example.com",
                webViewConfig = WebViewConfig(
                    nativeBridgeCapabilities = NativeBridgeCapabilities(notification = false)
                ),
                apkExportConfig = ApkExportConfig(
                    notificationEnabled = true,
                    notificationConfig = NotificationExportConfig(type = NotificationType.POLLING)
                )
            )
        )

        assertThat(config.enableNotificationPolyfill).isFalse()
        assertThat(config.webViewBehavior.nativeBridgeNotification).isFalse()
    }

    @Test
    fun `disabled notification switch leaves the bridge capability off`() {
        val config = exported(
            WebApp(
                name = "Chat",
                url = "https://chat.example.com",
                webViewConfig = WebViewConfig(
                    nativeBridgeCapabilities = NativeBridgeCapabilities(notification = false)
                ),
                apkExportConfig = ApkExportConfig(
                    notificationEnabled = false,
                    notificationConfig = NotificationExportConfig(type = NotificationType.WEB_API)
                )
            )
        )

        assertThat(config.enableNotificationPolyfill).isFalse()
        assertThat(config.webViewBehavior.nativeBridgeNotification).isFalse()
    }
}
