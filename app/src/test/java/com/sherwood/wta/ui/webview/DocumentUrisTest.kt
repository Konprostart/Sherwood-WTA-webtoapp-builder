package com.sherwood.wta.ui.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Writers must emit ONLY the new `sherwood-wta` scheme (legacy `webtoapp://`
 * is never produced again), and the produced strings must stay stable so
 * recents reuse and preview session keys keep matching the same document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentUrisTest {

    @Test
    fun `all builders emit the new scheme with stable payloads`() {
        assertThat(DocumentUris.SCHEME).isEqualTo("sherwood-wta")
        assertThat(DocumentUris.LEGACY_SCHEME).isEqualTo("webtoapp")

        assertThat(DocumentUris.forApp(7).toString()).isEqualTo("sherwood-wta://webapp/7")
        assertThat(DocumentUris.forUrl("https://example.com").toString())
            .isEqualTo("sherwood-wta://url/${"https://example.com".hashCode()}")
        assertThat(DocumentUris.forPreviewJson("{\"a\":1}").toString())
            .isEqualTo("sherwood-wta://preview/${"{\"a\":1}".hashCode()}")
        assertThat(DocumentUris.forTest("https://t.example").toString())
            .isEqualTo("sherwood-wta://test/${"https://t.example".hashCode()}")
        assertThat(DocumentUris.forAppPreview(42).toString())
            .isEqualTo("sherwood-wta://preview/app/42")
        assertThat(DocumentUris.forUrlPreview("https://p.example?x=1").toString())
            .isEqualTo("sherwood-wta://preview/url?u=https%3A%2F%2Fp.example%3Fx%3D1")
    }

    @Test
    fun `no builder emits the legacy scheme`() {
        val produced = listOf(
            DocumentUris.forApp(1),
            DocumentUris.forUrl("u"),
            DocumentUris.forPreviewJson("j"),
            DocumentUris.forTest("t"),
            DocumentUris.forAppPreview(2),
            DocumentUris.forUrlPreview("u")
        )

        assertThat(produced.map { it.scheme }).doesNotContain("webtoapp")
        assertThat(produced.none { it.toString().startsWith("webtoapp://") }).isTrue()
    }
}
