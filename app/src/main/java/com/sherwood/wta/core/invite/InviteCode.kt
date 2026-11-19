package com.sherwood.wta.core.invite

import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale

/**
 * Invite codes, in the format the installer page already publishes.
 *
 * The web page (`docs/public/install/install-core.js`) and this object must accept each
 * other's codes, so the three facts that decide acceptance are pinned here rather than
 * re-derived at each call site:
 *
 *  - the `SHW-` prefix and a six-character body;
 *  - the alphabet, which deliberately omits `I`, `L`, `O` and `U`, so a code read aloud or
 *    retyped from a screenshot cannot turn into a different valid-looking code;
 *  - normalisation, which accepts what a person would actually type (`shw abc123`,
 *    `SHW_ABC123`, `abc123`).
 *
 * Everything here is pure: no Android types, no clock, no storage. That keeps the format
 * testable on the JVM and lets the settings screen ask for a link without touching the
 * device.
 */
object InviteCode {

    const val PREFIX: String = "SHW-"

    const val BODY_LENGTH: Int = 6

    /**
     * Crockford-ish base: 22 letters (no I, L, O, U) and the digits 2-9 (no 0 or 1, which
     * are read as O and I). Thirty symbols, so a byte maps to a symbol without bias large
     * enough to matter for a six-character code.
     */
    const val ALPHABET: String = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

    /** The public installer page; the invite link is this URL with `?ref=` appended. */
    const val PUBLIC_URL: String =
        "https://konprostart.github.io/Sherwood-WTA-webtoapp-builder/install/"

    private val PATTERN: Regex =
        Regex("^" + Regex.escape(PREFIX) + "[A-Z0-9]{" + BODY_LENGTH + "}" + '$')

    private val SEPARATORS: Regex = Regex("[\\s_]+")

    /**
     * Brings any human spelling of a code into the canonical form: upper case, one `SHW-`
     * prefix, the rest untouched.
     *
     * The result is not necessarily valid — [isValid] is the gate. Returning a normalised
     * string instead of null keeps the call sites (storage, link building, query parsing)
     * uniform; they all ask [isValid] afterwards.
     */
    fun normalize(raw: String?): String {
        val trimmed = raw?.trim()?.uppercase(Locale.ROOT) ?: return ""
        if (trimmed.isEmpty()) return ""

        // "shw abc123" and "SHW_ABC123" mean the same thing to someone retyping a code.
        val text = SEPARATORS.replace(trimmed, "-")
        if (text.startsWith(PREFIX)) return text
        if (text.startsWith("SHW")) {
            val rest = text.substring(3).removePrefix("-")
            return if (rest.isEmpty()) "" else PREFIX + rest
        }
        return PREFIX + text
    }

    /** True for a canonical, well-formed code; a bare body is accepted after normalisation. */
    fun isValid(raw: String?): Boolean {
        val normalized = normalize(raw)
        return normalized.isNotEmpty() && PATTERN.matches(normalized)
    }

    /**
     * Maps six supplied bytes onto the alphabet.
     *
     * Deterministic on purpose: the generator is the only source of randomness, so tests can
     * pin the exact output and prove the alphabet covers what the page accepts.
     */
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size >= BODY_LENGTH) {
            "an invite code needs at least $BODY_LENGTH bytes, got ${bytes.size}"
        }
        val out = StringBuilder(PREFIX.length + BODY_LENGTH)
        out.append(PREFIX)
        for (index in 0 until BODY_LENGTH) {
            out.append(ALPHABET[(bytes[index].toInt() and 0xFF) % ALPHABET.length])
        }
        return out.toString()
    }

    /** Generates a fresh code. The default [SecureRandom] is seeded by the platform. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(BODY_LENGTH)
        random.nextBytes(bytes)
        return fromBytes(bytes)
    }

    /**
     * The shareable installer link for [code].
     *
     * An invalid code returns [baseUrl] unchanged: a link that silently dropped the `ref`
     * would look correct while attributing nothing, which is worse than an obviously plain
     * URL. A query already present in [baseUrl] is preserved, so a campaign parameter and
     * the referral code can coexist.
     */
    fun link(code: String, baseUrl: String = PUBLIC_URL): String {
        val normalized = normalize(code)
        val base = baseUrl.trim()
        if (!isValid(normalized)) return base

        // A fragment is client-side and must not swallow the query we append.
        val withoutFragment = base.substringBefore('#')
        if (withoutFragment.isEmpty()) return withoutFragment

        val separator = when {
            withoutFragment.endsWith("?") || withoutFragment.endsWith("&") -> ""
            '?' in withoutFragment -> "&"
            else -> "?"
        }
        return withoutFragment + separator + "ref=" + normalized
    }

    /**
     * The referral code carried by a `?ref=` query, or null when there is none or it does
     * not parse.
     *
     * Accepts the raw query, with or without the leading `?`, because that is what a deep
     * link and a manually pasted string respectively provide. The value is percent-decoded
     * before normalisation so `SHW-ABC%31%32%33` is read as `SHW-ABC123`.
     */
    fun refFromQuery(query: String?): String? {
        val raw = query?.trim()?.removePrefix("?") ?: return null
        if (raw.isEmpty()) return null

        for (pair in raw.split('&')) {
            if (pair.substringBefore('=', "") != "ref") continue
            val value = pair.substringAfter('=', "")
            if (value.isEmpty()) return null
            val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            val normalized = normalize(decoded)
            return if (isValid(normalized)) normalized else null
        }
        return null
    }
}
