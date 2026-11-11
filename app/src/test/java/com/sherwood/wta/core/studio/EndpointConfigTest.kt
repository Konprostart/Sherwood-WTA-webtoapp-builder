package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EndpointConfigTest {

    @Test
    fun defaultsAreValidAndHttps() {
        val config = EndpointConfig.defaults()
        assertThat(config.validationErrors()).isEmpty()
        assertThat(config.githubApiBaseUrl).isEqualTo("https://api.github.com")
        assertThat(config.projectOwner).isEqualTo("Konprostart")
    }

    @Test
    fun plainHttpIsRejected() {
        val config = EndpointConfig.defaults().copy(githubApiBaseUrl = "http://api.github.com")
        assertThat(config.validationErrors()).containsKey("githubApiBaseUrl")
        assertThat(config.isValid()).isFalse()
    }

    @Test
    fun embeddedCredentialsAreRejected() {
        assertThat(EndpointConfig.validateHttpsUrl("https://user:pass@api.github.com")).isNotNull()
    }

    @Test
    fun queryAndFragmentAreRejected() {
        assertThat(EndpointConfig.validateHttpsUrl("https://api.github.com?x=1")).isNotNull()
        assertThat(EndpointConfig.validateHttpsUrl("https://api.github.com#x")).isNotNull()
    }

    @Test
    fun overridesReplaceOnlyNonBlankFields() {
        val config = EndpointConfig.defaults().withOverrides(
            mapOf(
                "githubApiBaseUrl" to "https://github.example.com/api/v3/",
                "projectOwner" to "my-org",
                "githubWebBaseUrl" to "   "
            )
        )
        assertThat(config.githubApiBaseUrl).isEqualTo("https://github.example.com/api/v3")
        assertThat(config.projectOwner).isEqualTo("my-org")
        assertThat(config.githubWebBaseUrl).isEqualTo(EndpointConfig.DEFAULT_GITHUB_WEB)
    }

    @Test
    fun differencesOnlyContainChangedFields() {
        val config = EndpointConfig.defaults().copy(projectRepo = "my-fork")
        val differences = config.differencesFromDefaults()
        assertThat(differences).containsExactly("projectRepo", "my-fork")
    }

    @Test
    fun invalidOwnerAndBranchAreReported() {
        val config = EndpointConfig.defaults().copy(projectOwner = "bad owner", projectBranch = "a..b")
        val errors = config.validationErrors()
        assertThat(errors).containsKey("projectOwner")
        assertThat(errors).containsKey("projectBranch")
    }

    @Test
    fun releaseUrlsUseTheConfiguredRepository() {
        val config = EndpointConfig.defaults().copy(projectOwner = "acme", projectRepo = "fork")
        assertThat(config.releasesApiUrl())
            .isEqualTo("https://api.github.com/repos/acme/fork/releases?per_page=100")
        assertThat(config.repositoryWebUrl("acme", "fork"))
            .isEqualTo("https://github.com/acme/fork")
    }
}
