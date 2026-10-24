package com.sherwood.wta.ui.webview

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sherwood.wta.core.adblock.AdBlocker
import com.sherwood.wta.core.webview.WebViewManager

@Composable
internal fun rememberWebViewManager(
    context: Context,
    adBlocker: AdBlocker
): WebViewManager {
    return remember(context, adBlocker) {
        WebViewManager(context, adBlocker)
    }
}
