package com.sherwood.wta.core.sidekick.input

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests for the Whisper upload: which path each protocol uses, what is in the
 * multipart body, that the key goes into a header, and that failures map to the reason the
 * panel shows. A redirect must not be followed — following one would replay the key and
 * the recording at a host the user never configured.
 */
class SidekickWhisperTranscriberTest {

    private lateinit var server: MockWebServer
    private lateinit var audio: File
    private val transcriber by lazy { SidekickWhisperTranscriber() }

    private fun endpoint(
        protocol: SidekickSpeechProtocol = SidekickSpeechProtocol.OPENAI_COMPATIBLE,
        token: String? = null,
        model: String = "whisper-1"
    ) = SidekickSpeechEndpoint(
        protocol = protocol,
        baseUrl = server.url("/v1").toString().removeSuffix("/"),
        model = model,
        token = token
    )

    private fun whisperCppEndpoint(token: String? = null) = SidekickSpeechEndpoint(
        protocol = SidekickSpeechProtocol.WHISPER_CPP,
        baseUrl = server.url("/").toString().removeSuffix("/"),
        model = "",
        token = token
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        audio = File.createTempFile("sidekick-test", ".wav").apply {
            writeBytes(WavFormat.headerBytes(8) + ByteArray(8))
        }
    }

    @After
    fun tearDown() {
        runCatching { audio.delete() }
        runCatching { server.shutdown() }
    }

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .addHeader("Content-Type", "application/json")
            .setBody(body)

    /** The failure kind a result carries; asserts that it is a failure at all. */
    private fun failureOf(result: Result<*>): SidekickSpeechFailure {
        val error = result.exceptionOrNull()
        assertThat(error).isNotNull()
        return error!!.toSpeechFailure()
    }

    @Test
    fun `openai protocol posts multipart with model language and bearer key`() = runBlocking {
        server.enqueue(jsonResponse("""{"text":"Zdravo svete"}"""))

        val result = transcriber.transcribe(
            endpoint(token = "secret-key"),
            audio,
            SidekickVoiceLanguage.SERBIAN
        )

        assertThat(result.getOrNull()).isEqualTo("Zdravo svete")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/audio/transcriptions")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer secret-key")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("""name="file"; filename="audio.wav"""")
        assertThat(body).contains("audio/wav")
        assertThat(body).contains("""name="model"""")
        assertThat(body).contains("whisper-1")
        assertThat(body).contains("""name="language"""")
        assertThat(body).contains("sr")
        assertThat(body).contains("""name="response_format"""")
    }

    @Test
    fun `auto language is omitted so the server detects it`() = runBlocking {
        server.enqueue(jsonResponse("""{"text":"hello"}"""))

        transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).doesNotContain("""name="language"""")
    }

    @Test
    fun `whisper cpp uses the inference path and sends no model`() = runBlocking {
        server.enqueue(jsonResponse("""{"text":"Hej"}"""))

        val result = transcriber.transcribe(
            whisperCppEndpoint(),
            audio,
            SidekickVoiceLanguage.SWEDISH
        )

        assertThat(result.getOrNull()).isEqualTo("Hej")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/inference")
        val body = recorded.body.readUtf8()
        assertThat(body).doesNotContain("""name="model"""")
        assertThat(body).contains("""name="language"""")
        assertThat(body).contains("sv")
    }

    @Test
    fun `no authorization header is sent without a key`() = runBlocking {
        server.enqueue(jsonResponse("""{"text":"ok"}"""))

        transcriber.transcribe(endpoint(token = null), audio, SidekickVoiceLanguage.ENGLISH)

        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `plain text response is accepted`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "text/plain").setBody("  Zdravo  "))

        val result = transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO)

        assertThat(result.getOrNull()).isEqualTo("Zdravo")
    }

    @Test
    fun `empty transcript is reported as no match`() = runBlocking {
        server.enqueue(jsonResponse("""{"text":"   "}"""))

        val result = transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO)

        assertThat(failureOf(result)).isEqualTo(SidekickSpeechFailure.NO_MATCH)
    }

    @Test
    fun `json without a text field is an endpoint mismatch`() = runBlocking {
        server.enqueue(jsonResponse("""{"error":"unknown model"}"""))

        val result = transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO)

        assertThat(failureOf(result)).isEqualTo(SidekickSpeechFailure.ENDPOINT)
    }

    @Test
    fun `401 and 403 map to auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(
            failureOf(transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO))
        ).isEqualTo(SidekickSpeechFailure.AUTH)

        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(
            failureOf(transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO))
        ).isEqualTo(SidekickSpeechFailure.AUTH)
    }

    @Test
    fun `404 and 422 map to an endpoint problem`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(
            failureOf(transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO))
        ).isEqualTo(SidekickSpeechFailure.ENDPOINT)

        server.enqueue(MockResponse().setResponseCode(422))
        assertThat(
            failureOf(transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO))
        ).isEqualTo(SidekickSpeechFailure.ENDPOINT)
    }

    @Test
    fun `429 maps to throttle and 5xx to network`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(
            failureOf(transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO))
        ).isEqualTo(SidekickSpeechFailure.THROTTLED)

        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(
            failureOf(transcriber.transcribe(endpoint(), audio, SidekickVoiceLanguage.AUTO))
        ).isEqualTo(SidekickSpeechFailure.NETWORK)
    }

    @Test
    fun `a redirect is not followed`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .addHeader("Location", server.url("/elsewhere").toString())
        )

        val result = transcriber.transcribe(endpoint(token = "secret"), audio, SidekickVoiceLanguage.AUTO)

        assertThat(failureOf(result)).isEqualTo(SidekickSpeechFailure.ENDPOINT)
        // Exactly one request: the key never reaches the redirect target.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a missing or empty take is refused before any request`() = runBlocking {
        val empty = File.createTempFile("sidekick-empty", ".wav").apply {
            writeBytes(WavFormat.headerBytes(0))
        }
        try {
            val result = transcriber.transcribe(endpoint(), empty, SidekickVoiceLanguage.AUTO)
            assertThat(failureOf(result)).isEqualTo(SidekickSpeechFailure.AUDIO)
            assertThat(server.requestCount).isEqualTo(0)
        } finally {
            empty.delete()
        }
    }

    @Test
    fun `probe posts silence to the real endpoint and accepts an empty transcript`() = runBlocking {
        server.enqueue(jsonResponse("""{"text":""}"""))

        val result = transcriber.probe(endpoint(token = "secret"))

        assertThat(result.isSuccess).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/audio/transcriptions")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer secret")
    }

    @Test
    fun `probe reports auth failures instead of claiming success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = transcriber.probe(endpoint(token = "wrong"))

        assertThat(failureOf(result)).isEqualTo(SidekickSpeechFailure.AUTH)
    }
}
