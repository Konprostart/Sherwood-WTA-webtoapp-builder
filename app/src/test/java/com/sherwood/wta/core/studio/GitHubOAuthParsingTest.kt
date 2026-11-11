package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubOAuthParsingTest {

    @Test
    fun parsesDeviceChallenge() {
        val parsed = GitHubOAuthClient.parseChallenge(
            """{"device_code":"abc","user_code":"ABCD-1234",
               "verification_uri":"https://github.com/login/device",
               "expires_in":900,"interval":5}"""
        )
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.deviceCode).isEqualTo("abc")
        assertThat(parsed.userCode).isEqualTo("ABCD-1234")
        assertThat(parsed.intervalSeconds).isEqualTo(5)
    }

    @Test
    fun clampsIntervalAndExpiry() {
        val parsed = GitHubOAuthClient.parseChallenge(
            """{"device_code":"abc","user_code":"X",
               "verification_uri":"https://github.com/login/device",
               "expires_in":1,"interval":1000}"""
        )
        assertThat(parsed!!.expiresInSeconds).isEqualTo(60)
        assertThat(parsed.intervalSeconds).isEqualTo(60)
    }

    @Test
    fun rejectsMissingFields() {
        assertThat(GitHubOAuthClient.parseChallenge("""{"user_code":"X"}""")).isNull()
        assertThat(GitHubOAuthClient.parseChallenge("not json")).isNull()
        assertThat(GitHubOAuthClient.parseChallenge(null)).isNull()
    }

    @Test
    fun tokenSuccess() {
        val result = GitHubOAuthClient.parsePoll("""{"access_token":"gho_abc","token_type":"bearer"}""")
        assertThat(result).isInstanceOf(DeviceFlowPollResult.Success::class.java)
        assertThat((result as DeviceFlowPollResult.Success).token).isEqualTo("gho_abc")
    }

    @Test
    fun pendingAndSlowDown() {
        assertThat(GitHubOAuthClient.parsePoll("""{"error":"authorization_pending"}"""))
            .isEqualTo(DeviceFlowPollResult.Pending)
        assertThat(GitHubOAuthClient.parsePoll("""{"error":"slow_down"}"""))
            .isEqualTo(DeviceFlowPollResult.SlowDown)
    }

    @Test
    fun expiredAndDeniedAreFailures() {
        val expired = GitHubOAuthClient.parsePoll("""{"error":"expired_token"}""")
        assertThat(expired).isInstanceOf(DeviceFlowPollResult.Failed::class.java)
        val denied = GitHubOAuthClient.parsePoll("""{"error":"access_denied"}""")
        assertThat((denied as DeviceFlowPollResult.Failed).denied).isTrue()
    }

    @Test
    fun unknownErrorIsFailure() {
        assertThat(GitHubOAuthClient.parsePoll("""{"error":"whatever"}"""))
            .isInstanceOf(DeviceFlowPollResult.Failed::class.java)
    }

    @Test
    fun clientIdValidation() {
        assertThat(GitHubOAuthClient.isValidClientId("Iv1.0123456789abcdef")).isTrue()
        assertThat(GitHubOAuthClient.isValidClientId("short")).isFalse()
        assertThat(GitHubOAuthClient.isValidClientId("has space in it")).isFalse()
    }
}
