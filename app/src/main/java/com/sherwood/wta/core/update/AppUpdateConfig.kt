package com.sherwood.wta.core.update

/**
 * Auto-update settings of a generated app. Disabled by default; the app never contacts a
 * manifest URL unless [enabled] is true.
 */
data class AppUpdateConfig(
    val enabled: Boolean = false,
    val manifestUrl: String = "",
    val publicKeyBase64: String = "",
    val checkIntervalHours: Int = 24
) {
    /** True when the config has everything needed to run a check. */
    val isUsable: Boolean
        get() = enabled && manifestUrl.isNotBlank() && publicKeyBase64.isNotBlank()
}
