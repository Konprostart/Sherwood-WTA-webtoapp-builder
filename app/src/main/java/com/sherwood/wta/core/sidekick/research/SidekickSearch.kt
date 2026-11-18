package com.sherwood.wta.core.sidekick.research

import com.google.gson.JsonParser
import com.sherwood.wta.core.logging.AppLogger
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/** One search result, already cleaned for a prompt. */
data class SidekickSearchHit(
    val title: String,
    val url: String,
    val snippet: String
)

sealed interface SidekickSearchOutcome {
    data class Ok(val hits: List<SidekickSearchHit>) : SidekickSearchOutcome
    data class Failed(val reason: Reason) : SidekickSearchOutcome

    enum class Reason {
        /** No backend answered usefully (blocked, captcha, HTML changed, empty result set). */
        UNAVAILABLE,
        /** The network call itself failed. */
        NETWORK,
        /** The configured server answered but not with a result list. */
        BAD_ENDPOINT
    }
}

/**
 * Keyless web search for the Sidekick.
 *
 * Two backends, chosen by configuration:
 *
 * - **Default: DuckDuckGo HTML.** No account, no key, no quota to configure — which is the
 *   only thing that works out of the box on a phone. Its HTML result page is parsed, and
 *   DuckDuckGo's own `/l/?uddg=` redirect links are unwrapped so the model sees real URLs.
 * - **Optional: a SearXNG instance** (`{base}/search?q=…&format=json`). Users who already
 *   self-host one get a stable JSON API and complete control over who sees their queries.
 *
 * The parser is a pure function so the shape of a real result page is pinned by a test
 * instead of being verified by hoping.
 */
