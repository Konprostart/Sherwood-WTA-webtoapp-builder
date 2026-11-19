package com.sherwood.wta.core.update

import com.sherwood.wta.core.i18n.AppLanguage
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.EndpointConfig
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 10000

    /**
     * Hard cap on one raced JSON fetch: the first valid response wins long before
     * this, the cap only bounds the "every route hangs" case.
     */
    private const val RACE_TIMEOUT_MS = 12000L

    /**
     * Losing race requests are launched here rather than in the caller's scope,
     * so the winner returns immediately instead of waiting for every candidate
     * to finish or time out.
     */
    private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Invisible boundary that separates the English and Chinese halves of a GitHub release body
     * (English first, Chinese after the marker). It renders as nothing on the releases page, so
     * readers see English then Chinese; the app picks the half matching the current language.
     * Releases without the marker (older notes, or a future release nobody localized yet) fall
     * back to the whole body, which is English — never blank.
     */
    private const val ZH_MARKER = "<!-- zh-CN -->"

    /**
     * Returns the release body in the current app language: the Chinese half when the app is set
     * to Chinese and a Chinese half exists, otherwise the English half (or the whole body when the
     * marker is absent).
     */
    private fun localizeReleaseBody(body: String): String {
        val raw = body.trim()
        val idx = raw.indexOf(ZH_MARKER)
        if (idx < 0) return raw
        val english = raw.substring(0, idx).trim()
        val chinese = raw.substring(idx + ZH_MARKER.length).trim()
        return if (Strings.currentLanguage.value == AppLanguage.CHINESE && chinese.isNotBlank()) {
            chinese
        } else {
            english
        }
    }

    /**
     * Distribution flavor of the running app. The two flavors have different application
     * ids (`com.sherwood.wta` vs `com.sherwood.wta.play`) and both are published as release
     * assets, so a check must only ever offer the APK that can actually replace the installed
     * package. Offering the other family would hand the user an "app not installed" or a
     * silent second copy of the app instead of an update.
     */
    enum class Variant {
        STANDARD,
        GPLAY;

        companion object {
            /** The flavor this process was built for (see the `distribution` product flavors). */
            fun current(): Variant =
                if (com.sherwood.wta.BuildConfig.GPLAY) GPLAY else STANDARD

            /** Family of an asset: names carrying `-gplay` are the Play flavor. */
            fun ofAsset(assetName: String): Variant =
                if (assetName.contains("-gplay", ignoreCase = true)) GPLAY else STANDARD
        }
    }

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun toString(): String = "$major.$minor.$patch"

        override fun compareTo(other: Version): Int {
            if (major != other.major) return major - other.major
            if (minor != other.minor) return minor - other.minor
            return patch - other.patch
        }

        companion object {
            fun parse(raw: String): Version? {
                val core = raw.trim().removePrefix("v").removePrefix("V")
                    .substringBefore('-').substringBefore('+')
                val parts = core.split('.')
                val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
                return Version(major, minor, patch)
            }
        }
    }

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val sha256: String?,
        val releaseNotes: String
    )

    /**
     * A single historical release as needed by the version history UI. Each entry keeps
     * enough info to render, show its changelog, and offer a download without re-fetching.
     *
     * [downloadUrl] is the already-mirrored browser_download_url; null when the release has
     * no APK asset (e.g. source-only / draft).
     */
    data class ReleaseSummary(
        val tag: String,
        val version: String,
        val name: String,
        val publishedAt: String,
        val body: String,
        val downloadUrl: String?,
        val sizeBytes: Long,
        val sha256: String?
    )

    sealed class Result {
        data class UpdateAvailable(val info: ReleaseInfo, val currentVersion: String) : Result()
        data class UpToDate(val version: String) : Result()
        data class Failed(val message: String, val throwable: Throwable? = null) : Result()
    }

    suspend fun check(
        currentVersionName: String,
        endpoints: EndpointConfig = EndpointConfig.defaults(),
        variant: Variant = Variant.current()
    ): Result = withContext(Dispatchers.IO) {
        val current = Version.parse(currentVersionName)
            ?: return@withContext Result.Failed("Cannot parse current version: $currentVersionName")

        try {
            val release = fetchNewestRelease(endpoints)
                ?: return@withContext Result.Failed("Empty response from release API")
            releaseResult(release, current, variant)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Update check failed", e)
            Result.Failed(e.message ?: "Update check failed", e)
        }
    }

    /**
     * Resolves the newest published release object: `/releases/latest` first (cheap and
     * pre-filtered to a published, non-prerelease release), then the full release list.
     *
     * The fallback matters because the single-release endpoint answers 404 for a repository
     * whose only releases are prereleases — the exact shape a rolling test channel has — and a
     * mirror/proxy in front of the API can also drop that one path while the list still works.
     */
    private suspend fun fetchNewestRelease(endpoints: EndpointConfig): JSONObject? {
        fetchLatestReleaseJson(endpoints)?.let { body ->
            runCatching { JSONObject(body) }.getOrNull()?.let { return it }
        }
        val listJson = fetchReleasesListJson(endpoints) ?: return null
        return newestPublishedRelease(listJson)
    }

    /** Newest non-draft, non-prerelease entry of a `/releases` array, or null when there is none. */
    internal fun newestPublishedRelease(listJson: String): JSONObject? {
        val array = runCatching { org.json.JSONArray(listJson) }.getOrNull() ?: return null
        var best: JSONObject? = null
        var bestVersion: Version? = null
        for (i in 0 until array.length()) {
            val release = array.optJSONObject(i) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name").ifBlank { release.optString("name") }
            val version = Version.parse(tag) ?: continue
            val previous = bestVersion
            if (previous == null || version > previous) {
                best = release
                bestVersion = version
            }
        }
        return best
    }

    /**
     * Turns one release object into a [Result] for [variant]. Pure (no network), so the parsing
     * and selection rules are unit-tested directly.
     */
    internal fun releaseResult(release: JSONObject, current: Version, variant: Variant): Result {
        val tag = release.optString("tag_name").ifBlank { release.optString("name") }
        val latest = Version.parse(tag)
            ?: return Result.Failed("Cannot parse release tag: $tag")

        if (latest <= current) {
            return Result.UpToDate(current.toString())
        }

        val asset = pickBestApkAsset(release, variant)
            ?: return Result.Failed(
                "Release $tag has no ${variant.name.lowercase()} APK asset"
            )

        val rawUrl = asset.optString("browser_download_url")
        if (rawUrl.isBlank()) {
            return Result.Failed("Release asset has no download URL")
        }

        val info = ReleaseInfo(
            version = latest.toString(),
            // Raw asset URL: ApkUpdateInstaller expands the measured mirror
            // candidates itself and retries, so a single stale prefix can
            // no longer sink the download.
            downloadUrl = rawUrl,
            sizeBytes = asset.optLong("size", 0L),
            sha256 = sha256FromDigest(asset.optString("digest")),
            releaseNotes = localizeReleaseBody(release.optString("body"))
        )
        return Result.UpdateAvailable(info, current.toString())
    }

    /**
     * Fetches the full release history (newest first) for the version-history UI. Each entry
     * carries its changelog body and — when available — a (mirrored) APK download URL.
     *
     * Releases without a parseable version tag or without an APK asset are still listed (so
     * the user can read their notes), but [ReleaseSummary.downloadUrl] is null when there is
     * no APK to download. Draft releases are excluded by the API.
     */
    suspend fun fetchAllReleases(
        endpoints: EndpointConfig = EndpointConfig.defaults(),
        variant: Variant = Variant.current()
    ): List<ReleaseSummary> = withContext(Dispatchers.IO) {
        try {
            // Null means every candidate failed — surface it as an error so the
            // history UI shows a failure state instead of an empty list.
            val json = fetchReleasesListJson(endpoints)
                ?: throw IllegalStateException("Empty response from release API")
            val arr = org.json.JSONArray(json)
            val out = ArrayList<ReleaseSummary>(arr.length())
            for (i in 0 until arr.length()) {
                val release = arr.optJSONObject(i) ?: continue
                val tag = release.optString("tag_name").ifBlank { release.optString("name") }
                val version = Version.parse(tag)?.toString() ?: tag
                val asset = pickBestApkAsset(release, variant)
                val rawUrl = asset?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
                out.add(
                    ReleaseSummary(
                        tag = tag,
                        version = version,
                        name = release.optString("name").trim(),
                        publishedAt = release.optString("published_at").trim(),
                        body = localizeReleaseBody(release.optString("body")),
                        downloadUrl = rawUrl,
                        sizeBytes = asset?.optLong("size", 0L) ?: 0L,
                        sha256 = sha256FromDigest(asset?.optString("digest"))
                    )
                )
            }
            // Sort by version descending so newest is on top; unparseable tags sink to the bottom.
            out.sortedWith(compareByDescending { Version.parse(it.version) ?: Version(0, 0, 0) })
        } catch (e: Exception) {
            AppLogger.e(TAG, "Fetch all releases failed", e)
            throw e
        }
    }

    /**
     * Extracts the hex digest from a GitHub asset `digest` field ("sha256:<hex>").
     * Returns null for missing, blank, or non-sha256 values.
     */
    internal fun sha256FromDigest(digest: String?): String? =
        digest?.trim()?.takeIf { it.startsWith("sha256:") }
            ?.substringAfter("sha256:")?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Picks the APK asset of [variant] with the highest version embedded in its file name
     * (for example "Sherwood-WTA-2.8.2.apk" or "Sherwood-WTA-2.8.2-gplay.apk").
     *
     * Assets of the other flavor are never returned: they carry a different application id, so
     * installing one can only fail or install a second copy of the app. Ties, and names without
     * a version, fall back to the larger asset.
     */
    internal fun pickBestApkAsset(release: JSONObject, variant: Variant): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        val tagVersion = Version.parse(
            release.optString("tag_name").ifBlank { release.optString("name") }
        )
        var best: JSONObject? = null
        var bestVersion: Version? = null
        var bestSize = -1L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (Variant.ofAsset(name) != variant) continue
            val version = assetVersionOf(name) ?: tagVersion
            val size = asset.optLong("size", 0L)
            // A name without a version ranks below any parsed one and is compared by size only
            // against other unversioned assets; `best == null` always accepts the first match.
            val versionKey = version ?: Version(0, 0, 0)
            val bestKey = bestVersion ?: Version(0, 0, 0)
            val better = best == null ||
                versionKey > bestKey ||
                (versionKey == bestKey && size > bestSize)
            if (better) {
                best = asset
                bestVersion = version
                bestSize = size
            }
        }
        return best
    }

    /** Version embedded in an asset file name ("…-2.8.2-gplay.apk" → 2.8.2), or null. */
    internal fun assetVersionOf(assetName: String): Version? {
        val stem = assetName.removeSuffix(".apk").removeSuffix(".APK")
            .removeSuffix("-gplay").removeSuffix("-GPLAY")
        return Version.parse(stem.substringAfterLast('-'))
    }

    private suspend fun fetchLatestReleaseJson(endpoints: EndpointConfig): String? =
        // A proxy can answer 200 with a plain-text error ("Suspent",
        // "404 not found") — not every mirror actually proxies
        // api.github.com. Only a JSON object counts as a hit.
        fetchJsonRaced(endpoints.latestReleaseApiUrl()) { it.trimStart().startsWith("{") }

    private suspend fun fetchReleasesListJson(endpoints: EndpointConfig): String? =
        // The all-releases endpoint answers with an array, not an object.
        fetchJsonRaced(endpoints.releasesApiUrl()) { it.trimStart().startsWith("[") }

    /**
     * Fires one GET per mirror candidate concurrently and returns the first body
     * accepted by [accept]. Candidate order comes from
     * [CnMirrorProbe.peekChannels] — never blocking — so a cold probe cannot
     * delay the first request; losing requests keep running detached until
     * their own socket timeout (tiny payloads, bounded by the timeouts below).
     */
    private suspend fun fetchJsonRaced(apiUrl: String, accept: (String) -> Boolean): String? {
        val urls = (com.sherwood.wta.core.network.CnMirrorProbe.peekChannels()
            .map { it.rewrite(apiUrl) } + apiUrl).distinct()
        return fetchJsonRaced(urls, accept)
    }

    private suspend fun fetchJsonRaced(urls: List<String>, accept: (String) -> Boolean): String? {
        val results = Channel<Pair<String, String?>>(urls.size)
        val jobs = urls.map { endpoint ->
            raceScope.launch {
                val body = runCatching { httpGet(endpoint) }
                    .onFailure {
                        AppLogger.w(TAG, "API request failed via $endpoint: ${it.message}")
                    }
                    .getOrNull()
                results.send(endpoint to body)
            }
        }
        var winner: String? = null
        var remaining = urls.size
        withTimeoutOrNull(RACE_TIMEOUT_MS) {
            while (remaining > 0 && winner == null) {
                remaining--
                val (endpoint, body) = results.receive()
                if (body != null && accept(body)) {
                    AppLogger.d(TAG, "API request won via $endpoint")
                    winner = body
                } else if (body != null) {
                    AppLogger.w(TAG, "API returned non-JSON via $endpoint: ${body.take(80)}")
                }
            }
        }
        jobs.forEach { it.cancel() }
        return winner
    }

    private fun httpGet(endpoint: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Sherwood-WTA-UpdateChecker")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $endpoint")
            }
            return connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection?.disconnect()
        }
    }
}
