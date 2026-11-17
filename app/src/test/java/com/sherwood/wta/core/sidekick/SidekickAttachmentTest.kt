package com.sherwood.wta.core.sidekick

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentBudget
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentLimits
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentRejection
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment
import com.sherwood.wta.core.sidekick.input.SidekickTextAttachment
import org.junit.Test

/**
 * The attachment rules decide what reaches a paid provider, so they are pinned as pure
 * functions: how big, how many, what counts as text, and when the budget refuses instead of
 * silently truncating.
 */
class SidekickAttachmentTest {

    private fun image(name: String = "shot.jpg") =
        SidekickImageAttachment(name = name, bytes = byteArrayOf(1, 2, 3))

    private fun text(name: String = "app.log", body: String = "line") =
        SidekickTextAttachment(name = name, text = body)

    // ------------------------------------------------------------------ classification

    @Test
    fun `mime types are classified by prefix and by exact text types`() {
        assertThat(SidekickAttachmentLimits.isImageMime("image/png")).isTrue()
        assertThat(SidekickAttachmentLimits.isImageMime("IMAGE/JPEG")).isTrue()
        assertThat(SidekickAttachmentLimits.isImageMime("application/pdf")).isFalse()
        assertThat(SidekickAttachmentLimits.isImageMime(null)).isFalse()

        assertThat(SidekickAttachmentLimits.isTextMime("text/plain")).isTrue()
        assertThat(SidekickAttachmentLimits.isTextMime("text/csv; charset=utf-8")).isTrue()
        assertThat(SidekickAttachmentLimits.isTextMime("application/json")).isTrue()
        assertThat(SidekickAttachmentLimits.isTextMime("application/pdf")).isFalse()
    }

    @Test
    fun `an octet-stream download is accepted only through its extension`() {
        // This is exactly why the extension check exists: most providers report unknown
        // types for downloads, and the name is the only reliable signal.
        assertThat(SidekickAttachmentLimits.isTextMime("application/octet-stream")).isFalse()
        assertThat(SidekickAttachmentLimits.isTextExtension("crash.log")).isTrue()
        assertThat(SidekickAttachmentLimits.isTextExtension("MainActivity.kt")).isTrue()
        assertThat(SidekickAttachmentLimits.isTextExtension("report.PDF")).isFalse()
        assertThat(SidekickAttachmentLimits.isTextExtension("noextension")).isFalse()
    }

    @Test
    fun `a display name is reduced to its basename`() {
        assertThat(SidekickAttachmentLimits.sanitizeName("/storage/emulated/0/Download/app.log", "file"))
            .isEqualTo("app.log")
        assertThat(SidekickAttachmentLimits.sanitizeName("C:\\Users\\me\\error.txt", "file"))
            .isEqualTo("error.txt")
        // The full path must never reach the prompt: models repeat paths they were given.
        assertThat(SidekickAttachmentLimits.sanitizeName("../secret/app.log", "file"))
            .isEqualTo("app.log")
    }

    @Test
    fun `a missing or degenerate display name falls back`() {
        assertThat(SidekickAttachmentLimits.sanitizeName(null, "file")).isEqualTo("file")
        assertThat(SidekickAttachmentLimits.sanitizeName("   ", "file")).isEqualTo("file")
        assertThat(SidekickAttachmentLimits.sanitizeName("..", "file")).isEqualTo("file")
        assertThat(SidekickAttachmentLimits.sanitizeName("..", "image")).isEqualTo("image")
    }

    @Test
    fun `control characters are stripped from a display name`() {
        // A provider-supplied name with a newline could otherwise forge a header line in
        // the prompt.
        assertThat(SidekickAttachmentLimits.sanitizeName("a\nIGNORE\tALL", "file"))
            .isEqualTo("aIGNOREALL")
        assertThat(SidekickAttachmentLimits.sanitizeName("\u0000", "fallback"))
            .isEqualTo("fallback")
    }

