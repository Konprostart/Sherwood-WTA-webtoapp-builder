package com.sherwood.wta.core.frontend

import android.content.Context
import com.google.gson.JsonParser
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class GitHubRepoSpec(
    val owner: String,
    val repo: String,
    val branch: String? = null,
    val subPath: String? = null
)

data class GitHubFetchResult(
    val localPath: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val subPath: String?,
    /** Commit the working copy came from, or null when it could not be resolved. */
    val baseSha: String? = null,
    /** Absolute path of `repo_bridge.json`, or null when it could not be written. */
    val metadataFile: String? = null
)

sealed class GitHubFetchState {
    object Idle : GitHubFetchState()
    object Resolving : GitHubFetchState()
    data class Downloading(val progress: Float, val urlHint: String) : GitHubFetchState()
    object Extracting : GitHubFetchState()
    data class Success(val localPath: String) : GitHubFetchState()
    data class Error(val message: String) : GitHubFetchState()
}

/** Outcome of [GitHubRepoFetcher.probeToken]. */
sealed class GitHubTokenProbeResult {
    data class Success(val login: String) : GitHubTokenProbeResult()
    object InvalidToken : GitHubTokenProbeResult()
    object Unauthorized : GitHubTokenProbeResult()
    object RateLimited : GitHubTokenProbeResult()
    /** 403 without a rate-limit signal: the token is valid but cannot access GitHub this way. */
    object Forbidden : GitHubTokenProbeResult()
    object NetworkError : GitHubTokenProbeResult()
    object UnexpectedResponse : GitHubTokenProbeResult()
    data class HttpError(val code: Int) : GitHubTokenProbeResult()
}

/**
 * Imports a repository as a working copy.
 *
 * Without a token the public archive endpoint is used exactly as before. With a PAT
 * the authenticated zipball endpoint is preferred, which is what makes private
 * repositories importable; if that request fails the public archive is still tried,
 * so a token can never make a previously working import stop working.
 *
 * All parameters after [context] default to production behaviour, so existing call
 * sites (`GitHubRepoFetcher(context)`) are unchanged.
 */
