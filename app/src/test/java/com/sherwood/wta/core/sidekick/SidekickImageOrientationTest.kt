package com.sherwood.wta.core.sidekick

import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.sidekick.input.SidekickImageDecoder
import org.junit.Test

/**
 * The EXIF orientation table is small and easy to get wrong in a way that only shows up on
 * a front-camera photo, so every documented value is pinned here.
 */
class SidekickImageOrientationTest {

    @Test
    fun `every EXIF orientation value maps to its transform`() {
        assertThat(SidekickImageDecoder.orientationFor(1))
            .isEqualTo(SidekickImageDecoder.Orientation.NORMAL)
        assertThat(SidekickImageDecoder.orientationFor(2))
            .isEqualTo(SidekickImageDecoder.Orientation.FLIP_HORIZONTAL)
        assertThat(SidekickImageDecoder.orientationFor(3))
            .isEqualTo(SidekickImageDecoder.Orientation.ROTATE_180)
        assertThat(SidekickImageDecoder.orientationFor(4))
            .isEqualTo(SidekickImageDecoder.Orientation.FLIP_VERTICAL)
        assertThat(SidekickImageDecoder.orientationFor(5))
            .isEqualTo(SidekickImageDecoder.Orientation.TRANSPOSE)
        assertThat(SidekickImageDecoder.orientationFor(6))
            .isEqualTo(SidekickImageDecoder.Orientation.ROTATE_90)
        assertThat(SidekickImageDecoder.orientationFor(7))
            .isEqualTo(SidekickImageDecoder.Orientation.TRANSVERSE)
        assertThat(SidekickImageDecoder.orientationFor(8))
            .isEqualTo(SidekickImageDecoder.Orientation.ROTATE_270)
    }

    @Test
    fun `an unknown orientation is treated as normal instead of crashing`() {
        assertThat(SidekickImageDecoder.orientationFor(0))
            .isEqualTo(SidekickImageDecoder.Orientation.NORMAL)
        assertThat(SidekickImageDecoder.orientationFor(99))
            .isEqualTo(SidekickImageDecoder.Orientation.NORMAL)
    }
}
