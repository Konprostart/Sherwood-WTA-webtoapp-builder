package com.sherwood.wta.core.sidekick.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A tag stripper is easy to get subtly wrong: a leftover `<script>` body reads as prose, an
 * undecoded entity reads as `&amp;`, and a clipped emoji breaks the prompt encoding.
 */
class SidekickPageReaderTest {

    private val page = """
        <html><head>
          <title>A &amp; B — docs</title>
          <style>body { color: red }</style>
          <script>var secret = "instruction";</script>
        </head><body>
          <h1>Heading</h1>
          <p>First paragraph.</p>
          <p>Second&nbsp;paragraph with &lt;code&gt; and &#39;quotes&#39;.</p>
          <div>Third line &mdash; done</div>
          <noscript>enable js</noscript>
        </body></html>
    """.trimIndent()

    @Test
    fun `the title is decoded and trimmed`() {
        assertThat(SidekickPageReader.title(page)).isEqualTo("A & B — docs")
    }

    @Test
    fun `scripts and styles never reach the text`() {
        val text = SidekickPageReader.toText(page)
        assertThat(text).doesNotContain("color: red")
        assertThat(text).doesNotContain("var secret")
        assertThat(text).doesNotContain("enable js")
    }

    @Test
    fun `entities are decoded and block elements become lines`() {
        val text = SidekickPageReader.toText(page)
        assertThat(text).contains("Second paragraph with <code> and 'quotes'.")
        assertThat(text).contains("Third line — done")
        assertThat(text).contains("Heading")
        assertThat(text).contains("\n")
    }

    @Test
    fun `numeric entities decode in both bases`() {
        assertThat(SidekickPageReader.decodeEntities("&#65;&#x42;"))
            .isEqualTo("AB")
        assertThat(SidekickPageReader.decodeEntities("&#x1F600;"))
            .isEqualTo("\uD83D\uDE00")
    }

    @Test
    fun `an unknown entity is left visible rather than deleted`() {
        assertThat(SidekickPageReader.decodeEntities("&notareal;"))
            .isEqualTo("&notareal;")
    }

    @Test
    fun `clipping never splits a surrogate pair`() {
        val text = "ab\uD83D\uDE00cd"
        val clipped = SidekickPageReader.clip(text, 3)
        assertThat(clipped).isEqualTo("ab")
    }

    @Test
    fun `an over-long page is clipped with a marker`() {
        val text = SidekickPageReader.toText("<p>" + "x".repeat(500) + "</p>", cap = 100)
        assertThat(text).contains("[truncated]")
        assertThat(text.length).isLessThan(500)
    }

    @Test
    fun `binary content types are not readable`() {
        assertThat(SidekickPageReader.isReadable("application/pdf")).isFalse()
        assertThat(SidekickPageReader.isReadable("image/png")).isFalse()
        assertThat(SidekickPageReader.isReadable("text/html; charset=utf-8")).isTrue()
        assertThat(SidekickPageReader.isReadable("application/json")).isTrue()
        assertThat(SidekickPageReader.isReadable(null)).isTrue()
    }

    @Test
    fun `plain text is trimmed and clipped`() {
        assertThat(SidekickPageReader.toPlainText("hello  \r\n", cap = 100)).isEqualTo("hello")
        assertThat(SidekickPageReader.toPlainText("y".repeat(50), cap = 10)).contains("[truncated]")
    }
}
