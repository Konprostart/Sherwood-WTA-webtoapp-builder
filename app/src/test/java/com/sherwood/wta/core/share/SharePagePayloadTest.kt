package com.sherwood.wta.core.share

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharePagePayloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `small files inline a dataUrl`() = runBlocking {
        val file = tmp.newFile("small.png")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val json = buildShareBatch(listOf(fileItem(file)))
        val item = JSONArray(json).getJSONObject(0)
        assertThat(item.getBoolean("inline")).isTrue()
        assertThat(item.getString("dataUrl")).startsWith("data:image/png;base64,")
        assertThat(item.getString("fileUrl")).startsWith("file://")
    }

    @Test
    fun `files over the inline ceiling ship metadata only`() = runBlocking {
        val file = tmp.newFile("large.jpg")
        RandomAccessFile(file, "rw").use { it.setLength(ShareReceiveContract.INLINE_MAX_BYTES + 1) }
        val json = buildShareBatch(listOf(fileItem(file, mime = "image/jpeg", name = "large.jpg")))
        val item = JSONArray(json).getJSONObject(0)
        assertThat(item.getBoolean("inline")).isFalse()
        assertThat(item.has("dataUrl")).isFalse()
        assertThat(item.getString("fileUrl")).isEqualTo("file://" + file.absolutePath)
        assertThat(item.getLong("size")).isEqualTo(file.length())
    }

    @Test
    fun `bootstrap exposes readBlob for oversized payloads`() {
        assertThat(SHARE_INBOX_BOOTSTRAP_JS).contains("readBlob")
        assertThat(SHARE_INBOX_BOOTSTRAP_JS).contains("readDataUrl")
        assertThat(SHARE_INBOX_BOOTSTRAP_JS).contains("version: 2")
        assertThat(SHARE_INBOX_BOOTSTRAP_JS).contains("payloadSize")
        assertThat(SHARE_INBOX_BOOTSTRAP_JS).contains("readChunk")
    }

    private fun fileItem(
        file: File,
        mime: String = "image/png",
        name: String = file.name
    ) = SharedItem(
        id = "id-" + name,
        kind = ShareReceiveContract.KIND_FILE,
        mimeType = mime,
        name = name,
        size = file.length(),
        path = file.absolutePath
    )
}