class SidekickSearch(
    private val http: SidekickHttp,
    /** Base URL of a SearXNG instance, or `null` to use DuckDuckGo. */
    private val searxEndpoint: String? = null
) {

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): SidekickSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SidekickSearchOutcome.Ok(emptyList())
        return if (searxEndpoint.isNullOrBlank()) {
            searchDuckDuckGo(trimmed, limit)
        } else {
            searchSearx(trimmed, limit)
        }
    }

    private suspend fun searchDuckDuckGo(
        query: String,
        limit: Int
    ): SidekickSearchOutcome {
        val url = "$DUCKDUCKGO_HTML?q=${encode(query)}"
        val response = try {
            http.get(url, accept = "text/html")
        } catch (e: IOException) {
            AppLogger.w(TAG, "Search request failed: ${e.javaClass.simpleName}")
            return SidekickSearchOutcome.Failed(SidekickSearchOutcome.Reason.NETWORK)
        }
        if (!response.isSuccessful) {
            AppLogger.w(TAG, "Search rejected with HTTP ${response.code}")
            return SidekickSearchOutcome.Failed(
                if (response.code == 0) SidekickSearchOutcome.Reason.NETWORK
                else SidekickSearchOutcome.Reason.UNAVAILABLE
            )
        }
        val hits = parseDuckDuckGo(response.body, limit)
        return if (hits.isEmpty()) {
            SidekickSearchOutcome.Failed(SidekickSearchOutcome.Reason.UNAVAILABLE)
        } else {
            SidekickSearchOutcome.Ok(hits)
        }
    }

    private suspend fun searchSearx(
        query: String,
        limit: Int
    ): SidekickSearchOutcome {
        val base = searxEndpoint!!.trim().trimEnd('/')
        val response = try {
            http.get("$base/search?q=${encode(query)}&format=json", accept = "application/json")
        } catch (e: IOException) {
            AppLogger.w(TAG, "Search request failed: ${e.javaClass.simpleName}")
            return SidekickSearchOutcome.Failed(SidekickSearchOutcome.Reason.NETWORK)
        }
        if (!response.isSuccessful) {
            return SidekickSearchOutcome.Failed(SidekickSearchOutcome.Reason.BAD_ENDPOINT)
        }
        val hits = parseSearx(response.body, limit)
        return if (hits.isEmpty()) {
            SidekickSearchOutcome.Failed(SidekickSearchOutcome.Reason.BAD_ENDPOINT)
        } else {
            SidekickSearchOutcome.Ok(hits)
        }
    }

    companion object {
        private const val TAG = "SidekickSearch"
        private const val DUCKDUCKGO_HTML = "https://html.duckduckgo.com/html/"
        const val DEFAULT_LIMIT = 6
        const val MAX_LIMIT = 10
        private const val MAX_SNIPPET_CHARS = 320
        private const val MAX_TITLE_CHARS = 160

        private val ANCHOR = Regex(
            """<a\b([^>]*\bresult__a\b[^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val SNIPPET = Regex(
            """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|td|span)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val MULTI_SPACE = Regex("""\s+""")

        /** Extracts results from a DuckDuckGo HTML page. Pure; see the unit test. */
        internal fun parseDuckDuckGo(html: String, limit: Int): List<SidekickSearchHit> {
            val anchors = ANCHOR.findAll(html).toList()
            if (anchors.isEmpty()) return emptyList()
            val hits = ArrayList<SidekickSearchHit>(minOf(limit, anchors.size))
            for ((index, anchor) in anchors.withIndex()) {
                if (hits.size >= limit) break
                val href = HREF.find(anchor.groupValues[1])?.groupValues?.getOrNull(1)
                    ?: continue
                val url = unwrapDuckDuckGo(href) ?: continue
                // Only public HTTPS results: the model is told these are fetchable.
                if (SidekickWebPolicy.check(url) !is SidekickWebPolicy.Verdict.Allowed) continue

                val title = clean(anchor.groupValues[2], MAX_TITLE_CHARS)
                if (title.isEmpty()) continue

                val segmentEnd = anchors.getOrNull(index + 1)?.range?.first ?: html.length
                val segment = html.substring(
                    anchor.range.last + 1,
                    segmentEnd.coerceAtLeast(anchor.range.last + 1)
                )
                val snippet = SNIPPET.find(segment)
                    ?.let { clean(it.groupValues[1], MAX_SNIPPET_CHARS) }
                    .orEmpty()
                hits += SidekickSearchHit(title = title, url = url, snippet = snippet)
            }
            return hits
        }

        /** Extracts results from a SearXNG `format=json` payload. Pure. */
        internal fun parseSearx(json: String, limit: Int): List<SidekickSearchHit> {
            val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                ?: return emptyList()
            val results = root.getAsJsonArray("results") ?: return emptyList()
            val hits = ArrayList<SidekickSearchHit>(minOf(limit, results.size()))
            for (element in results) {
                if (hits.size >= limit) break
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                if (SidekickWebPolicy.check(url) !is SidekickWebPolicy.Verdict.Allowed) continue
                val title = clean(obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(), MAX_TITLE_CHARS)
                if (title.isEmpty()) continue
                val snippet = clean(
                    obj.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    MAX_SNIPPET_CHARS
                )
                hits += SidekickSearchHit(title = title, url = url, snippet = snippet)
            }
            return hits
        }

        /**
         * DuckDuckGo wraps every result link as `/l/?uddg=<encoded>`. The encoded target is
         * the only thing worth returning; keeping the wrapper would make the Sidekick fetch
         * DuckDuckGo's redirect instead of the page.
         */
        internal fun unwrapDuckDuckGo(href: String): String? {
            val absolute = when {
                href.startsWith("//") -> "https:$href"
                href.startsWith("/") -> "https://duckduckgo.com$href"
                else -> href
            }
            val uri = runCatching { java.net.URI(absolute) }.getOrNull() ?: return null
            val host = uri.host?.lowercase().orEmpty()
            if (!host.endsWith("duckduckgo.com")) return absolute
            val query = uri.rawQuery ?: return null
            val target = query.split('&')
                .firstOrNull { it.startsWith("uddg=") }
                ?.substringAfter('=')
                ?: return null
            return runCatching { URLDecoder.decode(target, "UTF-8") }.getOrNull()
        }

        private fun clean(html: String, max: Int): String {
            val text = SidekickPageReader.decodeEntities(SidekickPageReader.stripTags(html))
                .replace(MULTI_SPACE, " ")
                .trim()
            return SidekickPageReader.clip(text, max)
        }

        private fun encode(query: String): String =
            URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    }
}
