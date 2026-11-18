package com.sherwood.wta.core.sidekick

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.sidekick.research.SidekickResearch
import org.junit.Test

/** Pure helpers that decide what the research prompt may contain. */
class SidekickResearchArgsTest {

    @Test
    fun `links are found, de-duplicated and punctuation is stripped`() {
        val question = "Look at https://github.com/a/b and https://github.com/a/b, " +
            "then https://example.com/doc?x=1. Thanks!"
        val urls = SidekickResearch.extractUrls(question)
        assertThat(urls).containsExactly(
            "https://github.com/a/b",
            "https://example.com/doc?x=1"
        ).inOrder()
    }

    @Test
    fun `a link glued to a closing bracket is still usable`() {
        val urls = SidekickResearch.extractUrls("see (https://example.com/a) now")
        assertThat(urls).containsExactly("https://example.com/a")
    }

    @Test
    fun `no links means no automatic fetch`() {
        assertThat(SidekickResearch.extractUrls("why did the build fail?")).isEmpty()
    }

    @Test
    fun `valid arguments survive sanitising`() {
        assertThat(SidekickEngine.sanitizeArgumentsJson("""{"query":"kotlin"}"""))
            .isEqualTo("""{"query":"kotlin"}""")
    }

    @Test
    fun `a truncated argument stream does not crash the loop`() {
        assertThat(SidekickEngine.sanitizeArgumentsJson("""{"query":"kot"""))
            .isEqualTo("{}")
        assertThat(SidekickEngine.sanitizeArgumentsJson("not json at all")).isEqualTo("{}")
        assertThat(SidekickEngine.sanitizeArgumentsJson("")).isEqualTo("{}")
    }

    @Test
    fun `an image rejection is recognised only for 4xx image errors`() {
        assertThat(SidekickEngine.looksLikeImageRejection("Invalid image_url in messages", 400)).isTrue()
        assertThat(
            SidekickEngine.looksLikeImageRejection("This model does not support vision input", 422)
        ).isTrue()
        // A rate limit or an auth failure is not an image problem.
        assertThat(SidekickEngine.looksLikeImageRejection("rate limited", 429)).isFalse()
        assertThat(SidekickEngine.looksLikeImageRejection("unauthorized", 401)).isFalse()
        // A generic 400 must not trigger a second billed request.
        assertThat(SidekickEngine.looksLikeImageRejection("invalid request", 400)).isFalse()
        assertThat(SidekickEngine.looksLikeImageRejection(null, 400)).isFalse()
        assertThat(SidekickEngine.looksLikeImageRejection("image", null)).isFalse()
    }

    @Test
    fun `a tool result is clipped without splitting a surrogate pair`() {
        val text = "a" + "\uD83D\uDE00".repeat(20) + "b".repeat(SidekickEngine.MAX_TOOL_MESSAGE_CHARS)
        val clipped = SidekickEngine.clipToolText(text)
        assertThat(clipped.length).isAtMost(SidekickEngine.MAX_TOOL_MESSAGE_CHARS + 64)
        assertThat(clipped).endsWith("[tool output truncated]")
    }

    @Test
    fun `an omitted image is announced in the user turn`() {
        val note = SidekickPrompt.imageOmittedNote(1)
        assertThat(note).contains("an image")
        assertThat(note).contains("not sent")
        assertThat(SidekickPrompt.imageOmittedNote(3)).contains("3 images")
        assertThat(SidekickPrompt.imageOmittedNote(0)).isEmpty()
    }
}
