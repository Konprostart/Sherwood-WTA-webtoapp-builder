package com.sherwood.wta.core.sidekick.input

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which transcription engine the Sidekick panel uses.
 *
 * [SYSTEM] is the phone's own `SpeechRecognizer`: zero APK size, partial results while
 * speaking, but language support depends on the device and the audio may leave it.
 * [WHISPER] records a WAV and posts it to a Whisper-compatible server the user runs; it
 * is the only option that guarantees Serbian, English and Swedish, and it can stay on
 * the local network.
 */
enum class SidekickSpeechEngine(val id: String) {
    SYSTEM("system"),
    WHISPER("whisper");

    companion object {
        fun fromId(id: String?): SidekickSpeechEngine =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * The two request shapes Whisper servers speak.
 *
 * [OPENAI_COMPATIBLE] is `POST {base}/audio/transcriptions` (OpenAI, Groq, speaches,
 * faster-whisper-server). [WHISPER_CPP] is `POST {base}/inference`, which is the
 * whisper.cpp example server and is *not* OpenAI-compatible.
 */
enum class SidekickSpeechProtocol(val id: String) {
    OPENAI_COMPATIBLE("openai"),
    WHISPER_CPP("whisper_cpp");

    companion object {
        fun fromId(id: String?): SidekickSpeechProtocol =
            entries.firstOrNull { it.id == id } ?: OPENAI_COMPATIBLE
    }
}

/** Why a server URL was refused. Mapped to user-facing copy by the settings dialog. */
enum class SidekickEndpointError {
    EMPTY,
    MALFORMED,
    UNSUPPORTED_SCHEME,

    /** `http://` to a host that is not loopback or a private LAN address. */
    PUBLIC_CLEARTEXT,

    /** `http://` on the LAN but with an API key attached — the key would travel in clear. */
    CLEARTEXT_WITH_TOKEN,

    /** `user:pass@host` in the URL: the credentials would be replayed on every request. */
    CREDENTIALS_IN_URL,

    /** A query or fragment on a base URL is always a paste mistake. */
    QUERY_OR_FRAGMENT
}

/**
 * A resolved Whisper-compatible transcription server.
 *
 * [baseUrl] is normalized (no trailing slash, no query, no fragment). [token] is never
 * logged, serialized or shown in the UI.
 */
data class SidekickSpeechEndpoint(
    val protocol: SidekickSpeechProtocol,
    val baseUrl: String,
    val model: String,
    val token: String?
) {
    val hasToken: Boolean get() = !token.isNullOrBlank()

    /** The exact URL a transcript is posted to. */
    val transcriptionUrl: String
        get() = SidekickSpeechEndpointPolicy.transcriptionUrl(baseUrl, protocol)
}

/**
 * Validates and normalizes the server URL, and decides which URLs are allowed over
 * cleartext.
 *
 * The rule from the plan: HTTPS is required for anything reachable on the public
 * internet; `http://` is allowed only for loopback and private LAN addresses, and only
 * when no API key is stored — a bearer token over cleartext is a credential leak, and
 * audio is user speech, not just a health check.
 *
 * "Private" is decided from the host literal, never by resolving it: a public hostname
 * that resolves to a private address is still a public name, and resolving here would
 * make validation depend on a network call.
 */
object SidekickSpeechEndpointPolicy {

    /** Cap on the URL text the UI can store; anything longer is a paste accident. */
    const val MAX_URL_LENGTH = 2048

    data class Valid(
        val baseUrl: String,
        val host: String,
        val cleartext: Boolean
    )

    sealed interface Validation {
        data class Ok(val value: Valid) : Validation
        data class Invalid(val reason: SidekickEndpointError) : Validation
    }

    /**
     * Validates and normalizes [rawUrl].
     *
     * @param hasToken whether an API key is stored. A cleartext URL is refused when one
     *   is, because the key travels in the `Authorization` header of the same request.
     */
    fun validate(rawUrl: String, hasToken: Boolean): Validation {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return Validation.Invalid(SidekickEndpointError.EMPTY)
        if (trimmed.length > MAX_URL_LENGTH) {
            return Validation.Invalid(SidekickEndpointError.MALFORMED)
        }
        // Reported separately from "malformed": `ftp://host` parses fine as a URL and the
        // user needs to hear that the scheme is the problem, not the rest of it.
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = trimmed.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") {
                return Validation.Invalid(SidekickEndpointError.UNSUPPORTED_SCHEME)
            }
        }
        // OkHttp only parses http(s) URLs, so this rejects anything without a scheme.
        val url = trimmed.toHttpUrlOrNull()
            ?: return Validation.Invalid(SidekickEndpointError.MALFORMED)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Validation.Invalid(SidekickEndpointError.CREDENTIALS_IN_URL)
        }
        if (url.query != null || url.fragment != null) {
            return Validation.Invalid(SidekickEndpointError.QUERY_OR_FRAGMENT)
        }
        val host = url.host.lowercase()
        val cleartext = url.scheme == "http"
        if (cleartext) {
            if (!isPrivateHost(host)) {
                return Validation.Invalid(SidekickEndpointError.PUBLIC_CLEARTEXT)
            }
            if (hasToken) {
                return Validation.Invalid(SidekickEndpointError.CLEARTEXT_WITH_TOKEN)
            }
        }
        return Validation.Ok(Valid(baseUrl = normalize(url.toString()), host = host, cleartext = cleartext))
    }

    /** Strips default ports, trailing slashes and any empty path `/`. */
    fun normalize(url: String): String {
        val parsed = url.toHttpUrlOrNull()?.toString() ?: return url.trim()
        return parsed.removeSuffix("/")
    }

    /**
     * Appends the protocol's path to a base URL.
     *
     * A base that already ends in the endpoint path is used as typed, so pasting the
     * full URL from a server's README works instead of producing `/inference/inference`.
     * A reverse-proxy prefix (`https://host/whisper`) is preserved.
     */
    fun transcriptionUrl(baseUrl: String, protocol: SidekickSpeechProtocol): String {
        val base = baseUrl.trim().removeSuffix("/")
        val suffix = when (protocol) {
            SidekickSpeechProtocol.OPENAI_COMPATIBLE -> "/audio/transcriptions"
            SidekickSpeechProtocol.WHISPER_CPP -> "/inference"
        }
        return if (base.endsWith(suffix)) base else base + suffix
    }

    /**
     * Whether [host] is loopback or a private/LAN address.
     *
     * Deliberately literal-only: loopback, IPv4 private/benchmark/link-local ranges,
     * IPv6 loopback/ULA/link-local, `.local` mDNS names and single-label hostnames
     * (no public TLD is single-label, so a bare name is a LAN name).
     */
    fun isPrivateHost(host: String): Boolean {
        val name = host.lowercase().trim('[', ']')
        if (name.isEmpty()) return false
        if (name == "localhost" || name.endsWith(".localhost")) return true
        if (name.endsWith(".local")) return true
        // IPv6 literal (contains a colon).
        if (name.contains(':')) {
            if (name == "::1" || name == "0:0:0:0:0:0:0:1") return true
            return name.startsWith("fc") || name.startsWith("fd") ||
                name.startsWith("fe8") || name.startsWith("fe9") ||
                name.startsWith("fea") || name.startsWith("feb")
        }
        val octets = name.split('.')
        if (octets.size == 4 && octets.all { part -> part.toIntOrNull() in 0..255 }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            return a == 127 ||
                a == 10 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254)
        }
        // A dotted name is a DNS name; only single-label names are assumed to be LAN.
        return !name.contains('.')
    }

    /**
     * The ISO-639-1 code Whisper expects, or `null` to let the server auto-detect.
     *
     * These are deliberately not the BCP-47 tags the system recognizer uses: Whisper
     * wants `sr`, not `sr-RS`.
     */
    fun whisperLanguage(language: SidekickVoiceLanguage): String? = when (language) {
        SidekickVoiceLanguage.AUTO -> null
        SidekickVoiceLanguage.SERBIAN -> "sr"
        SidekickVoiceLanguage.ENGLISH -> "en"
        SidekickVoiceLanguage.SWEDISH -> "sv"
    }
}
