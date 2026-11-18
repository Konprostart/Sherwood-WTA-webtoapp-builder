package com.sherwood.wta.core.sidekick.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Sidekick fetches URLs a model can influence, on a phone that shares a network with the
 * user's own machines. These pin "public HTTPS only" so the boundary cannot be loosened by
 * accident.
 */
class SidekickWebPolicyTest {

    private fun allowed(url: String): SidekickWebPolicy.Verdict.Allowed? =
        SidekickWebPolicy.check(url) as? SidekickWebPolicy.Verdict.Allowed

    private fun reason(url: String): SidekickWebPolicy.Reason? =
        (SidekickWebPolicy.check(url) as? SidekickWebPolicy.Verdict.Denied)?.reason

    @Test
    fun `a public https url is allowed and normalised`() {
        val verdict = allowed("https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder#readme")
        assertThat(verdict).isNotNull()
        assertThat(verdict!!.url).isEqualTo("https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder")
        assertThat(verdict.host).isEqualTo("github.com")
    }

    @Test
    fun `plain http is refused`() {
        assertThat(reason("http://example.com/")).isEqualTo(SidekickWebPolicy.Reason.INSECURE)
    }

    @Test
    fun `other schemes are refused`() {
        assertThat(reason("ftp://example.com/file")).isEqualTo(SidekickWebPolicy.Reason.UNSUPPORTED_SCHEME)
        assertThat(reason("file:///etc/passwd")).isEqualTo(SidekickWebPolicy.Reason.UNSUPPORTED_SCHEME)
        assertThat(reason("javascript:alert(1)")).isEqualTo(SidekickWebPolicy.Reason.UNSUPPORTED_SCHEME)
    }

    @Test
    fun `credentials in the url are refused`() {
        assertThat(reason("https://user:pass@example.com/"))
            .isEqualTo(SidekickWebPolicy.Reason.CREDENTIALS_IN_URL)
    }

    @Test
    fun `loopback and private addresses are refused`() {
        val private = listOf(
            "https://localhost/",
            "https://app.localhost/",
            "https://127.0.0.1/",
            "https://10.0.0.1/",
            "https://192.168.1.1/",
            "https://172.16.0.1/",
            "https://172.31.255.255/",
            "https://169.254.1.1/",
            "https://100.64.0.1/",
            "https://[::1]/",
            "https://[fd12::1]/",
            "https://[fe80::1]/",
            "https://nas.local/",
            "https://router.internal/",
            "https://intranet/"
        )
        private.forEach { url ->
            assertThat(reason(url)).isEqualTo(SidekickWebPolicy.Reason.PRIVATE_HOST)
        }
    }

    @Test
    fun `public addresses stay reachable`() {
        assertThat(SidekickWebPolicy.isPrivateHost("8.8.8.8")).isFalse()
        assertThat(SidekickWebPolicy.isPrivateHost("172.32.0.1")).isFalse()
        assertThat(SidekickWebPolicy.isPrivateHost("3com.example.com")).isFalse()
        assertThat(allowed("https://example.com/docs?q=1")).isNotNull()
    }

    @Test
    fun `the port is preserved when it is not the https default`() {
        assertThat(allowed("https://example.com:8443/x")!!.url).isEqualTo("https://example.com:8443/x")
        assertThat(allowed("https://example.com:443/x")!!.url).isEqualTo("https://example.com/x")
    }

    @Test
    fun `an over-long url is refused`() {
        val long = "https://example.com/" + "a".repeat(SidekickWebPolicy.MAX_URL_LENGTH)
        assertThat(reason(long)).isEqualTo(SidekickWebPolicy.Reason.TOO_LONG)
    }

    @Test
    fun `an unparseable url is refused`() {
        assertThat(reason("not a url")).isEqualTo(SidekickWebPolicy.Reason.MALFORMED)
        assertThat(reason("")).isEqualTo(SidekickWebPolicy.Reason.MALFORMED)
    }
}
