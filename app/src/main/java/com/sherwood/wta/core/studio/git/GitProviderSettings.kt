package com.sherwood.wta.core.studio.git

import android.content.Context

/**
 * Local Studio preference: which hosting platform the Studio talks to, and where its
 * server is.
 *
 * No credential is stored here — that is [GitCredentialStore]'s job. The base URL is
 * kept per provider so switching between GitHub and a self-hosted instance never
 * forgets the other server.
 *
 * GitHub's own URLs are not configurable here: they already live in
 * [com.sherwood.wta.core.studio.EndpointConfig], which also drives the module and update
 * mirrors, and duplicating them would let the two drift.
 */
class GitProviderSettings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var activeProvider: GitProviderId
        get() {
            val stored = prefs.getString(KEY_ACTIVE, null) ?: return GitProviderId.GITHUB
            return GitProviderId.entries.firstOrNull { it.name == stored } ?: GitProviderId.GITHUB
        }
        set(value) {
            prefs.edit().putString(KEY_ACTIVE, value.name).apply()
        }

    /** Empty for a provider whose server has not been entered yet. */
    fun baseUrl(provider: GitProviderId): String =
        prefs.getString(baseKey(provider), null)
            ?: DEFAULT_BASE_URL[provider].orEmpty()

    fun setBaseUrl(provider: GitProviderId, value: String) {
        val trimmed = value.trim().trimEnd('/')
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(baseKey(provider)) else putString(baseKey(provider), trimmed)
        }.apply()
    }

    /** True when the provider needs a server URL the user has not supplied yet. */
    fun requiresBaseUrl(provider: GitProviderId): Boolean =
        provider != GitProviderId.GITHUB && baseUrl(provider).isBlank()

    private fun baseKey(provider: GitProviderId): String = "$KEY_BASE_PREFIX${provider.name}"

    companion object {
        internal const val PREFS = "sherwood_git_providers"
        internal const val KEY_ACTIVE = "active_provider"
        internal const val KEY_BASE_PREFIX = "base_url:"

        /** A self-hosted Gitea or Forgejo server must be entered; there is no default. */
        private val DEFAULT_BASE_URL = mapOf(
            GitProviderId.GITHUB to "https://github.com",
            GitProviderId.GITEA to "",
            GitProviderId.FORGEJO to ""
        )

        @Volatile
        private var INSTANCE: GitProviderSettings? = null

        fun getInstance(context: Context): GitProviderSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: GitProviderSettings(context.applicationContext).also { INSTANCE = it }
            }
    }
}
