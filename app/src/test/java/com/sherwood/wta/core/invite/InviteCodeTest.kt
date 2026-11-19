package com.sherwood.wta.core.invite

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The invite code contract, tested without Android.
 *
 * The code is the only thing that will link a friend to the person who invited them once a
 * reward backend exists, so the two failure modes that would silently break attribution are
 * pinned here:
 *
 *  - a generated code must be accepted by the installer page, which means the same prefix,
 *    length and alphabet;
 *  - a code retyped by a person ("shw abc123") must normalise to the same value, or a friend
 *    who mistypes a separator would be told the code is wrong.
 */
class InviteCodeTest {

    @Test
    fun `the published format is the one the page links to`() {
        assertThat(InviteCode.PREFIX).isEqualTo("SHW-")
        assertThat(InviteCode.BODY_LENGTH).isEqualTo(6)
        assertThat(InviteCode.PUBLIC_URL)
            .isEqualTo("https://konprostart.github.io/Sherwood-WTA-webtoapp-builder/install/")
        assertThat(InviteCode.isValid("SHW-ABC123")).isTrue()
    }

    @Test
    fun `the alphabet omits characters that read as another character`() {
        // I/L/O/U and 0/1 are the pairs that turn a code read aloud into a different code.
        listOf("I", "L", "O", "U", "0", "1").forEach { ambiguous ->
            assertThat(InviteCode.ALPHABET).doesNotContain(ambiguous)
        }
        // Thirty distinct symbols: every index sampled by a byte maps to exactly one symbol.
        assertThat(InviteCode.ALPHABET.toSet()).hasSize(InviteCode.ALPHABET.length)
    }

    @Test
    fun `normalisation accepts what a person would retype`() {
        listOf(
            "SHW-ABC123",
            "shw-abc123",
            "shw abc123",
            "SHW_ABC123",
            "  shw-abc123  ",
            // The bare body is what someone types when the prefix was cut off by a screenshot.
            "abc123"
        ).forEach { typed ->
            assertThat(InviteCode.normalize(typed)).isEqualTo("SHW-ABC123")
            assertThat(InviteCode.isValid(typed)).isTrue()
        }
    }

    @Test
    fun `a bare prefix normalises to nothing rather than a fake code`() {
        assertThat(InviteCode.normalize("SHW")).isEmpty()
        assertThat(InviteCode.normalize("SHW-")).isEqualTo("SHW-")
        assertThat(InviteCode.isValid("SHW-")).isFalse()
    }

    @Test
    fun `malformed codes are rejected`() {
        listOf(
            null,
            "",
            "   ",
            "SHW-ABC12",
            "SHW-ABC1234",
            "ABC-123456",
            "SHW-ABC12!",
            "SHW-ABC 123"
        ).forEach { candidate ->
            assertThat(InviteCode.isValid(candidate)).isFalse()
        }
    }

    @Test
    fun `bytes map onto the alphabet in order`() {
        // Pinned to the page's mapping (`byte % alphabet.length`), so a generated code is
        // accepted there byte for byte.
        assertThat(InviteCode.fromBytes(ByteArray(6)))
            .isEqualTo("SHW-AAAAAA")
        assertThat(InviteCode.fromBytes(ByteArray(6) { it.toByte() }))
            .isEqualTo("SHW-ABCDEF")
        assertThat(InviteCode.fromBytes(ByteArray(6) { 0xFF.toByte() }))
            .isEqualTo("SHW-SSSSSS")
    }

    @Test
    fun `a short byte array is refused instead of padded`() {
        val error = runCatching { InviteCode.fromBytes(ByteArray(5)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `generated codes are always canonical`() {
        repeat(200) {
            val code = InviteCode.generate()
            assertThat(InviteCode.isValid(code)).isTrue()
            assertThat(code).hasLength(InviteCode.PREFIX.length + InviteCode.BODY_LENGTH)
            code.removePrefix(InviteCode.PREFIX).forEach { character ->
                assertThat(InviteCode.ALPHABET).contains(character.toString())
            }
        }
    }

    @Test
    fun `the invite link appends the code as ref`() {
        assertThat(InviteCode.link("SHW-ABC123"))
            .isEqualTo("https://konprostart.github.io/Sherwood-WTA-webtoapp-builder/install/?ref=SHW-ABC123")
        // A code typed in a lower-case body still produces a canonical link.
        assertThat(InviteCode.link("abc123"))
            .isEqualTo("https://konprostart.github.io/Sherwood-WTA-webtoapp-builder/install/?ref=SHW-ABC123")
    }

    @Test
    fun `the link keeps a query that is already there`() {
        assertThat(InviteCode.link("SHW-ABC123", "https://example.test/install?a=1&b=2"))
            .isEqualTo("https://example.test/install?a=1&b=2&ref=SHW-ABC123")
        assertThat(InviteCode.link("SHW-ABC123", "https://example.test/install?utm=share"))
            .isEqualTo("https://example.test/install?utm=share&ref=SHW-ABC123")
    }

    @Test
    fun `a fragment does not swallow the ref`() {
        assertThat(InviteCode.link("SHW-ABC123", "https://example.test/install#top"))
            .isEqualTo("https://example.test/install?ref=SHW-ABC123")
    }

    @Test
    fun `an invalid code yields the plain url rather than a link that attributes nothing`() {
        assertThat(InviteCode.link("nope")).isEqualTo(InviteCode.PUBLIC_URL)
        assertThat(InviteCode.link("")).isEqualTo(InviteCode.PUBLIC_URL)
    }

    @Test
    fun `the ref is read back from a query`() {
        assertThat(InviteCode.refFromQuery("?ref=SHW-ABC123")).isEqualTo("SHW-ABC123")
        assertThat(InviteCode.refFromQuery("ref=abc123")).isEqualTo("SHW-ABC123")
        assertThat(InviteCode.refFromQuery("?a=1&ref=SHW-ABC123&b=2")).isEqualTo("SHW-ABC123")
        // Percent-encoded, because a link pasted from a chat client arrives that way.
        assertThat(InviteCode.refFromQuery("?ref=SHW-ABC%31%32%33")).isEqualTo("SHW-ABC123")
    }

    @Test
    fun `a query without a usable ref reads as absent`() {
        assertThat(InviteCode.refFromQuery(null)).isNull()
        assertThat(InviteCode.refFromQuery("")).isNull()
        assertThat(InviteCode.refFromQuery("?foo=1")).isNull()
        assertThat(InviteCode.refFromQuery("?ref=")).isNull()
        assertThat(InviteCode.refFromQuery("?ref=nope")).isNull()
        assertThat(InviteCode.refFromQuery("?ref=SHW-ABC12")).isNull()
    }

    @Test
    fun `a generated code survives the link and back`() {
        val code = InviteCode.generate()
        val link = InviteCode.link(code)
        assertThat(link).startsWith(InviteCode.PUBLIC_URL + "?ref=")
        assertThat(InviteCode.refFromQuery(link.substringAfter('?'))).isEqualTo(code)
    }
}
