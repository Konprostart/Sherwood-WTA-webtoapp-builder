package com.sherwood.wta.core.startup

import com.sherwood.wta.core.activation.ActivationManager
import com.sherwood.wta.core.adblock.AdBlocker
import com.sherwood.wta.core.announcement.AnnouncementManager
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.shell.ShellModeManager
import com.sherwood.wta.core.shell.ShellRuntimeServices

class ShellRuntimeStartup(
    private val shellModeManager: ShellModeManager,
    private val activationManager: ActivationManager,
    private val announcementManager: AnnouncementManager,
    private val adBlocker: AdBlocker,
) {

    fun initialize() {
        ShellRuntimeServices.initialize(
            shellModeManager = shellModeManager,
            activationManager = activationManager,
            announcementManager = announcementManager,
            adBlocker = adBlocker,
        )

        try {
            val isShell = shellModeManager.isShellMode()
            AppLogger.i("WebToAppApplication", "Shell mode pre-check: $isShell")
        } catch (e: Exception) {
            AppLogger.e("WebToAppApplication", "Shell mode pre-check failed", e)
        } catch (e: Error) {
            AppLogger.e("WebToAppApplication", "Shell mode pre-check critical error", Error(e))
        }
    }

    fun shutdown() {
        ShellRuntimeServices.reset()
    }
}
