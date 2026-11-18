package com.sherwood.wta.core.sidekick.research

/**
 * Turns a link the user pasted into the concrete documents worth fetching.
 *
 * A GitHub link is the case that needs real work. `github.com/owner/repo` is a JavaScript
 * application whose HTML says almost nothing, and `.../blob/main/src/Foo.kt` is a file
 * viewer, not the file. Fetching either one produces a useless page and a model that then
 * invents the repository contents. Both are rewritten:
 *
 * ```
 * github.com/o/r                  → api.github.com/repos/o/r  +  raw README
 * github.com/o/r/blob/ref/path    → raw.githubusercontent.com/o/r/ref/path
 * github.com/o/r/tree/ref/path    → api.github.com/repos/o/r/contents/path?ref=ref
 * ```
 *
 * Every produced URL is still checked by [SidekickWebPolicy] before it is fetched; this
 * object only decides *what* to read, never *whether* it may be read.
 */
object SidekickLinkResolver {

    data class Target(
        val label: String,
        val url: String,
        val maxChars: Int
    )

    /** Character budget for a single fetched document. */
    const val DEFAULT_TARGET_CHARS = 6_000

    /** At most this many URLs are auto-fetched from one question. */
    const val MAX_AUTO_LINKS = 2

    fun targets(rawUrl: String): List<Target> {
        val verdict = SidekickWebPolicy.check(rawUrl)
        val allowed = verdict as? SidekickWebPolicy.Verdict.Allowed ?: return emptyList()
        val url = allowed.url
        val segments = pathSegments(url)
        return when {
            allowed.host == "github.com" -> githubTargets(url, segments)
            allowed.host == "raw.githubusercontent.com" -> listOf(
                Target(label = "raw file", url = url, maxChars = DEFAULT_TARGET_CHARS)
            )
            else -> listOf(Target(label = allowed.host, url = url, maxChars = DEFAULT_TARGET_CHARS))
        }
    }

    private fun githubTargets(url: String, segments: List<String>): List<Target> {
        // segments = [owner, repo, ...rest]
        if (segments.size < 2) {
            return listOf(Target(label = "github.com", url = url, maxChars = DEFAULT_TARGET_CHARS))
        }
        val owner = segments[0]
        val repo = segments[1]
        val rest = segments.drop(2)

        // A repo root: the API object carries description, language, stars and topics, and
        // the README carries what the project actually is.
        if (rest.isEmpty()) {
            return listOf(
                Target(
                    label = "GitHub repository $owner/$repo",
                    url = "https://api.github.com/repos/$owner/$repo",
                    maxChars = 3_000
                ),
                Target(
                    label = "README of $owner/$repo",
                    url = "https://raw.githubusercontent.com/$owner/$repo/HEAD/README.md",
                    maxChars = DEFAULT_TARGET_CHARS
                )
            )
        }

        // /blob/<ref>/<path...> — the viewer's HTML is useless, the raw file is the content.
        if (rest.size >= 3 && rest[0] == "blob") {
            val ref = rest[1]
            val path = rest.drop(2).joinToString("/")
            return listOf(
                Target(
                    label = "$owner/$repo at $path",
                    url = "https://raw.githubusercontent.com/$owner/$repo/$ref/$path",
                    maxChars = DEFAULT_TARGET_CHARS
                )
            )
        }

        // /tree/<ref>[/<path>] — a directory listing, which only the API can give as text.
        if (rest.size >= 2 && rest[0] == "tree") {
            val ref = rest[1]
            val path = rest.drop(2).joinToString("/")
            val suffix = if (path.isEmpty()) "" else "/$path"
            return listOf(
                Target(
                    label = "GitHub directory $owner/$repo/${path.ifEmpty { "root" }}",
                    url = "https://api.github.com/repos/$owner/$repo/contents$suffix?ref=$ref",
                    maxChars = 4_000
                )
            )
        }

        // Issues, pulls, releases, commits, actions: the HTML page is readable enough.
        return listOf(Target(label = allowedHostLabel(owner, repo), url = url, maxChars = DEFAULT_TARGET_CHARS))
    }

    private fun allowedHostLabel(owner: String, repo: String): String = "github.com/$owner/$repo"

    private fun pathSegments(url: String): List<String> {
        val path = runCatching { java.net.URI(url).rawPath }.getOrNull().orEmpty()
        return path.split('/').filter { it.isNotBlank() }
    }
}
