package com.sherwood.wta.core.sidekick

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentKind
import com.sherwood.wta.core.sidekick.input.SidekickAttachmentLimits
import org.junit.Test

/**
 * How a picked file is classified before anything is read.
 *
 * Pinned because the bug it fixes was invisible: an image whose provider reported no MIME
 * type took the text branch and was refused as "not a text file", so a picked screenshot
 * never appeared. The rule is that a reported type is believed, and only a type that says
 * nothing lets the extension decide.
 */
class SidekickAttachmentKindTest {

    @Test
    fun `an image mime type wins over the extension`() {
        assertThat(SidekickAttachmentLimits.kindFor("image/png", "screenshot.png"))
            .isEqualTo(SidekickAttachmentKind.IMAGE)
        assertThat(SidekickAttachmentLimits.kindFor("image/jpeg", "IMG_0042"))
            .isEqualTo(SidekickAttachmentKind.IMAGE)
    }

    @Test
    fun `an image with no reported type is still an image`() {
        // The photo picker on some ROMs, and every file manager serving a download, hand a
        // picture over as null or as the generic binary type. This is the reported bug.
        assertThat(SidekickAttachmentLimits.kindFor(null, "screenshot.png"))
            .isEqualTo(SidekickAttachmentKind.IMAGE)
        assertThat(SidekickAttachmentLimits.kindFor("application/octet-stream", "photo.jpg"))
            .isEqualTo(SidekickAttachmentKind.IMAGE)
        assertThat(SidekickAttachmentLimits.kindFor(null, "capture-1712.JPG"))
            .isEqualTo(SidekickAttachmentKind.IMAGE)
        assertThat(SidekickAttachmentLimits.kindFor(null, "shot.heic"))
            .isEqualTo(SidekickAttachmentKind.IMAGE)
    }

    @Test
    fun `a text file is still classified as text with no reported type`() {
        assertThat(SidekickAttachmentLimits.kindFor("application/octet-stream", "crash.log"))
            .isEqualTo(SidekickAttachmentKind.TEXT)
        assertThat(SidekickAttachmentLimits.kindFor(null, "MainActivity.kt"))
            .isEqualTo(SidekickAttachmentKind.TEXT)
        assertThat(SidekickAttachmentLimits.kindFor("text/plain", "notes"))
            .isEqualTo(SidekickAttachmentKind.TEXT)
    }

    @Test
    fun `a reported type is believed even when the name disagrees`() {
        // A provider that names a PDF must not have it decoded as a bitmap just because
        // someone renamed the file to .png.
        assertThat(SidekickAttachmentLimits.kindFor("application/pdf", "report.png"))
            .isEqualTo(SidekickAttachmentKind.UNSUPPORTED)
        assertThat(SidekickAttachmentLimits.kindFor("text/csv", "data.png"))
            .isEqualTo(SidekickAttachmentKind.TEXT)
    }

    @Test
    fun `a pick that is neither text nor a known image is unsupported`() {
        assertThat(SidekickAttachmentLimits.kindFor(null, "noextension"))
            .isEqualTo(SidekickAttachmentKind.UNSUPPORTED)
        assertThat(SidekickAttachmentLimits.kindFor(null, ""))
            .isEqualTo(SidekickAttachmentKind.UNSUPPORTED)
        assertThat(SidekickAttachmentLimits.kindFor("application/zip", "bundle.zip"))
            .isEqualTo(SidekickAttachmentKind.UNSUPPORTED)
        assertThat(SidekickAttachmentLimits.kindFor(null, "movie.mp4"))
            .isEqualTo(SidekickAttachmentKind.UNSUPPORTED)
    }

    @Test
    fun `only a missing type falls back to the extension for alpha handling`() {
        // A reported type is authoritative: a JPEG named .png is not flattened.
        assertThat(SidekickAttachmentLimits.mayHaveAlpha("image/jpeg", "shot.png")).isFalse()
        assertThat(SidekickAttachmentLimits.mayHaveAlpha("image/png", "shot.jpg")).isTrue()
        // With no type, the extension decides — erring towards flattening only costs a pass.
        assertThat(SidekickAttachmentLimits.mayHaveAlpha(null, "shot.png")).isTrue()
        assertThat(SidekickAttachmentLimits.mayHaveAlpha(null, "shot.webp")).isTrue()
        assertThat(SidekickAttachmentLimits.mayHaveAlpha("application/octet-stream", "a.jpg"))
            .isFalse()
        // A defaulted name ("image") carries no extension, so nothing is assumed.
        assertThat(SidekickAttachmentLimits.mayHaveAlpha(null, "image")).isFalse()
    }

    @Test
    fun `an unusable type is recognised separately from a real one`() {
        assertThat(SidekickAttachmentLimits.isUnhelpfulMime(null)).isTrue()
        assertThat(SidekickAttachmentLimits.isUnhelpfulMime("")).isTrue()
        assertThat(SidekickAttachmentLimits.isUnhelpfulMime("application/octet-stream")).isTrue()
        assertThat(SidekickAttachmentLimits.isUnhelpfulMime("application/pdf")).isFalse()
        assertThat(SidekickAttachmentLimits.isUnhelpfulMime("image/png")).isFalse()
    }
}
