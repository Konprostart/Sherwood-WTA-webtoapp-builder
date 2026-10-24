package com.sherwood.wta.ui.webview

import com.sherwood.wta.core.activation.ActivationManager
import com.sherwood.wta.core.adblock.AdBlocker
import com.sherwood.wta.core.announcement.AnnouncementManager
import com.sherwood.wta.core.webview.LocalHttpServer
import com.sherwood.wta.data.repository.WebAppRepository

data class WebViewScreenDependencies(
    val repository: WebAppRepository,
    val activation: ActivationManager,
    val announcement: AnnouncementManager,
    val adBlocker: AdBlocker,
    val localHttpServer: LocalHttpServer,
)
