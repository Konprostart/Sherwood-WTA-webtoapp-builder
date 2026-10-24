package com.sherwood.wta.ui.viewmodel

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.data.model.Announcement
import com.sherwood.wta.data.model.AppType
import com.sherwood.wta.data.model.BgmConfig
import com.sherwood.wta.data.model.BgmItem
import com.sherwood.wta.data.model.GalleryConfig
import com.sherwood.wta.data.model.GalleryItem
import com.sherwood.wta.data.model.GalleryItemType
import com.sherwood.wta.data.model.HtmlConfig
import com.sherwood.wta.data.model.SplashConfig
import com.sherwood.wta.data.model.SplashType
import com.sherwood.wta.data.model.WebApp
import com.sherwood.wta.data.model.WebViewConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditStateMapperTest {

    @Test
    fun `web app maps to edit state and back through draft builder`() {
        val webApp = WebApp(
            name = "Sample",
            url = "https://example.com",
            iconPath = "file:///icon.png",
            appType = AppType.WEB,
            htmlConfig = HtmlConfig(entryFile = "index.html"),
            splashConfig = SplashConfig(type = SplashType.IMAGE, mediaPath = "file:///splash.png"),
            bgmConfig = BgmConfig(
                playlist = listOf(BgmItem(name = "song", path = "/music.mp3"))
            ),
            announcement = Announcement(title = "Notice", content = "hello"),
            webViewConfig = WebViewConfig(hideToolbar = true),
            pluginsEnabled = true,
            pluginIds = listOf("mod-1"),
        )

        val editState = webApp.toEditState()

        assertThat(editState.name).isEqualTo("Sample")
        assertThat(editState.iconUri).isEqualTo(Uri.parse("file:///icon.png"))
        assertThat(editState.splashMediaUri).isEqualTo(Uri.parse("file:///splash.png"))
        assertThat(editState.pluginIds).containsExactly("mod-1")

        val payload = editState.toDraftPayload(
            normalizedUrl = "https://example.com",
            iconPath = "file:///icon.png",
            pluginIds = editState.pluginIds,
            currentThemeType = "AURORA",
            externalizedWebViewConfig = editState.webViewConfig,
        )

        val rebuilt = webApp.applyDraft(editState, payload)

        assertThat(rebuilt.name).isEqualTo("Sample")
        assertThat(rebuilt.url).isEqualTo("https://example.com")
        assertThat(rebuilt.pluginIds).containsExactly("mod-1")
    }

    @Test
    fun `blank activation dialog is normalized away in draft`() {
        val state = EditState(
            name = "Draft",
            url = "https://example.com",
        )

        val payload = state.toDraftPayload(
            normalizedUrl = "https://example.com",
            iconPath = null,
            pluginIds = emptySet(),
            currentThemeType = "AURORA",
            externalizedWebViewConfig = state.webViewConfig,
        )

        val rebuilt = null.applyDraft(state, payload)

        assertThat(rebuilt.activationDialogConfig).isNull()
    }
}
