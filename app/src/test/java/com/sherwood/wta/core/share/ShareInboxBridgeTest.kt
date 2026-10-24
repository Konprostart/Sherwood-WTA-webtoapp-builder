package com.sherwood.wta.core.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareInboxBridgeTest {

    private lateinit var context: Context
    private val imageUri: Uri = Uri.parse("content://media/external/images/media/99")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, ShareReceiveContract.INBOX_DIR_NAME).deleteRecursively()
    }

    @Test
    fun `readChunk returns base64 of the persisted payload`() = runBlocking {
        val payload = ByteArray(300) { i -> (i * 3).toByte() }
        shadowOf(context.contentResolver)
            .registerInputStream(imageUri, ByteArrayInputStream(payload))
        val item = SharedContentInbox.acceptIntent(
            context,
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, imageUri)
            },
            listOf(ShareReceiveContract.MIME_IMAGES)
        ).single()

        val bridge = ShareInboxBridge(context)
        assertThat(bridge.payloadSize(item.id)).isEqualTo(payload.size.toLong())
        assertThat(bridge.payloadMime(item.id)).isEqualTo("image/png")
        val decoded = Base64.decode(bridge.readChunk(item.id, 0, payload.size), Base64.NO_WRAP)
        assertThat(decoded).isEqualTo(payload)
        assertThat(bridge.readChunk(item.id, payload.size, 16)).isEmpty()
        assertThat(bridge.payloadSize("missing")).isEqualTo(-1L)
        assertThat(bridge.readChunk("missing", 0, 8)).isEmpty()
    }
}
