package com.sherwood.wta.core.studio.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
    fun `github is the only provider this build can connect to`() {
        assertThat(GitProviderRegistry.supported()).containsExactly(GitProviderId.GITHUB)
    }

    @Test
    fun `an unimplemented provider is reported instead of falling back to github`() {
        val context = context()
        val lookup = GitProviderRegistry.of(
            context,
            GitProviderId.GITEA,
            "https://git.example.com"
        )

        assertThat(lookup).isInstanceOf(GitProviderLookup.Unavailable::class.java)
        val unavailable = lookup as GitProviderLookup.Unavailable
        assertThat(unavailable.id).isEqualTo(GitProviderId.GITEA)
        assertThat(unavailable.reason)
            .isEqualTo(GitProviderUnavailableReason.NOT_IMPLEMENTED_IN_THIS_BUILD)
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
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