    @Test
    fun `only formats that can carry transparency are flattened`() {
        assertThat(SidekickAttachmentLimits.mayHaveAlpha("image/png")).isTrue()
        assertThat(SidekickAttachmentLimits.mayHaveAlpha("image/webp")).isTrue()
        assertThat(SidekickAttachmentLimits.mayHaveAlpha("image/gif")).isTrue()
        assertThat(SidekickAttachmentLimits.mayHaveAlpha("image/jpeg")).isFalse()
        assertThat(SidekickAttachmentLimits.mayHaveAlpha("IMAGE/PNG; charset=binary")).isTrue()
        assertThat(SidekickAttachmentLimits.mayHaveAlpha(null)).isFalse()
    }

    @Test
    fun `an over-long name is shortened, not rejected`() {
        val long = "a".repeat(300) + ".kt"
        val name = SidekickAttachmentLimits.sanitizeName(long, "file")
        assertThat(name.length).isEqualTo(SidekickAttachmentLimits.MAX_NAME_CHARS)
    }

    // ------------------------------------------------------------------ budget

    @Test
    fun `the image count is capped per question`() {
        val one = listOf(image("a.jpg"))
        val two = one + image("b.jpg")

        assertThat(SidekickAttachmentBudget.rejection(one, image("c.jpg"))).isNull()
        assertThat(SidekickAttachmentBudget.rejection(two, image("c.jpg")))
            .isEqualTo(SidekickAttachmentRejection.BUDGET)
        // A later image must never silently replace an earlier one.
        assertThat(two).hasSize(2)
    }

    @Test
    fun `the total attachment count is capped`() {
        val full = (1..SidekickAttachmentLimits.MAX_ATTACHMENTS).map { text("f$it.log") }
        assertThat(SidekickAttachmentBudget.rejection(full, text("extra.log")))
            .isEqualTo(SidekickAttachmentRejection.BUDGET)
    }

    @Test
    fun `an empty text attachment is refused rather than attached`() {
        assertThat(SidekickAttachmentBudget.rejection(emptyList(), text(body = "   ")))
            .isEqualTo(SidekickAttachmentRejection.EMPTY)
    }

    @Test
    fun `text is clipped to the remaining budget instead of being refused while room exists`() {
        val body = "x".repeat(1_000)
        val existing = listOf(text("a.log", body))
        val remaining = SidekickAttachmentBudget.remainingTextChars(existing)

        assertThat(remaining).isEqualTo(SidekickAttachmentLimits.MAX_TEXT_CHARS_TOTAL - 1_000)

        val big = SidekickTextAttachment(
            name = "b.log",
            text = "y".repeat(SidekickAttachmentLimits.MAX_TEXT_CHARS_TOTAL)
        )
        val fitted = SidekickAttachmentBudget.fitText(existing, big)
        assertThat(fitted).isNotNull()
        assertThat(fitted!!.text.length).isEqualTo(remaining)
        // Truncation is recorded so the model knows it read a prefix.
        assertThat(fitted.truncated).isTrue()
    }

    @Test
    fun `a text attachment that fits is returned untouched`() {
        val candidate = text("small.log", "short")
        val fitted = SidekickAttachmentBudget.fitText(emptyList(), candidate)
        assertThat(fitted).isEqualTo(candidate)
        assertThat(fitted!!.truncated).isFalse()
    }

    @Test
    fun `no remaining text budget refuses instead of attaching nothing`() {
        val huge = text("big.log", "z".repeat(SidekickAttachmentLimits.MAX_TEXT_CHARS_TOTAL))
        assertThat(SidekickAttachmentBudget.remainingTextChars(listOf(huge))).isEqualTo(0)
        assertThat(SidekickAttachmentBudget.fitText(listOf(huge), text("more.log"))).isNull()
    }

    @Test
    fun `images do not consume the text budget`() {
        val withImage = listOf<SidekickAttachment>(image())
        assertThat(SidekickAttachmentBudget.remainingTextChars(withImage))
            .isEqualTo(SidekickAttachmentLimits.MAX_TEXT_CHARS_TOTAL)
    }
}