class GitHubRepoFetcher(
    private val context: Context,
    private val tokenProvider: GitHubTokenProvider = GitHubTokenStore(context),
    private val httpClient: OkHttpClient = NetworkModule.downloadClient,
    apiBaseUrl: String = DEFAULT_API_BASE_URL,
    archiveBaseUrl: String = DEFAULT_ARCHIVE_BASE_URL,
    private val urlGuard: (String) -> Unit = { GitHubUrlPolicy.requireHttps(it) },
    private val metadataStore: RepoBridgeMetadataStore = RepoBridgeMetadataStore()
) {

    private val apiBase = apiBaseUrl.trimEnd('/')
    private val archiveBase = archiveBaseUrl.trimEnd('/')

    /**
     * Refuses HTTPS -> HTTP redirect downgrades, so a token-carrying request cannot be
     * downgraded to cleartext by a misbehaving server. HTTPS -> HTTPS host redirects
     * (api.github.com -> codeload.github.com) still work.
     */
    private val secureClient: OkHttpClient by lazy {
        httpClient.newBuilder().followSslRedirects(false).build()
    }

    /** Bounds the best-effort ref lookup so a slow API can never stall an import. */
    private val shortClient: OkHttpClient by lazy {
        secureClient.newBuilder()
            .callTimeout(REF_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableStateFlow<GitHubFetchState>(GitHubFetchState.Idle)
    val state: StateFlow<GitHubFetchState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun reset() {
        _state.value = GitHubFetchState.Idle
        _logs.value = emptyList()
    }

    suspend fun fetch(
        input: String,
        explicitBranch: String? = null,
        explicitSubPath: String? = null
    ): Result<GitHubFetchResult> = withContext(Dispatchers.IO) {
        _logs.value = emptyList()
        log("Parsing input: $input")

        val spec = parseInput(input)
        if (spec == null) {
            val msg = "Invalid GitHub URL or repo identifier"
            return@withContext fail(msg)
        }

        val branch = explicitBranch?.takeIf { it.isNotBlank() }?.trim() ?: spec.branch
        val subPath = explicitSubPath?.takeIf { it.isNotBlank() }?.trim('/', ' ') ?: spec.subPath
        val branchCandidates = if (branch != null) listOf(branch) else BRANCH_FALLBACKS

        val token = tokenProvider.currentToken()?.takeIf { GitHubTokenFormat.isValid(it) }
        log(
            if (token != null) "Authentication: token present"
            else "Authentication: anonymous (public repositories only)"
        )

        _state.value = GitHubFetchState.Resolving
        log("Owner: ${spec.owner}, Repo: ${spec.repo}, Branch hint: ${branch ?: "auto"}, SubPath: ${subPath ?: "(root)"}")

        var lastError: String? = null
        for (br in branchCandidates) {
            log("Trying branch: $br")

            val baseSha = resolveBaseSha(spec, br, token)
            log("Base commit: ${baseSha?.take(7) ?: "unresolved"}")

            val download = downloadForBranch(spec, br, token)
            if (download == null) {
                lastError = "Failed to download branch $br"
                continue
            }

            _state.value = GitHubFetchState.Extracting
            log("Extracting archive...")
            val extracted = try {
                extract(download, spec.repo, subPath)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Extract failed", e)
                log("Extract error: ${e.message}")
                download.delete()
                return@withContext fail(e.message ?: "Failed to extract archive")
            } finally {
                download.delete()
            }

            val metadata = RepoBridgeMetadata(
                host = HOST,
                owner = spec.owner,
                repo = spec.repo,
                ref = br,
                baseSha = baseSha,
                subPath = subPath
            )
            val metadataFile = runCatching { metadataStore.write(extracted, metadata) }
                .onFailure { log("Metadata write failed: ${it.javaClass.simpleName}") }
                .getOrNull()

            _state.value = GitHubFetchState.Success(extracted.absolutePath)
            log("Done: ${extracted.absolutePath}")
            return@withContext Result.success(
                GitHubFetchResult(
                    localPath = extracted.absolutePath,
                    owner = spec.owner,
                    repo = spec.repo,
                    branch = br,
                    subPath = subPath,
                    baseSha = baseSha,
                    metadataFile = metadataFile?.absolutePath
                )
            )
        }

        fail(lastError ?: "Failed to download repository (check URL and branch)")
    }

    /**
     * Verifies a PAT against `GET /user` and resolves the account login.
     *
     * The blocking call runs on [Dispatchers.IO], so callers may invoke this from the
     * main thread. The token travels only in the Authorization header and is never
     * logged; only the HTTP status code is.
     */
    suspend fun probeToken(rawToken: String): GitHubTokenProbeResult =
        withContext(Dispatchers.IO) {
            val token = GitHubTokenFormat.normalize(rawToken)
            if (!GitHubTokenFormat.isValid(token)) {
                return@withContext GitHubTokenProbeResult.InvalidToken
            }
            val url = apiUrl("user")
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .build()
            try {
                urlGuard(url)
                shortClient.newCall(request).execute().use { resp ->
                    log("Token probe: HTTP ${resp.code}")
                    when {
                        resp.isSuccessful -> {
                            val login = resp.body?.string()?.let(::extractLogin)
                            if (login != null) {
                                GitHubTokenProbeResult.Success(login)
                            } else {
                                GitHubTokenProbeResult.UnexpectedResponse
                            }
                        }
                        resp.code == 401 -> GitHubTokenProbeResult.Unauthorized
                        resp.code == 429 ||
                            (resp.code == 403 &&
                                (resp.header("x-ratelimit-remaining") == "0" ||
                                    resp.header("retry-after") != null)) ->
                            GitHubTokenProbeResult.RateLimited
                        resp.code == 403 -> GitHubTokenProbeResult.Forbidden
                        else -> GitHubTokenProbeResult.HttpError(resp.code)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Token probe failed: ${e.javaClass.simpleName}")
                GitHubTokenProbeResult.NetworkError
            }
        }

    private fun extractLogin(json: String): String? = runCatching {
        JsonParser.parseString(json)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("login")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() && it.length <= MAX_LOGIN_LENGTH }

    /**
     * Authenticated zipball first when a token is available, then the public archive.
     * Returning the public route as a fallback keeps public imports working even when a
     * token is expired or lacks the scope for the repository.
     */
    private fun downloadForBranch(spec: GitHubRepoSpec, ref: String, token: String?): File? {
        if (token != null) {
            val authenticated = runCatching { downloadApiZipball(spec, ref, token) }
                .onFailure { log("Authenticated archive request failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (authenticated != null) return authenticated
            log("Authenticated download failed; falling back to the public archive")
        }
        return runCatching { downloadPublicArchive(spec, ref) }
            .onFailure { log("Archive request failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * The API route must be `zipball`: `tarball` returns gzipped tar data, which
     * ZipInputStream reads as zero entries without any error.
     */
    private fun downloadApiZipball(spec: GitHubRepoSpec, ref: String, token: String): File? {
        val url = apiUrl("repos", spec.owner, spec.repo, "zipball", ref)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .build()
        // The URL is safe to log: the token only ever travels in the header.
        log("Download (authenticated): $url")
        return executeDownload(request, secureClient)
    }

    private fun downloadPublicArchive(spec: GitHubRepoSpec, ref: String): File? {
        val url = archiveUrl(spec, ref)
        log("Download: $url")
        val request = Request.Builder().url(url).get().build()
        return executeDownload(request)
    }

    private fun executeDownload(request: Request, client: OkHttpClient = httpClient): File? {
        urlGuard(request.url.toString())
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                log("HTTP ${resp.code}")
                return null
            }
            val body = resp.body ?: return null
            val total = body.contentLength()
            val urlHint = request.url.toString()
            val tempFile = File(context.cacheDir, "gh_repo_${System.currentTimeMillis()}.zip")
            try {
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var totalRead = 0L
                        var read: Int
                        _state.value = GitHubFetchState.Downloading(0f, urlHint)
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            totalRead += read
                            if (totalRead > MAX_DOWNLOAD_SIZE) {
                                throw IllegalStateException("Archive larger than ${MAX_DOWNLOAD_SIZE / 1024 / 1024} MB")
                            }
                            val progress = if (total > 0) (totalRead.toFloat() / total).coerceIn(0f, 1f) else 0f
                            _state.value = GitHubFetchState.Downloading(progress, urlHint)
                        }
                    }
                }
                return tempFile
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }
    }

    /**
     * Best-effort resolution of the commit a branch points at. Failures are logged and
     * never fail the import: an offline or rate-limited lookup only means [baseSha] is
     * unknown for that working copy.
     */
    private fun resolveBaseSha(spec: GitHubRepoSpec, ref: String, token: String?): String? {
        val url = apiUrl("repos", spec.owner, spec.repo, "git", "refs", "heads", ref)
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
        if (token != null) builder.header("Authorization", "Bearer $token")

        return try {
            urlGuard(url)
            shortClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log("Ref lookup HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                extractSha(body)
            }
        } catch (e: Exception) {
            log("Ref lookup failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun extractSha(json: String): String? {
        val element = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        val sha = when {
            element.isJsonArray -> element.asJsonArray.firstOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getAsJsonObject("object")
                ?.get("sha")
                ?.asString
            element.isJsonObject -> element.asJsonObject
                .getAsJsonObject("object")
                ?.get("sha")
                ?.asString
            else -> null
        }
        return sha?.lowercase()?.takeIf { RepoBridgeMetadata.isSha(it) }
    }

    private fun apiUrl(first: String, vararg rest: String): String =
        apiBase.toHttpUrl().newBuilder().addPathSegments(first).apply {
            rest.forEach { addPathSegments(it) }
        }.build().toString()

    private fun archiveUrl(spec: GitHubRepoSpec, ref: String): String =
        archiveBase.toHttpUrl().newBuilder()
            .addPathSegment(spec.owner)
            .addPathSegment(spec.repo)
            .addPathSegments("archive/refs/heads")
            .addPathSegments("$ref.zip")
            .build()
            .toString()

    private fun extract(zipFile: File, repoName: String, subPath: String?): File {
        val extractDir = File(
            context.filesDir,
            "github_imports/${sanitize(repoName)}_${System.currentTimeMillis()}"
        ).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        var entryCount = 0
        var fileCount = 0
        var totalBytes = 0L

        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) {
                        throw IllegalStateException("Archive has too many entries (> $MAX_ENTRY_COUNT)")
                    }

                    val entryName = entry.name
                    if (shouldSkipEntry(entryName)) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    val target = File(extractDir, entryName).canonicalFile
                    // + File.separator: a bare prefix match lets sibling directories through.
                    if (!target.path.startsWith(extractDir.canonicalPath + File.separator)) {
                        AppLogger.w(TAG, "Skip unsafe zip entry: $entryName")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        fileCount++
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            while (zis.read(buf).also { read = it } != -1) {
                                totalBytes += read
                                if (totalBytes > MAX_EXTRACTED_SIZE) {
                                    throw IllegalStateException(
                                        "Extracted content larger than ${MAX_EXTRACTED_SIZE / 1024 / 1024} MB"
                                    )
                                }
                                out.write(buf, 0, read)
                            }
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            // A body that is not a zip yields zero entries; never report that as success.
            if (fileCount == 0) {
                throw IllegalStateException("Downloaded archive contains no files")
            }
        } catch (e: Exception) {
            extractDir.deleteRecursively()
            throw e
        }

        val unwrapped = unwrapSingleRootDir(extractDir)

        val finalDir = if (!subPath.isNullOrBlank()) {
            val sub = File(unwrapped, subPath).canonicalFile
            if (!sub.path.startsWith(unwrapped.canonicalPath)) {
                extractDir.deleteRecursively()
                throw IllegalStateException("Invalid sub-path: $subPath")
            }
            if (!sub.exists() || !sub.isDirectory) {
                extractDir.deleteRecursively()
                throw IllegalStateException("Sub-path not found in repository: $subPath")
            }
            sub
        } else {
            unwrapped
        }

        return finalDir
    }

    private fun unwrapSingleRootDir(dir: File): File {
        val children = dir.listFiles() ?: return dir
        return if (children.size == 1 && children[0].isDirectory) children[0] else dir
    }

    private fun shouldSkipEntry(name: String): Boolean {
        val parts = name.split('/', '\\')
        return parts.any { p -> SKIP_PATTERNS.any { it.equals(p, ignoreCase = true) } }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    private fun log(line: String) {
        _logs.value = _logs.value + line
        AppLogger.d(TAG, line)
    }

    private fun fail(message: String): Result<GitHubFetchResult> {
        _state.value = GitHubFetchState.Error(message)
        log("Failed: $message")
        return Result.failure(IllegalStateException(message))
    }

    companion object {
        private const val TAG = "GitHubRepoFetcher"

        private const val HOST = RepoBridgeMetadata.HOST_GITHUB
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val DEFAULT_API_BASE_URL = "https://api.github.com"
        private const val DEFAULT_ARCHIVE_BASE_URL = "https://github.com"
        private const val REF_LOOKUP_TIMEOUT_SECONDS = 5L

        private const val MAX_DOWNLOAD_SIZE = 300L * 1024 * 1024

        private const val MAX_EXTRACTED_SIZE = 1024L * 1024 * 1024

        private const val MAX_ENTRY_COUNT = 50_000

        private const val MAX_LOGIN_LENGTH = 100

        private val SKIP_PATTERNS = setOf(
            "__MACOSX", ".DS_Store", "Thumbs.db"
        )

        private val BRANCH_FALLBACKS = listOf("main", "master")

        private val SHORT_RE = Regex("^([\\w.-]+)/([\\w.-]+?)(?:\\.git)?$")
        private val SSH_RE = Regex("^git@github\\.com:([\\w.-]+)/([\\w.-]+?)(?:\\.git)?$")
        private val HTTPS_RE = Regex(
            "^https?://github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:/tree/([^/]+)(?:/(.+))?)?/?$"
        )
        private val ARCHIVE_RE = Regex(
            "^https?://github\\.com/([\\w.-]+)/([\\w.-]+?)/archive/(?:refs/heads/)?([^/]+?)\\.zip$"
        )

        fun parseInput(raw: String): GitHubRepoSpec? {
            val input = raw.trim()
            if (input.isEmpty()) return null

            ARCHIVE_RE.matchEntire(input)?.let {
                return GitHubRepoSpec(
                    owner = it.groupValues[1],
                    repo = it.groupValues[2].removeSuffix(".git"),
                    branch = it.groupValues[3]
                )
            }

            HTTPS_RE.matchEntire(input)?.let {
                return GitHubRepoSpec(
                    owner = it.groupValues[1],
                    repo = it.groupValues[2].removeSuffix(".git"),
                    branch = it.groupValues[3].takeIf { v -> v.isNotEmpty() },
                    subPath = it.groupValues[4].takeIf { v -> v.isNotEmpty() }
                )
            }

            SSH_RE.matchEntire(input)?.let {
                return GitHubRepoSpec(
                    owner = it.groupValues[1],
                    repo = it.groupValues[2].removeSuffix(".git")
                )
            }

            SHORT_RE.matchEntire(input)?.let {
                if (input.contains("://") || input.startsWith("git@")) return@let
                return GitHubRepoSpec(
                    owner = it.groupValues[1],
                    repo = it.groupValues[2].removeSuffix(".git")
                )
            }

            return null
        }
    }
}
