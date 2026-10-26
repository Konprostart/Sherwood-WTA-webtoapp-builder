package com.sherwood.wta.core.frontend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubTokenFormatTest {

    private val classic = "ghp_" + "a".repeat(36)
    private val fineGrained = "github_pat_" + "B1".repeat(30)

    @Test
    fun `accepts classic and fine grained tokens`() {
        assertThat(GitHubTokenFormat.isValid(classic)).isTrue()
        assertThat(GitHubTokenFormat.isValid(fineGrained)).isTrue()
        assertThat(GitHubTokenFormat.isValid("  $classic  ")).isTrue()
    }

    @Test
    fun `rejects unsupported or malformed tokens`() {
        assertThat(GitHubTokenFormat.isValid(null)).isFalse()
        assertThat(GitHubTokenFormat.isValid("")).isFalse()
        assertThat(GitHubTokenFormat.isValid("ghp_short")).isFalse()
        assertThat(GitHubTokenFormat.isValid("password123456789012345678")).isFalse()
        assertThat(GitHubTokenFormat.isValid("ghp_" + "a".repeat(10) + "\n" + "a".repeat(26))).isFalse()
        assertThat(GitHubTokenFormat.isValid("Bearer $classic")).isFalse()
    }

    @Test
    fun `normalize trims surrounding whitespace`() {
        assertThat(GitHubTokenFormat.normalize("  $classic \n")).isEqualTo(classic)
    }

    @Test
    fun `masked never reveals the full token`() {
        val masked = GitHubTokenFormat.masked(classic)

        assertThat(masked).isNotEqualTo(classic)
        assertThat(masked).doesNotContain(classic)
        assertThat(masked).startsWith("ghp_")
        assertThat(masked).endsWith("aaaa")
    }
}
