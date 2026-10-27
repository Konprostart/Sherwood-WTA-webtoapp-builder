package com.sherwood.wta.core.frontend

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Why a Git Data API call failed. */
enum class GitDataFailureKind {
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    /** The remote branch no longer points at the expected commit. */
    CONFLICT,
    /** GitHub rejected the payload (422), e.g. a malformed tree entry. */
    UNPROCESSABLE,
    NETWORK,
    /** A 2xx response whose body did not carry the expected fields. */
    UNEXPECTED,
    HTTP
}

sealed class GitDataResult<out T> {
    data class Success<T>(val value: T) : GitDataResult<T>()

    data class Failure(
        val kind: GitDataFailureKind,
        val statusCode: Int? = null,
        val message: String? = null
    ) : GitDataResult<Nothing>()
}

/** A branch ref as returned by `GET/PATCH /git/ref/heads/{ref}`. */
data class GitRef(val ref: String, val sha: String)

/** The commit sha and the tree it points at. */
data class GitCommitInfo(val sha: String, val treeSha: String)

/**
 * One path in a tree. [mode] is the git file mode and [type] the object type.
 * A null [sha] removes the path when the entry is sent in a tree update.
 */
data class GitTreeEntry(
    val path: String,
    val sha: String?,
    val mode: String = "100644",
    val type: String = "blob"
)

/** A resolved tree, plus GitHub's flag for repos too large to list completely. */
data class GitTreeSnapshot(val entries: List<GitTreeEntry>, val truncated: Boolean = false)

/**
 * Minimal client for the GitHub Git Data API.
 *
 * Phase 1 exposes the primitives a commit needs: read a commit's tree, create
 * blobs, create a tree on top of it, create the commit, then advance the branch
 * ref. There is deliberately no JGit and no local repository: every object is
 * built server-side, exactly like the import path builds the working copy.
 *
 * All calls run on [Dispatchers.IO], always send the PAT in the Authorization
 * header, refuse non-HTTPS URLs through [GitHubUrlPolicy], and never log the
 * token. Failures are mapped to [GitDataFailureKind] so callers can tell a
 * moved branch ([GitDataFailureKind.CONFLICT]) from a bad token or a network
 * problem.
 */
