package com.sherwood.wta.core.sidekick.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The URL policy is the gate that decides whether speech can leave the phone in clear
 * text, so every branch here is a security decision rather than a formatting nicety.
 */
class SidekickSpeechEndpointPolicyTest {

    private fun reason(url: String, hasToken: Boolean = false): SidekickEndpointError? {
        val validation = SidekickSpeechEndpointPolicy.validate(url, hasToken)
        return (validation as? SidekickSpeechEndpointPolicy.Validation.Invalid)?.reason
    }

    private fun accepted(url: String, hasToken: Boolean = false): String {
        val validation = SidekickSpeechEndpointPolicy.validate(url, hasToken)
        assertThat(validation).isInstanceOf(SidekickSpeechEndpointPolicy.Validation.Ok::class.java)
        return (validation as SidekickSpeechEndpointPolicy.Validation.Ok).value.baseUrl
    }

    @Test
    fun `https is accepted for any host`() {
        assertThat(accepted("https://whisper.example.com")).isEqualTo("https://whisper.example.com")
        assertThat(reason("https://whisper.example.com", hasToken = true)).isNull()
    }

    @Test
    fun `cleartext is accepted only for loopback and LAN addresses`() {
        assertThat(reason("http://127.0.0.1:8000")).isNull()
        assertThat(reason("http://192.168.1.10:8080")).isNull()
        assertThat(reason("http://10.0.2.2:8080")).isNull()
        assertThat(reason("http://whisperbox:8080")).isNull()
        assertThat(reason("http://whisper.local:8080")).isNull()
    }

    @Test
    fun `cleartext to a public host is refused`() {
        assertThat(reason("http://whisper.example.com"))
            .isEqualTo(SidekickEndpointError.PUBLIC_CLEARTEXT)
        assertThat(reason("http://8.8.8.8:8080"))
            .isEqualTo(SidekickEndpointError.PUBLIC_CLEARTEXT)
    }

    @Test
    fun `cleartext on the LAN is refused once a key is stored`() {
        // The key travels in the same request, so LAN-only cleartext does not make it safe.
        assertThat(reason("http://192.168.1.10:8080", hasToken = true))
            .isEqualTo(SidekickEndpointError.CLEARTEXT_WITH_TOKEN)
        assertThat(reason("https://192.168.1.10:8443", hasToken = true)).isNull()
    }

    @Test
    fun `embedded credentials are refused`() {
        assertThat(reason("https://user:secret@whisper.example.com"))
            .isEqualTo(SidekickEndpointError.CREDENTIALS_IN_URL)
    }

    @Test
    fun `query and fragment are refused`() {
        assertThat(reason("https://whisper.example.com?x=1"))
            .isEqualTo(SidekickEndpointError.QUERY_OR_FRAGMENT)
        assertThat(reason("https://whisper.example.com#top"))
            .isEqualTo(SidekickEndpointError.QUERY_OR_FRAGMENT)
    }

    @Test
    fun `non http schemes and bare text are refused`() {
        assertThat(reason("ftp://whisper.example.com"))
            .isEqualTo(SidekickEndpointError.UNSUPPORTED_SCHEME)
        assertThat(reason("192.168.1.10:8080"))
            .isEqualTo(SidekickEndpointError.MALFORMED)
        assertThat(reason("   ")).isEqualTo(SidekickEndpointError.EMPTY)
    }

    @Test
    fun `normalisation drops the trailing slash and default port`() {
        assertThat(accepted("https://whisper.example.com/")).isEqualTo("https://whisper.example.com")
        assertThat(accepted("https://whisper.example.com:443")).isEqualTo("https://whisper.example.com")
        // A reverse-proxy prefix is part of the base, not something to strip.
        assertThat(accepted("https://example.com/whisper/")).isEqualTo("https://example.com/whisper")
    }

    @Test
    fun `openai endpoint appends the path and keeps a prefix`() {
        assertThat(
            SidekickSpeechEndpointPolicy.transcriptionUrl(
                "https://host/v1",
                SidekickSpeechProtocol.OPENAI_COMPATIBLE
            )
        ).isEqualTo("https://host/v1/audio/transcriptions")
        assertThat(
            SidekickSpeechEndpointPolicy.transcriptionUrl(
                "http://192.168.1.5:8000",
                SidekickSpeechProtocol.OPENAI_COMPATIBLE
            )
        ).isEqualTo("http://192.168.1.5:8000/audio/transcriptions")
    }

    @Test
    fun `openai endpoint pasted in full is not doubled`() {
        assertThat(
            SidekickSpeechEndpointPolicy.transcriptionUrl(
                "https://host/v1/audio/transcriptions",
                SidekickSpeechProtocol.OPENAI_COMPATIBLE
            )
        ).isEqualTo("https://host/v1/audio/transcriptions")
    }

    @Test
    fun `whisper cpp endpoint uses the inference path`() {
        assertThat(
            SidekickSpeechEndpointPolicy.transcriptionUrl(
                "http://192.168.1.5:8080",
                SidekickSpeechProtocol.WHISPER_CPP
            )
        ).isEqualTo("http://192.168.1.5:8080/inference")
        assertThat(
            SidekickSpeechEndpointPolicy.transcriptionUrl(
                "http://192.168.1.5:8080/inference",
                SidekickSpeechProtocol.WHISPER_CPP
            )
        ).isEqualTo("http://192.168.1.5:8080/inference")
    }

    @Test
    fun `whisper language codes are iso 639 1 and auto is omitted`() {
        assertThat(SidekickSpeechEndpointPolicy.whisperLanguage(SidekickVoiceLanguage.AUTO)).isNull()
        assertThat(SidekickSpeechEndpointPolicy.whisperLanguage(SidekickVoiceLanguage.SERBIAN))
            .isEqualTo("sr")
        assertThat(SidekickSpeechEndpointPolicy.whisperLanguage(SidekickVoiceLanguage.ENGLISH))
            .isEqualTo("en")
        assertThat(SidekickSpeechEndpointPolicy.whisperLanguage(SidekickVoiceLanguage.SWEDISH))
            .isEqualTo("sv")
    }

    @Test
    fun `private host detection covers loopback ranges and lan names`() {
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("localhost")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("127.0.0.1")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("10.1.2.3")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("172.16.0.1")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("172.31.255.255")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("172.32.0.1")).isFalse()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("192.168.0.1")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("169.254.1.1")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("::1")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("fd00::1")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("fe80::1")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("whisperbox")).isTrue()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("whisper.example.com")).isFalse()
        assertThat(SidekickSpeechEndpointPolicy.isPrivateHost("8.8.8.8")).isFalse()
    }
}
