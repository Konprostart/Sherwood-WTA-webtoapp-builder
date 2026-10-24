package com.sherwood.wta.ui.webview

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Separate WebApp tasks must reuse the recents card for the same document.
 * FLAG_ACTIVITY_MULTIPLE_TASK, and documentLaunchMode="always", create a new
 * card on every open (#1250).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebViewDocumentLaunchTest {

    @Test
    fun `same document reuses its recents task`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = Uri.parse("webtoapp://webapp/7")
        val intent = WebViewActivity.buildLaunchIntent(
            context,
            separateTasks = true,
            documentUri = uri
        ) {}

        assertThat(intent.component?.className).contains("WebViewDocumentActivity")
        assertThat(intent.data).isEqualTo(uri)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK).isEqualTo(0)

        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val document = manifest.substringAfter("WebViewDocumentActivity")
            .substringBefore("</activity>")
        assertThat(document).contains("android:documentLaunchMode=\"intoExisting\"")
        assertThat(document).doesNotContain("android:documentLaunchMode=\"always\"")
    }

    private fun repoFile(relativePath: String): File {
        val roots = listOf(File("."), File(".."))
        return roots.asSequence()
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: error("Cannot locate $relativePath")
    }
}