class GitDataApiClient(
    context: Context,
    private val tokenProvider: GitHubTokenProvider = GitHubTokenStore(context),
    httpClient: OkHttpClient = NetworkModule.downloadClient,
    apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val urlGuard: (String) -> Unit = { GitHubUrlPolicy.requireHttps(it) }
) {

    private val apiBase = apiBaseUrl.trimEnd('/')

    /** Refuses HTTPS -> HTTP redirect downgrades for token-bearing requests. */
    private val client: OkHttpClient = httpClient.newBuilder()
        .followSslRedirects(false)
        .build()

    private val gson = Gson()

    suspend fun getRef(
        owner: String,
        repo: String,
        ref: String
    ): GitDataResult<GitRef> = withContext(Dispatchers.IO) {
        val url = apiUrl("repos", owner, repo, "git", "ref", "heads", ref)
        request("GET", url, null) { body ->
            val json = parseObject(body) ?: return@request null
            val sha = json.objectSha("object") ?: json.stringSha("sha") ?: return@request null
            GitRef(json.string("ref") ?: "refs/heads/$ref", sha)
        }
    }

    /** Reads the tree of [sha] so a new tree can be layered on top of it. */
    suspend fun getCommit(
        owner: String,
        repo: String,
        sha: String
    ): GitDataResult<GitCommitInfo> = withContext(Dispatchers.IO) {
        val url = apiUrl("repos", owner, repo, "git", "commits", sha)
        request("GET", url, null) { body ->
            val json = parseObject(body) ?: return@request null
            val commitSha = json.stringSha("sha") ?: return@request null
            val treeSha = json.objectSha("tree") ?: return@request null
            GitCommitInfo(commitSha, treeSha)
        }
    }

    /**
     * Lists every path under [treeSha]. Used to diff the working copy against the
     * commit it came from without keeping a local manifest.
     */
    suspend fun getTree(
        owner: String,
        repo: String,
        treeSha: String,
        recursive: Boolean = true
    ): GitDataResult<GitTreeSnapshot> = withContext(Dispatchers.IO) {
        val url = apiUrl("repos", owner, repo, "git", "trees", treeSha) +
            if (recursive) "?recursive=1" else ""
        request("GET", url, null) { body ->
            val json = parseObject(body) ?: return@request null
            val array = json.getAsJsonArray("tree") ?: return@request null
            val entries = array.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val path = obj.string("path") ?: return@mapNotNull null
                val sha = obj.stringSha("sha") ?: return@mapNotNull null
                GitTreeEntry(
                    path = path,
                    sha = sha,
                    mode = obj.string("mode") ?: "100644",
                    type = obj.string("type") ?: "blob"
                )
            }
            val truncated = json.get("truncated")
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean
                ?: false
            GitTreeSnapshot(entries, truncated)
        }
    }

    /** Creates a blob and returns its sha. */
    suspend fun createBlob(
        owner: String,
        repo: String,
        content: ByteArray
    ): GitDataResult<String> = withContext(Dispatchers.IO) {
        val payload = gson.toJson(
            BlobRequest(
                content = Base64.encodeToString(content, Base64.NO_WRAP),
                encoding = "base64"
            )
        )
        val url = apiUrl("repos", owner, repo, "git", "blobs")
        request("POST", url, payload) { body ->
            parseObject(body)?.stringSha("sha")
        }
    }

    /**
     * Creates a tree on top of [baseTreeSha]. When [baseTreeSha] is null the tree is
     * a full snapshot, so callers normally pass the tree of the commit they started
     * from and only the changed paths.
     */
    suspend fun createTree(
        owner: String,
        repo: String,
        baseTreeSha: String?,
        entries: List<GitTreeEntry>
    ): GitDataResult<String> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) {
            return@withContext GitDataResult.Failure(
                GitDataFailureKind.UNPROCESSABLE,
                message = "No tree entries to create"
            )
        }
        val payload = gson.toJson(
            TreeRequest(
                baseTree = baseTreeSha,
                tree = entries.map { TreeEntryPayload(it.path, it.mode, "blob", it.sha) }
            )
        )
        val url = apiUrl("repos", owner, repo, "git", "trees")
        request("POST", url, payload) { body ->
            parseObject(body)?.stringSha("sha")
        }
    }

    /** Creates a commit whose parent is [parentSha] and returns the new commit sha. */
    suspend fun createCommit(
        owner: String,
        repo: String,
        message: String,
        treeSha: String,
        parentSha: String
    ): GitDataResult<String> = withContext(Dispatchers.IO) {
        val payload = gson.toJson(
            CommitRequest(
                message = message,
                tree = treeSha,
                parents = listOf(parentSha)
            )
        )
        val url = apiUrl("repos", owner, repo, "git", "commits")
        request("POST", url, payload) { body ->
            parseObject(body)?.stringSha("sha")
        }
    }

    /**
     * Fast-forwards `refs/heads/{branch}` to [newSha].
     *
     * When [expectedBaseSha] is set the current ref is read first; a mismatch fails
     * with [GitDataFailureKind.CONFLICT] without creating anything. The PATCH itself
     * is always `force=false`, so a race between that read and the update is still
     * rejected by GitHub and mapped to the same conflict.
     */
    suspend fun updateRef(
        owner: String,
        repo: String,
        branch: String,
        newSha: String,
        expectedBaseSha: String? = null,
        force: Boolean = false
    ): GitDataResult<GitRef> = withContext(Dispatchers.IO) {
        if (expectedBaseSha != null) {
            when (val current = getRef(owner, repo, branch)) {
                is GitDataResult.Success -> {
                    if (current.value.sha != expectedBaseSha) {
                        return@withContext GitDataResult.Failure(
                            GitDataFailureKind.CONFLICT,
                            message = "Remote branch moved from $expectedBaseSha to ${current.value.sha}"
                        )
                    }
                }
                is GitDataResult.Failure -> return@withContext current
            }
        }
        val payload = gson.toJson(RefUpdateRequest(sha = newSha, force = force))
        val url = apiUrl("repos", owner, repo, "git", "refs", "heads", branch)
        request("PATCH", url, payload) { body ->
            val json = parseObject(body) ?: return@request null
            val sha = json.objectSha("object") ?: json.stringSha("sha") ?: return@request null
            GitRef(json.string("ref") ?: "refs/heads/$branch", sha)
        }
    }

    private fun <T> request(
        method: String,
        url: String,
        jsonBody: String?,
        parse: (String?) -> T?
    ): GitDataResult<T> {
        val token = tokenProvider.currentToken()?.takeIf { GitHubTokenFormat.isValid(it) }
            ?: return GitDataResult.Failure(
                GitDataFailureKind.UNAUTHORIZED,
                message = "No GitHub token configured"
            )

        val body = jsonBody?.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)

        return try {
            urlGuard(url)
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post(requireNotNull(body))
                "PATCH" -> builder.patch(requireNotNull(body))
                else -> builder.method(method, body)
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    return failureFor(
                        code = response.code,
                        rateLimitRemaining = response.header("x-ratelimit-remaining"),
                        message = extractErrorMessage(text)
                    )
                }
                val value = runCatching { parse(text) }.getOrNull()
                if (value == null) {
                    GitDataResult.Failure(
                        GitDataFailureKind.UNEXPECTED,
                        response.code,
                        "Unexpected response from GitHub"
                    )
                } else {
                    GitDataResult.Success(value)
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Git Data API request failed: ${e.javaClass.simpleName}")
            GitDataResult.Failure(GitDataFailureKind.NETWORK)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Git Data API request error: ${e.javaClass.simpleName}")
            GitDataResult.Failure(GitDataFailureKind.NETWORK)
        }
    }

    private fun failureFor(
        code: Int,
        rateLimitRemaining: String?,
        message: String?
    ): GitDataResult.Failure = when {
        code == 401 -> GitDataResult.Failure(GitDataFailureKind.UNAUTHORIZED, code, message)
        code == 429 -> GitDataResult.Failure(GitDataFailureKind.RATE_LIMITED, code, message)
        code == 403 && rateLimitRemaining == "0" ->
            GitDataResult.Failure(GitDataFailureKind.RATE_LIMITED, code, message)
        code == 403 -> GitDataResult.Failure(GitDataFailureKind.FORBIDDEN, code, message)
        code == 409 -> GitDataResult.Failure(GitDataFailureKind.CONFLICT, code, message)
        code == 422 && message?.contains("fast forward", ignoreCase = true) == true ->
            GitDataResult.Failure(GitDataFailureKind.CONFLICT, code, message)
        code == 422 -> GitDataResult.Failure(GitDataFailureKind.UNPROCESSABLE, code, message)
        else -> GitDataResult.Failure(GitDataFailureKind.HTTP, code, message)
    }

    private fun apiUrl(first: String, vararg rest: String): String =
        apiBase.toHttpUrl().newBuilder().addPathSegments(first).apply {
            rest.forEach { addPathSegments(it) }
        }.build().toString()

    private fun parseObject(body: String?): JsonObject? =
        body?.let {
            runCatching {
                JsonParser.parseString(it).takeIf { element -> element.isJsonObject }?.asJsonObject
            }.getOrNull()
        }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringSha(key: String): String? =
        string(key)?.takeIf { RepoBridgeMetadata.isSha(it) }

    private fun JsonObject.objectSha(key: String): String? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject?.stringSha("sha")

    private fun extractErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val fromJson = runCatching {
            JsonParser.parseString(body)
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("message")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
        }.getOrNull()
        return (fromJson ?: body).trim().take(MAX_ERROR_MESSAGE_LENGTH).takeIf { it.isNotEmpty() }
    }

    private data class BlobRequest(
        val content: String,
        val encoding: String
    )

    private data class TreeRequest(
        @SerializedName("base_tree") val baseTree: String?,
        val tree: List<TreeEntryPayload>
    )

    private data class TreeEntryPayload(
        val path: String,
        val mode: String,
        val type: String,
        val sha: String?
    )

    private data class CommitRequest(
        val message: String,
        val tree: String,
        val parents: List<String>
    )

    private data class RefUpdateRequest(
        val sha: String,
        val force: Boolean
    )

    companion object {
        private const val TAG = "GitDataApiClient"

        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val DEFAULT_API_BASE_URL = "https://api.github.com"
        private const val MAX_ERROR_MESSAGE_LENGTH = 500

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
