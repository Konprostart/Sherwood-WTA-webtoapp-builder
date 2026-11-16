package com.sherwood.wta.ui.shell

import androidx.compose.runtime.compositionLocalOf
import com.sherwood.wta.core.webview.StatusBarPageColorTracker

val LocalPageScrollTracker = compositionLocalOf<StatusBarPageColorTracker?> { null }
