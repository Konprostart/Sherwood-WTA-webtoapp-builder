package com.sherwood.wta.core.studio.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitProviderSeamTest {

    // --------------------------------------------------------------- repository ref

    @Test
    fun `parses an owner and a repository name`() {
        val ref = GitRepoRef.parse(" Konprostart / Sherwood-WTA-webtoapp-builder ")

        assertThat(ref).isNotNull()
        assertThat(ref!!.owner).isEqualTo("Konprostart")
        assertThat(ref.name).isEqualTo("Sherwood-WTA-webtoapp-builder")
        assertThat(ref.path).isEqualTo("Konprostart/Sherwood-WTA-webtoapp-builder")
    }

    @Test
    fun `rejects a repository reference that is not exactly two segments`() {
        assertThat(GitRepoRef.parse("only-a-name")).isNull()
        assertThat(GitRepoRef.parse("owner/name/extra")).isNull()
        assertThat(GitRepoRef.parse("owner/")).isNull()
        assertThat(GitRepoRef.parse("/name")).isNull()
        assertThat(GitRepoRef.parse("")).isNull()
    }

    @Test
    fun `rejects traversal segments as owner or repository name`() {
        assertThat(GitRepoRef.parse("..")).isNull()
        assertThat(GitRepoRef.parse("owner/..")).isNull()
        assertThat(GitRepoRef.parse("../name")).isNull()
        assertThat(GitRepoRef.of(".", "name")).isNull()
        assertThat(GitRepoRef.of("owner", "..")).isNull()
    }

    @Test
    fun `rejects whitespace and path separators inside a name`() {
        assertThat(GitRepoRef.of("own er", "name")).isNull()
        assertThat(GitRepoRef.of("owner", "na me")).isNull()
        assertThat(GitRepoRef.of("own\ner", "name")).isNull()
    }

    // ------------------------------------------------------------------ token format

    @Test
    fun `github tokens keep their github-only format`() {
        assertThat(GitTokenFormat.isValid(GitProviderId.GITHUB, "ghp_" + "a".repeat(36))).isTrue()
        assertThat(GitTokenFormat.isValid(GitProviderId.GITHUB, "github_pat_" + "a".repeat(30)))
            .isTrue()
        // A bare hexadecimal token is what Gitea issues; GitHub must not accept it.
        assertThat(GitTokenFormat.isValid(GitProviderId.GITHUB, "a".repeat(40))).isFalse()
    }

    @Test
    fun `gitea and forgejo accept a bare token and reject a malformed one`() {
        for (provider in listOf(GitProviderId.GITEA, GitProviderId.FORGEJO)) {
            assertThat(GitTokenFormat.isValid(provider, "a".repeat(40))).isTrue()
            assertThat(GitTokenFormat.isValid(provider, "abc_DEF-123" + "z".repeat(28))).isTrue()
            assertThat(GitTokenFormat.isValid(provider, "short")).isFalse()
            assertThat(GitTokenFormat.isValid(provider, "has space" + "a".repeat(30))).isFalse()
            assertThat(GitTokenFormat.isValid(provider, "a".repeat(300))).isFalse()
            assertThat(GitTokenFormat.isValid(provider, null)).isFalse()
        }
    }

    @Test
    fun `masking never reveals a usable token`() {
        val token = "a".repeat(40)
        val masked = GitTokenFormat.masked(token)

        assertThat(masked).isEqualTo("aaaa\u2026aaaa")
        assertThat(masked).doesNotContain(token)
        assertThat(GitTokenFormat.masked("short")).isEqualTo("****")
        assertThat(GitTokenFormat.masked(null)).isEmpty()
    }

    // --------------------------------------------------------------- credential slot

    @Test
    fun `a credential slot is derived from the provider and host`() {
        val key = GitCredentialKey.of(GitProviderId.GITEA, "https://git.example.com/")

        assertThat(key).isNotNull()
        assertThat(key!!.host).isEqualTo("git.example.com")
        assertThat(key.slot).isEqualTo("gitea:git.example.com")
    }

    @Test
    fun `a slot keeps a non-default port so two servers on one host stay separate`() {
        val key = GitCredentialKey.of(GitProviderId.FORGEJO, "https://git.example.com:8443")

        assertThat(key).isNotNull()
        assertThat(key!!.host).isEqualTo("git.example.com:8443")
    }

    @Test
    fun `a cleartext server never gets a credential slot`() {
        assertThat(GitCredentialKey.of(GitProviderId.GITEA, "http://git.example.com")).isNull()
        assertThat(GitCredentialKey.of(GitProviderId.GITEA, "git.example.com")).isNotNull()
        assertThat(GitCredentialKey.of(GitProviderId.GITEA, "")).isNull()
        assertThat(GitCredentialKey.of(GitProviderId.GITEA, "not a url")).isNull()
    }

    // ---------------------------------------------------------------------- registry

    @Test
    fun `github gitea and forgejo all have a driver in this build`() {
        assertThat(GitProviderRegistry.supported()).containsExactly(
            GitProviderId.GITHUB,
            GitProviderId.GITEA,
            GitProviderId.FORGEJO
        )
    }

    @Test
    fun `a self-hosted provider resolves to its own driver and keeps its id`() {
        val lookup = GitProviderRegistry.of(
            context(),
            GitProviderId.GITEA,
            "https://git.example.com"
        )

        val available = lookup as GitProviderLookup.Available
        assertThat(available.provider.id).isEqualTo(GitProviderId.GITEA)
        assertThat(available.provider.baseUrl).isEqualTo("https://git.example.com")
        // Forgejo is a distinct id even though it shares Gitea's API.
        val forgejo = GitProviderRegistry.of(
            context(),
            GitProviderId.FORGEJO,
            "https://code.example.com"
        ) as GitProviderLookup.Available
        assertThat(forgejo.provider.id).isEqualTo(GitProviderId.FORGEJO)
    }

    @Test
    fun `a self-hosted provider without a server url is reported as missing its url`() {
        val lookup = GitProviderRegistry.of(context(), GitProviderId.FORGEJO, "")

        val unavailable = lookup as GitProviderLookup.Unavailable
        assertThat(unavailable.reason).isEqualTo(GitProviderUnavailableReason.MISSING_BASE_URL)
    }

    @Test
    fun `a cleartext self-hosted server is refused before any request`() {
        val lookup = GitProviderRegistry.of(
            context(),
            GitProviderId.GITEA,
            "http://git.example.com"
        )

        val unavailable = lookup as GitProviderLookup.Unavailable
        assertThat(unavailable.reason).isEqualTo(GitProviderUnavailableReason.NON_HTTPS_BASE_URL)
    }

    @Test
    fun `github resolves to a provider without any configuration`() {
        val lookup = GitProviderRegistry.of(context(), GitProviderId.GITHUB)

        val available = lookup as GitProviderLookup.Available
        assertThat(available.provider.id).isEqualTo(GitProviderId.GITHUB)
        assertThat(available.provider.baseUrl).startsWith("https://")
    }

    // ------------------------------------------------------------------ capabilities

    @Test
    fun `github advertises every capability the studio uses`() {
        val capabilities = GitHubProvider.CAPABILITIES

        assertThat(capabilities.changeRequestTerm)
            .isEqualTo(GitChangeRequestTerm.PULL_REQUEST)
        assertThat(capabilities.artifacts).isTrue()
        assertThat(capabilities.releases).isTrue()
        assertThat(capabilities.binaryReleaseAssets).isTrue()
        assertThat(capabilities.pipelineDispatch).isTrue()
        assertThat(capabilities.createBranches).isTrue()
        assertThat(capabilities.mergeBranches).isTrue()
        assertThat(capabilities.createChangeRequests).isTrue()
        assertThat(capabilities.mergeChangeRequests).isTrue()
    }

    @Test
    fun `gitea advertises every capability its api actually serves`() {
        val capabilities = GiteaProvider.CAPABILITIES

        assertThat(capabilities.checks).isTrue()
        assertThat(capabilities.pipelines).isTrue()
        assertThat(capabilities.runLogs).isTrue()
        assertThat(capabilities.artifacts).isTrue()
        assertThat(capabilities.releases).isTrue()
        // Writes this build implements.
        assertThat(capabilities.reviews).isTrue()
        assertThat(capabilities.pipelineDispatch).isTrue()
        assertThat(capabilities.createRepository).isTrue()
        assertThat(capabilities.createBranches).isTrue()
        assertThat(capabilities.createChangeRequests).isTrue()
        assertThat(capabilities.mergeChangeRequests).isTrue()
        // No endpoint backs these, so they stay off rather than promising a control
        // that could only fail.
        assertThat(capabilities.inlineComments).isFalse()
        assertThat(capabilities.mergeBranches).isFalse()
        assertThat(capabilities.oauthDeviceFlow).isFalse()
        assertThat(capabilities.draftChangeRequests).isFalse()
    }

    @Test
    fun `gitea serves a publish path and only the missing endpoints report unsupported`() =
        runBlocking<Unit> {
            val provider = (GitProviderRegistry.of(
                context(),
                GitProviderId.GITEA,
                "https://git.example.com"
            ) as GitProviderLookup.Available).provider
            val ref = GitRepoRef.parse("owner/repo")!!

            assertThat(provider.commitWriter()).isNotNull()
            assertThat(provider.commitWriter()!!.commitsInOneStep).isTrue()

            // There is no branch-merge endpoint on Gitea.
            val merged = provider.mergeBranches(ref, "main", "feat/x")
            assertThat((merged as GitResult.Failure).kind)
                .isEqualTo(GitFailureKind.UNSUPPORTED)
            // Per-line comments need diff positions this client does not compute.
            val comment = provider.addReviewComment(ref, 1, "a".repeat(40), "a.kt", 4, "x")
            assertThat((comment as GitResult.Failure).kind)
                .isEqualTo(GitFailureKind.UNSUPPORTED)
        }

    // ------------------------------------------------------------- studio state

    @Test
    fun `the provider name shown to the user is the product name`() {
        assertThat(GitStudio.displayName(GitProviderId.GITHUB)).isEqualTo("GitHub")
        assertThat(GitStudio.displayName(GitProviderId.GITEA)).isEqualTo("Gitea")
        assertThat(GitStudio.displayName(GitProviderId.FORGEJO)).isEqualTo("Forgejo")
        assertThat(GitStudio.selectable()).hasSize(3)
    }

    @Test
    fun `a server url is validated before it is saved`() {
        // GitHub has no editable server URL, so nothing can be wrong with it.
        assertThat(GitStudio.baseUrlProblem(GitProviderId.GITHUB, "")).isNull()
        assertThat(GitStudio.baseUrlProblem(GitProviderId.GITEA, "  "))
            .isEqualTo(GitProviderUnavailableReason.MISSING_BASE_URL)
        assertThat(GitStudio.baseUrlProblem(GitProviderId.GITEA, "http://git.example.com"))
            .isEqualTo(GitProviderUnavailableReason.NON_HTTPS_BASE_URL)
        assertThat(GitStudio.baseUrlProblem(GitProviderId.GITEA, "https://git.example.com"))
            .isNull()
        assertThat(GitStudio.baseUrlProblem(GitProviderId.FORGEJO, "git.example.com")).isNull()
    }

    @Test
    fun `the state reports why an unconfigured server cannot be used`() {
        val context = context()
        val settings = GitProviderSettings.getInstance(context)
        val previousProvider = settings.activeProvider
        val previousUrl = settings.baseUrl(GitProviderId.GITEA)
        try {
            settings.setBaseUrl(GitProviderId.GITEA, "")
            settings.activeProvider = GitProviderId.GITEA

            val state = GitStudio.state(context)

            assertThat(state.id).isEqualTo(GitProviderId.GITEA)
            assertThat(state.displayName).isEqualTo("Gitea")
            assertThat(state.isAvailable).isFalse()
            assertThat(state.unavailableReason)
                .isEqualTo(GitProviderUnavailableReason.MISSING_BASE_URL)
            assertThat(state.canPublish).isFalse()
        } finally {
            settings.activeProvider = previousProvider
            settings.setBaseUrl(GitProviderId.GITEA, previousUrl)
        }
    }

    @Test
    fun `a configured gitea server yields a read-only state`() {
        val context = context()
        val settings = GitProviderSettings.getInstance(context)
        val previousProvider = settings.activeProvider
        val previousUrl = settings.baseUrl(GitProviderId.GITEA)
        try {
            settings.setBaseUrl(GitProviderId.GITEA, "https://git.example.com")
            settings.activeProvider = GitProviderId.GITEA

            val state = GitStudio.state(context)

            assertThat(state.isAvailable).isTrue()
            assertThat(state.capabilities).isEqualTo(GiteaProvider.CAPABILITIES)
            // No writer means no publish UI, on this host and in this build.
            assertThat(state.canPublish).isFalse()
            assertThat(state.hasToken).isFalse()
        } finally {
            settings.activeProvider = previousProvider
            settings.setBaseUrl(GitProviderId.GITEA, previousUrl)
        }
    }

    @Test
    fun `a working copy resolves against its own host, not the active one`() {
        val context = context()
        val settings = GitProviderSettings.getInstance(context)
        val previousProvider = settings.activeProvider
        try {
            settings.activeProvider = GitProviderId.GITHUB

            val lookup = GitProviderRegistry.forProvenance(
                context,
                GitProviderId.GITEA,
                "git.example.com"
            )

            val available = lookup as GitProviderLookup.Available
            assertThat(available.provider.id).isEqualTo(GitProviderId.GITEA)
            assertThat(available.provider.baseUrl).isEqualTo("https://git.example.com")
        } finally {
            settings.activeProvider = previousProvider
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
