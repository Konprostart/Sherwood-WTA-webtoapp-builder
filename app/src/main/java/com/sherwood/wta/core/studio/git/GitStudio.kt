package com.sherwood.wta.core.studio.git

import android.content.Context

/**
 * Everything a Studio screen needs to know about the active connection, resolved once.
 *
 * The screens read this instead of testing the provider id: [capabilities] decides which
 * controls are shown, and a section that cannot work is replaced by one line saying why.
 * [unavailableReason] is typed rather than a message so the screen keeps the wording and
 * the translation in the i18n layer.
 */
data class GitStudioState(
    val id: GitProviderId,
    val displayName: String,
    val baseUrl: String,
    val capabilities: GitCapabilities?,
    val hasToken: Boolean,
    val unavailableReason: GitProviderUnavailableReason?,
    /** True when the driver exposes a publish path (a [GitCommitWriter]). */
    val canPublish: Boolean
) {
    val isAvailable: Boolean get() = capabilities != null && unavailableReason == null
}

/**
 * Resolves the active connection without making a network call.
 *
 * Nothing here reads or decrypts the token: [hasToken] consults the credential store's
 * presence check only, so opening a Studio tab never touches the Keystore material.
 */
object GitStudio {

    fun state(context: Context): GitStudioState {
        val settings = GitProviderSettings.getInstance(context)
        val id = settings.activeProvider
        val credentials = GitCredentialStore(context)
        val key = GitCredentialKey.of(id, settings.baseUrl(id))
        val hasToken = key?.let { credentials.hasToken(it) } == true
        return when (val lookup = GitProviderRegistry.active(context)) {
            is GitProviderLookup.Available -> {
                val provider = lookup.provider
                GitStudioState(
                    id = provider.id,
                    displayName = displayName(provider.id),
                    baseUrl = provider.baseUrl,
                    capabilities = provider.capabilities,
                    hasToken = hasToken,
                    unavailableReason = null,
                    canPublish = provider.commitWriter() != null
                )
            }
            is GitProviderLookup.Unavailable -> GitStudioState(
                id = lookup.id,
                displayName = displayName(lookup.id),
                baseUrl = settings.baseUrl(lookup.id),
                capabilities = null,
                hasToken = hasToken,
                unavailableReason = lookup.reason,
                canPublish = false
            )
        }
    }

    /**
     * The product name of a host. Brand names are not translated: "Gitea" is "Gitea" in
     * every language, and inventing a localisation for it would make the setting harder
     * to match against the server's own documentation.
     */
    fun displayName(id: GitProviderId): String = when (id) {
        GitProviderId.GITHUB -> "GitHub"
        GitProviderId.GITEA -> "Gitea"
        GitProviderId.FORGEJO -> "Forgejo"
    }

    /** Providers a user may pick, in a stable order. */
    fun selectable(): List<GitProviderId> = GitProviderId.entries

    /** The base URL to show in the settings field for [id] (empty when unset). */
    fun editableBaseUrl(context: Context, id: GitProviderId): String =
        GitProviderSettings.getInstance(context).baseUrl(id)

    /**
     * Validates a server URL typed by the user without saving or requesting anything.
     * Returns the reason it is unusable, or null when it is acceptable.
     */
    fun baseUrlProblem(id: GitProviderId, raw: String): GitProviderUnavailableReason? {
        if (id == GitProviderId.GITHUB) return null
        if (raw.isBlank()) return GitProviderUnavailableReason.MISSING_BASE_URL
        return if (GitCredentialKey.of(id, raw) == null) {
            GitProviderUnavailableReason.NON_HTTPS_BASE_URL
        } else {
            null
        }
    }
}
