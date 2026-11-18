package com.sherwood.wta.core.sidekick.research

/**
 * Turns an HTML page into the text a model can actually read.
 *
 * There is no HTML parser in the platform and adding one is a new dependency for the sake
 * of a few hundred lines of stripping, so this is a deliberately small tag stripper. It is
 * not a sanitiser and must never be used to render HTML — the output goes to a prompt.
 *
 * The parts that matter for readability: drop `<script>`/`<style>`/`<noscript>`/`<svg>`
 * wholesale (their contents are not prose), keep line structure for block elements, decode
 * the handful of entities that actually appear, and collapse the whitespace that remains.
 */
object SidekickPageReader {

    /** Content types worth reading as text. Anything else is refused rather than mojibaked. */
    fun isReadable(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return type.startsWith("text/") ||
            type == "application/json" ||
            type == "application/xml" ||
            type == "application/xhtml+xml" ||
            type == "application/javascript"
    }

    fun isHtml(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == "text/html" || type == "application/xhtml+xml"
    }

    /** The document title, decoded and clipped; `null` when the page has none. */
    fun title(html: String): String? {
        val match = TITLE.find(html) ?: return null
        val text = decodeEntities(stripTags(match.groupValues[1]))
            .replace(WHITESPACE, " ")
            .trim()
        return text.takeIf { it.isNotEmpty() }?.let { clip(it, MAX_TITLE_CHARS) }
    }

    /**
     * Readable body text.
     *
     * [cap] is a character budget, not a byte budget: the model is billed in tokens and the
     * caller has already bounded the download.
     */
    fun toText(html: String, cap: Int = MAX_TEXT_CHARS): String {
        var body = html
        for (pattern in DROP_BLOCKS) {
            body = pattern.replace(body, " ")
        }
        // Block-level boundaries become newlines so paragraphs do not run together.
        body = BLOCK_BREAK.replace(body, "\n")
        body = stripTags(body)
        body = decodeEntities(body)
        body = body.replace(INLINE_SPACE, " ")
        body = body.replace(BLANK_LINES, "\n\n")
        body = body.replace(HORIZONTAL_SPACE, " ")
        val trimmed = body.trim()
        return if (trimmed.length <= cap) trimmed else clip(trimmed, cap) + TRUNCATED
    }

    /** Plain text body (JSON, logs, markdown) with just the byte-level noise removed. */
    fun toPlainText(text: String, cap: Int = MAX_TEXT_CHARS): String {
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        return if (cleaned.length <= cap) cleaned else clip(cleaned, cap) + TRUNCATED
    }

    fun stripTags(input: String): String = TAGS.replace(input, " ")

    /**
     * The entity set that appears on real pages. Numeric forms are handled generically;
     * unknown named entities are left alone rather than dropped, so "&euro;" is at worst
     * visible instead of silently deleting a character.
     */
    fun decodeEntities(input: String): String {
        if (!input.contains('&')) return input
        val numeric = NUMERIC_ENTITY.replace(input) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
                ?: match.groupValues[2].toIntOrNull(10)
            if (code == null || code <= 0 || code > Character.MAX_CODE_POINT) {
                match.value
            } else {
                String(Character.toChars(code))
            }
        }
        var result = numeric
        for ((entity, replacement) in NAMED_ENTITIES) {
            result = result.replace(entity, replacement)
        }
        return result
    }

    /** `take` that never splits a surrogate pair, so clipping cannot break UTF-8 encoding. */
    internal fun clip(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        var end = max
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    const val MAX_TEXT_CHARS = 8_000
    private const val MAX_TITLE_CHARS = 180
    private const val TRUNCATED = "\n…[truncated]"

    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAGS = Regex("""<[^>]*>""")
    private val BLOCK_BREAK = Regex(
        """</?(?:p|div|section|article|header|footer|main|aside|nav|li|ul|ol|dl|dt|dd|h[1-6]|tr|td|th|table|blockquote|pre|figure|figcaption|form|fieldset|hr|br)\b[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val DROP_BLOCKS = listOf(
        Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<noscript[^>]*>.*?</noscript>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<svg[^>]*>.*?</svg>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<template[^>]*>.*?</template>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL),
        // A document head carries metas and link tags; the title was already read above.
        Regex("""<head[^>]*>.*?</head>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    )
    private val NUMERIC_ENTITY = Regex("""&#(?:[xX]([0-9a-fA-F]+)|(\d+));""")
    private val WHITESPACE = Regex("""\s+""")
    // `\v` cannot be used here: in Java's regex it is the vertical-*whitespace* class and
    // it matches newlines, which collapsed every page onto one line.
    private val INLINE_SPACE = Regex("""[ \t\f\x0B]+""")
    private val HORIZONTAL_SPACE = Regex("""[ \t\x0B]+""")
    private val BLANK_LINES = Regex("""\n{3,}""")

    private val NAMED_ENTITIES = listOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&apos;" to "'",
        "&#39;" to "'",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…",
        "&rsquo;" to "’",
        "&lsquo;" to "‘",
        "&ldquo;" to "“",
        "&rdquo;" to "”",
        "&copy;" to "©",
        "&reg;" to "®",
        "&trade;" to "™",
        "&euro;" to "€",
        "&pound;" to "£",
        "&deg;" to "°",
        "&middot;" to "·",
        "&bull;" to "•",
        "&times;" to "×"
    )
}
