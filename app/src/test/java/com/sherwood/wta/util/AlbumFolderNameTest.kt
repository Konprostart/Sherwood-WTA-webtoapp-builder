package com.sherwood.wta.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlbumFolderNameTest {
    @Test
    fun `blank and illegal names fall back to Sherwood Studio`() {
        assertThat(AlbumFolderName.sanitize(null)).isEqualTo("Sherwood Studio")
        assertThat(AlbumFolderName.sanitize("  ")).isEqualTo("Sherwood Studio")
        assertThat(AlbumFolderName.sanitize("../")).isEqualTo("Sherwood Studio")
        assertThat(AlbumFolderName.sanitize("a/b")).isEqualTo("ab")
    }

    @Test
    fun `a single segment is kept`() {
        assertThat(AlbumFolderName.sanitize(" 小说 ")).isEqualTo("小说")
    }
}
