package com.sherwood.wta.core.sidekick.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Storage-level rules for the speech server: a URL that the policy refuses must never
 * become "configured", and a key must not be storable on a cleartext URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SidekickSpeechEndpointStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newStore(): SidekickSpeechEndpointStore {
        context.getSharedPreferences("sherwood_sidekick_speech", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // A fresh instance per test: the in-memory token cache must not leak between
        // cases, and each case starts from an empty store.
        return SidekickSpeechEndpointStore(context)
    }

    @Before
    fun setUp() {
        newStore()
    }

    @Test
    fun `https server is stored normalised and becomes usable`() {
        val store = newStore()

        val result = store.saveServer(
            SidekickSpeechProtocol.OPENAI_COMPATIBLE,
            "  https://whisper.example.com/  ",
            "whisper-1"
        )

        assertThat(result).isEqualTo(SidekickSpeechEndpointStore.SaveResult.Saved)
        assertThat(store.baseUrl).isEqualTo("https://whisper.example.com")
        assertThat(store.isUsable()).isTrue()
    }

    @Test
    fun `public cleartext is refused and nothing is stored`() {
        val store = newStore()

        val result = store.saveServer(
            SidekickSpeechProtocol.OPENAI_COMPATIBLE,
            "http://whisper.example.com:8000",
            ""
        )

        assertThat(result).isEqualTo(
            SidekickSpeechEndpointStore.SaveResult.Invalid(SidekickEndpointError.PUBLIC_CLEARTEXT)
        )
        assertThat(store.baseUrl).isEmpty()
        assertThat(store.isUsable()).isFalse()
    }

    @Test
    fun `lan cleartext is accepted while no key is stored`() {
        val store = newStore()

        val result = store.saveServer(
            SidekickSpeechProtocol.WHISPER_CPP,
            "http://192.168.1.20:8080",
            ""
        )

        assertThat(result).isEqualTo(SidekickSpeechEndpointStore.SaveResult.Saved)
        assertThat(store.endpoint()?.transcriptionUrl)
            .isEqualTo("http://192.168.1.20:8080/inference")
    }

    @Test
    fun `a key cannot be stored on a cleartext url`() {
        val store = newStore()
        store.saveServer(SidekickSpeechProtocol.WHISPER_CPP, "http://192.168.1.20:8080", "")

        val result = store.saveToken("some-secret-key")

        assertThat(result.isFailure).isTrue()
        assertThat(store.hasToken()).isFalse()
    }

    @Test
    fun `an openai endpoint defaults the model and whisper cpp keeps it blank`() {
        val store = newStore()
        store.saveServer(SidekickSpeechProtocol.OPENAI_COMPATIBLE, "https://host", "")
        assertThat(store.endpoint()?.model).isEqualTo(SidekickSpeechEndpointStore.DEFAULT_OPENAI_MODEL)

        store.saveServer(SidekickSpeechProtocol.WHISPER_CPP, "https://host/inference", "")
        val endpoint = store.endpoint()
        assertThat(endpoint?.model).isEmpty()
        // The pasted full path is not doubled.
        assertThat(endpoint?.transcriptionUrl).isEqualTo("https://host/inference")
    }

    @Test
    fun `configurationError reports why a stored url is refused`() {
        val store = newStore()
        store.saveServer(SidekickSpeechProtocol.OPENAI_COMPATIBLE, "https://host", "")
        assertThat(store.configurationError()).isNull()

        // A URL edited to something the policy refuses must not stay silently usable.
        context.getSharedPreferences("sherwood_sidekick_speech", Context.MODE_PRIVATE)
            .edit().putString("base_url", "http://example.com").commit()
        assertThat(store.isUsable()).isFalse()
        assertThat(store.endpoint()).isNull()
        assertThat(store.configurationError())
            .isEqualTo(SidekickEndpointError.PUBLIC_CLEARTEXT)
    }
}
