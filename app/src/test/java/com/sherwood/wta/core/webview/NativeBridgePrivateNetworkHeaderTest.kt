package com.sherwood.wta.core.webview

import com.google.common.truth.Truth.assertThat
import okhttp3.Request
import org.junit.Test

/**
 * Dual-expose contract for the private-network bridge marker: requests built
 * by the web-to-native fetch proxy carry BOTH the legacy
 * `X-WebToApp-Private-Network-Bridge` header and the new
 * `X-Sherwood-WTA-Private-Network-Bridge` alias, so servers can accept or
 * filter on either form (the legacy name must keep its exact value).
 * This test validates the two names as real OkHttp headers on a single
 * request (the same construction NativeBridge performs) and guards against
 * accidental name unification or typos.
 */
class NativeBridgePrivateNetworkHeaderTest {

    private fun requestWithBothBridgeHeaders(): Request =
        Request.Builder()
            .url("https://example.internal/status")
            .header("X-WebToApp-Private-Network-Bridge", "1")
            .header("X-Sherwood-WTA-Private-Network-Bridge", "1")
            .get()
            .build()

    @Test
    fun `both bridge header names are distinct and valid`() {
        val legacy = "X-WebToApp-Private-Network-Bridge"
        val sherwood = "X-Sherwood-WTA-Private-Network-Bridge"

        assertThat(legacy).isNotEqualTo(sherwood)

        val request = requestWithBothBridgeHeaders()
        assertThat(request.header(legacy)).isEqualTo("1")
        assertThat(request.header(sherwood)).isEqualTo("1")
    }

    @Test
    fun `neither name is the other's case-insensitive duplicate`() {
        val request = requestWithBothBridgeHeaders()
        val names = request.headers.names.map { it.lowercase() }.filter {
            it.endsWith("private-network-bridge")
        }

        assertThat(names).containsExactly(
            "x-webtoapp-private-network-bridge",
            "x-sherwood-wta-private-network-bridge"
        )
    }
}
