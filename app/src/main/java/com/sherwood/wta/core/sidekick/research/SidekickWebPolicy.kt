package com.sherwood.wta.core.sidekick.research

import java.net.URI
import java.util.Locale

/**
 * Which URLs the Sidekick is allowed to fetch itself.
 *
 * The assistant runs on a phone that is often on the same network as the user's own
 * machines: a router admin page, a NAS, a dev server on `localhost`. A model that can be
 * talked into fetching an arbitrary URL — including one it invents — must not be able to
 * turn "research this link" into a probe of that network. So the rule is public HTTPS only:
 *
 * - `https` only. A plaintext fetch would send the question-derived URL and the request
 *   itself in the clear, and there is no legitimate reason for an `http://` public page.
 * - No credentials in the URL (`user:pass@host`), no anchors.
 * - No loopback, link-local, private-range or single-label hosts. Detection is on the
 *   literal host, never on DNS: a public name that resolves to `10.0.0.1` is still a public
 *   name, and resolving first would make this policy race against DNS rebinding.
 *
 * The policy is deliberately pure (no Android, no network) so the boundary is unit tested
 * rather than assumed.
 */
object SidekickWebPolicy {

    /** Longest URL accepted; anything past this is a data/blob URL, not a document. */
    const val MAX_URL_LENGTH = 2_048

    sealed interface Verdict {
        data class Allowed(val url: String, val host: String) : Verdict
        data class Denied(val reason: Reason) : Verdict
    }

    enum class Reason {
        MALFORMED,
        UNSUPPORTED_SCHEME,
        INSECURE,
        CREDENTIALS_IN_URL,
        PRIVATE_HOST,
        TOO_LONG
    }

    /** Checks [raw] and returns the canonical form to fetch (fragment removed). */
    fun check(raw: String): Verdict {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Verdict.Denied(Reason.MALFORMED)
        if (trimmed.length > MAX_URL_LENGTH) return Verdict.Denied(Reason.TOO_LONG)

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Verdict.Denied(Reason.MALFORMED)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return Verdict.Denied(Reason.UNSUPPORTED_SCHEME)
        if (scheme != "https") {
            return Verdict.Denied(
                if (scheme == "http") Reason.INSECURE else Reason.UNSUPPORTED_SCHEME
            )
        }
        if (!uri.userInfo.isNullOrEmpty()) return Verdict.Denied(Reason.CREDENTIALS_IN_URL)

        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?: return Verdict.Denied(Reason.MALFORMED)
        if (host.isEmpty()) return Verdict.Denied(Reason.MALFORMED)
        if (isPrivateHost(host)) return Verdict.Denied(Reason.PRIVATE_HOST)

        // Rebuild without the fragment: an anchor is a client-side position, not part of
        // the document identity, and leaving it in makes two identical fetches look distinct.
        val normalized = buildString {
            append("https://")
            append(host)
            val port = uri.port
            if (port != -1 && port != 443) append(':').append(port)
            append(uri.rawPath.orEmpty().ifEmpty { "/" })
            uri.rawQuery?.let { append('?').append(it) }
        }
        return Verdict.Allowed(normalized, host)
    }

    /**
     * True for hosts that must never be fetched: loopback, link-local, RFC 1918, carrier
     * NAT, IPv6 unique-local, `.local`/`.internal` mDNS names and any single-label name.
     */
    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.lowercase(Locale.ROOT).trimEnd('.').removeSurrounding("[", "]")
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (PRIVATE_SUFFIXES.any { host.endsWith(it) }) return true
        // A single-label host is a LAN name by definition ("nas", "router"); public names
        // always carry a dot. This also blocks the decimal-IP encodings used to sneak past
        // naive range checks (`https://2130706433/`).
        if (!host.contains('.') && !host.contains(':')) return true
        if (host.contains(':')) return isPrivateIpv6(host)
        return isPrivateIpv4(host)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        // Only inspect things that really are dotted-quad literals; a hostname that merely
        // starts with digits (`3com.example.com`) must stay public.
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            part.toIntOrNull(10) ?: return false
        }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 0 || a == 10 || a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val address = host.substringBefore('%')
        return address == "::1" || address == "::" ||
            address.startsWith("fc") || address.startsWith("fd") ||
            address.startsWith("fe80") || address.startsWith("ff")
    }

    private val PRIVATE_SUFFIXES = listOf(
        ".local", ".internal", ".lan", ".home.arpa", ".intranet", ".corp"
    )
}
