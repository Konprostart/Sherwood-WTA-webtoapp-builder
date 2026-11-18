package com.sherwood.wta.core.sidekick.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The DuckDuckGo result page is parsed by hand, so its shape is pinned here rather than
 * discovered in production. The fixture mirrors the real markup: wrapped `/l/?uddg=` links,
 * HTML in titles and snippets, and a result that must be filtered because it is private.
 */
class SidekickSearchParserTest {

    private val duckDuckGoPage = """
        <html><body>
        <div class="result results_links results_links_deep web-result">
          <a rel="nofollow" class="result__a"
             href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2Fguide%2Ftopics%2Fpermissions&amp;rut=abc">
             Permissions &amp; privacy
          </a>
          <a class="result__snippet" href="#">Grant <b>permissions</b> at runtime.</a>
        </div>
        <div class="result results_links results_links_deep web-result">
          <a rel="nofollow" class="result__a"
             href="//duckduckgo.com/l/?uddg=https%3A%2F%2F192.168.1.1%2Fadmin&rut=def">
             Local router
          </a>
          <a class="result__snippet" href="#">Should never be returned.</a>
        </div>
        <div class="result results_links results_links_deep web-result">
          <a rel="nofollow" class="result__a"
             href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2Fggml-org%2Fwhisper.cpp&rut=ghi">
             whisper.cpp
          </a>
          <a class="result__snippet" href="#">Port of OpenAI's Whisper model in C/C++.</a>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `result links are unwrapped and private results are dropped`() {
        val hits = SidekickSearch.parseDuckDuckGo(duckDuckGoPage, limit = 10)

        assertThat(hits).hasSize(2)
        assertThat(hits[0].url).isEqualTo("https://developer.android.com/guide/topics/permissions")
        assertThat(hits[0].title).isEqualTo("Permissions & privacy")
        assertThat(hits[0].snippet).isEqualTo("Grant permissions at runtime.")
        assertThat(hits[1].url).isEqualTo("https://github.com/ggml-org/whisper.cpp")
    }

    @Test
    fun `the limit is respected`() {
        assertThat(SidekickSearch.parseDuckDuckGo(duckDuckGoPage, limit = 1)).hasSize(1)
    }

    @Test
    fun `a captcha page yields no results instead of garbage`() {
        val captcha = "<html><body><div class='anomaly-modal'>bots use DuckDuckGo too</div></body></html>"
        assertThat(SidekickSearch.parseDuckDuckGo(captcha, limit = 5)).isEmpty()
    }

    @Test
    fun `wrapped links resolve to their target`() {
        assertThat(
            SidekickSearch.unwrapDuckDuckGo("/l/?uddg=https%3A%2F%2Fexample.com%2Fdoc")
        ).isEqualTo("https://example.com/doc")
        assertThat(
            SidekickSearch.unwrapDuckDuckGo("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com")
        ).isEqualTo("https://example.com")
    }

    @Test
    fun `a direct link passes through untouched`() {
        assertThat(SidekickSearch.unwrapDuckDuckGo("https://example.com/page"))
            .isEqualTo("https://example.com/page")
    }

    @Test
    fun `a searx result list is parsed`() {
        val json = """
            {
              "results": [
                {"title": "Android docs", "url": "https://developer.android.com/", "content": "Official docs."},
                {"title": "private", "url": "http://10.0.0.2/", "content": "nope"},
                {"title": "Kotlin", "url": "https://kotlinlang.org/", "content": "The language."}
              ]
            }
        """.trimIndent()

        val hits = SidekickSearch.parseSearx(json, limit = 10)
        assertThat(hits).hasSize(2)
        assertThat(hits[0].url).isEqualTo("https://developer.android.com/")
        assertThat(hits[1].title).isEqualTo("Kotlin")
    }

    @Test
    fun `a non-json searx response is not mistaken for results`() {
        assertThat(SidekickSearch.parseSearx("<html>error</html>", limit = 5)).isEmpty()
        assertThat(SidekickSearch.parseSearx("{}", limit = 5)).isEmpty()
    }
}
