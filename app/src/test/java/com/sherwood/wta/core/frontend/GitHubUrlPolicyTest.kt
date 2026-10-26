package com.sherwood.wta.core.frontend

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubUrlPolicyTest {

    @Test
    fun `accepts an https url without credentials`() {
        val url = GitHubUrlPolicy.requireHttps("https://api.github.com/repos/octocat/hello")

        assertThat(url.scheme).isEqualTo("https")
        assertThat(url.host).isEqualTo("api.github.com")
    }

    @Test
    fun `rejects plain http`() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubUrlPolicy.requireHttps("http://api.github.com/repos/octocat/hello")
        }
    }

    @Test
    fun `rejects embedded credentials`() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubUrlPolicy.requireHttps("https://user:secret@api.github.com/repos")
        }
    }

    @Test
    fun `rejects malformed and empty urls`() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubUrlPolicy.requireHttps("not a url")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubUrlPolicy.requireHttps("   ")
        }
    }

    @Test
    fun `isHttps mirrors the strict rule`() {
        assertThat(GitHubUrlPolicy.isHttps("https://github.com/octocat/hello")).isTrue()
        assertThat(GitHubUrlPolicy.isHttps("http://github.com/octocat/hello")).isFalse()
        assertThat(GitHubUrlPolicy.isHttps("https://user:pass@github.com/octocat/hello")).isFalse()
    }
}
