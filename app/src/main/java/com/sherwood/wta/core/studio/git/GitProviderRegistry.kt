package com.sherwood.wta.core.studio.git

import android.content.Context

/** Why a provider cannot be used. Typed so the caller keeps the wording (and the i18n). */
enum class GitProviderUnavailableReason {
    /** The host is recognised but no driver for it ships in this build. */
    NOT_IMPLEMENTED_IN_THIS_BUILD,
    /** A self-hosted instance needs a server URL and none is saved. */
    MISSING_BASE_URL,
    /** The saved server URL is not HTTPS, so a token would travel in clear text. */
    NON_HTTPS_BASE_URL
}

sealed class GitProviderLookup {
    data class Available(val provider: GitProvider) : GitProviderLookup()

    data class Unavailable(
        val id: GitProviderId,
        val reason: GitProviderUnavailableReason
    ) : GitProviderLookup()
}

/**
 * The single place that decides which hosting platform answers.
 *
 * The Studio asks for the active provider once and then only ever sees [GitProvider].
 * Adding a host is a new entry in [drivers]; no caller changes.
 *
 * An unsupported or misconfigured provider is reported as
 * [GitProviderLookup.Unavailable] instead of falling back to GitHub. A silent fallback
 * would send the user's edits and token to the wrong host, which is the one failure this
 * layer exists to prevent.
 */
object GitProviderRegistry {

    /**
     * Factories by provider id. GitHub's factory ignores the base URL because its
     * endpoints come from [com.sherwood.wta.core.studio.EndpointConfig]; a self-hosted
     * driver receives the configured server URL and the credential slot derived from it.
     */
    private val drivers:
        Map<GitProviderId, (Context, String) -> GitProvider> = mapOf(
        GitProviderId.GITHUB to { context, _ -> GitHubProvider(context) },
        GitProviderId.GITEA to { context, baseUrl -> gitea(context, GitProviderId.GITEA, baseUrl) },
        GitProviderId.FORGEJO to { context, baseUrl -> gitea(context, GitProviderId.FORGEJO, baseUrl) }
    )

    private fun gitea(context: Context, id: GitProviderId, baseUrl: String): GitProvider {
        val key = GitCredentialKey.of(id, baseUrl)
            ?: error("An HTTPS base URL is required before a provider is built")
        return GiteaProvider(
            id = id,
            baseUrl = baseUrl,
            tokenProvider = GitCredentialStore(context).tokenProvider(key)
        )
    }

    fun active(context: Context): GitProviderLookup {
        val settings = GitProviderSettings.getInstance(context)
        val id = settings.activeProvider
        return of(context, id, settings.baseUrl(id))
    }

    /**
     * Resolves the driver a *working copy* belongs to, from its recorded provenance.
     *
     * The active setting is not consulted here on purpose: a project imported from
     * `git.example.com` must keep talking to that server even after the user switches the
     * active provider to GitHub. A missing host falls back to the configured server for
     * that provider, which is the only sensible reading of a v1 record.
     */
    fun forProvenance(
        context: Context,
        provider: GitProviderId,
        host: String?
    ): GitProviderLookup {
        if (provider == GitProviderId.GITHUB) {
            return of(context, GitProviderId.GITHUB)
        }
        val configured = GitProviderSettings.getInstance(context).baseUrl(provider)
        if (!host.isNullOrBlank()) {
            return of(context, provider, "https://${host.trim()}")
        }
        return of(context, provider, configured)
    }

    fun of(
        context: Context,
        id: GitProviderId,
        baseUrl: String = GitProviderSettings.getInstance(context).baseUrl(id)
    ): GitProviderLookup {
        if (id != GitProviderId.GITHUB) {
            if (baseUrl.isBlank()) {
                return GitProviderLookup.Unavailable(id, GitProviderUnavailableReason.MISSING_BASE_URL)
            }
            // The slot derivation is also the HTTPS gate: a token is only ever attached to
            // an HTTPS request, so a cleartext server is refused here, before any request.
            if (GitCredentialKey.of(id, baseUrl) == null) {
                return GitProviderLookup.Unavailable(
                    id,
                    GitProviderUnavailableReason.NON_HTTPS_BASE_URL
                )
            }
        }
        val factory = drivers[id]
            ?: return GitProviderLookup.Unavailable(
                id,
                GitProviderUnavailableReason.NOT_IMPLEMENTED_IN_THIS_BUILD
            )
        return GitProviderLookup.Available(factory(context, baseUrl))
    }

    /** Provider ids this build can actually connect to. */
    fun supported(): Set<GitProviderId> = drivers.keys
}
