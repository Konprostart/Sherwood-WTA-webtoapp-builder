package com.sherwood.wta.core.studio

import android.content.Context
import com.google.gson.JsonParser
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What the user has to do after a device-flow start: open [verificationUri] and type
 * [userCode]. [deviceCode] is the polling secret and is never displayed or logged.
 */
data class DeviceFlowChallenge(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

sealed class DeviceFlowStartResult {
    data class Success(val challenge: DeviceFlowChallenge) : DeviceFlowStartResult()
    data class Failure(val message: String, val httpCode: Int? = null) : DeviceFlowStartResult()
}

sealed class DeviceFlowPollResult {
    /** The user approved; a `gho_` OAuth token was issued. */
    data class Success(val token: String) : DeviceFlowPollResult()

    /** The user has not approved yet; poll again after the current interval. */
    object Pending : DeviceFlowPollResult()

    /** GitHub asked us to slow down; add five seconds to the interval. */
    object SlowDown : DeviceFlowPollResult()

    /** The code expired or the user denied the request. */
    data class Failed(val message: String, val denied: Boolean = false) : DeviceFlowPollResult()
}

/**
 * GitHub OAuth Device Flow (`/login/device/code` + `/login/oauth/access_token`).
 *
 * Why device flow instead of a redirect URI: the app is installed from an APK and
 * has no verifiable HTTPS callback. Device flow is the OAuth grant GitHub designed
 * for exactly that case; it needs only a public OAuth App client ID, never a client
 * secret. The client ID belongs to the user/fork, not to this project's author, and
 * is configured on the Studio screen.
 *
 * The polling token is the only secret in this class and it never reaches
 * [AppLogger]. No token is stored here either: the caller hands the issued
 * `gho_` token to [com.sherwood.wta.core.frontend.GitHubTokenStore].
 */
class GitHubOAuthClient(
    private val httpClient: OkHttpClient = NetworkModule.downloadClient,
    private val endpoints: EndpointConfig = EndpointConfig.defaults(),
    private val urlGuard: (String) -> Unit = { com.sherwood.wta.core.frontend.GitHubUrlPolicy.requireHttps(it) }
) {

    suspend fun startDeviceFlow(clientId: String, scope: String = DEFAULT_SCOPE): DeviceFlowStartResult =
        withContext(Dispatchers.IO) {
            val id = clientId.trim()
            if (!isValidClientId(id)) {
                return@withContext DeviceFlowStartResult.Failure("OAuth client ID is missing or invalid.")
            }
            val body = FormBody.Builder()
                .add("client_id", id)
                .add("scope", scope)
                .build()
            val request = Request.Builder()
                .url(endpoints.githubDeviceCodeUrl)
                .header("Accept", "application/json")
                .post(body)
                .build()
            try {
                urlGuard(endpoints.githubDeviceCodeUrl)
                secureClient.newCall(request).execute().use { response ->
                    val text = response.body?.string()
                    if (!response.isSuccessful) {
                        return@withContext DeviceFlowStartResult.Failure(
                            message = "GitHub rejected the device-code request.",
                            httpCode = response.code
                        )
                    }
                    val parsed = parseChallenge(text)
                    if (parsed == null) {
                        DeviceFlowStartResult.Failure("GitHub returned an unexpected device-code response.")
                    } else {
                        DeviceFlowStartResult.Success(parsed)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Device flow start failed: ${e.javaClass.simpleName}")
                DeviceFlowStartResult.Failure("Network error while contacting GitHub.")
            }
        }

    suspend fun pollForToken(clientId: String, deviceCode: String): DeviceFlowPollResult =
        withContext(Dispatchers.IO) {
            val id = clientId.trim()
            if (!isValidClientId(id) || deviceCode.isBlank()) {
                return@withContext DeviceFlowPollResult.Failed("OAuth client ID or device code is missing.")
            }
            val body = FormBody.Builder()
                .add("client_id", id)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val request = Request.Builder()
                .url(endpoints.githubAccessTokenUrl)
                .header("Accept", "application/json")
                .post(body)
                .build()
            try {
                urlGuard(endpoints.githubAccessTokenUrl)
                secureClient.newCall(request).execute().use { response ->
                    val text = response.body?.string()
                    if (!response.isSuccessful) {
                        // A non-2xx is not necessarily fatal (some proxies return 400 for
                        // authorization_pending), but without a parseable error we stop.
                        return@withContext DeviceFlowPollResult.Failed(
                            "GitHub returned HTTP ${response.code} while polling."
                        )
                    }
                    parsePoll(text)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Device flow poll failed: ${e.javaClass.simpleName}")
                DeviceFlowPollResult.Failed("Network error while waiting for authorization.")
            }
        }

    private val secureClient: OkHttpClient by lazy {
        httpClient.newBuilder().followSslRedirects(false).build()
    }

    companion object {
        private const val TAG = "GitHubOAuth"

        /**
         * `repo` covers private repository read/write and Git Data; `read:user` lets the
         * app show which account is connected; `workflow` is required to dispatch a
         * GitHub Actions workflow.
         */
        const val DEFAULT_SCOPE = "repo read:user workflow"

        private val CLIENT_ID_RE = Regex("^[A-Za-z0-9._-]{10,100}$")

        fun isValidClientId(value: String): Boolean =
            value.isNotBlank() && CLIENT_ID_RE.matches(value.trim())

        /** Pure parser, unit-tested without a network. */
        fun parseChallenge(body: String?): DeviceFlowChallenge? {
            val json = body?.let {
                runCatching { JsonParser.parseString(it) }.getOrNull()
            }?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            fun str(key: String): String? =
                json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            val deviceCode = str("device_code") ?: return null
            val userCode = str("user_code") ?: return null
            val verificationUri = str("verification_uri")
                ?: str("verification_url")
                ?: return null
            if (!verificationUri.startsWith("https://", ignoreCase = true) &&
                !verificationUri.startsWith("http://", ignoreCase = true)
            ) {
                return null
            }
            val expires = json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asInt ?: 900
            val interval = json.get("interval")?.takeIf { it.isJsonPrimitive }?.asInt ?: 5
            return DeviceFlowChallenge(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                expiresInSeconds = expires.coerceIn(60, 3600),
                intervalSeconds = interval.coerceIn(1, 60)
            )
        }

        /** Pure parser, unit-tested without a network. */
        fun parsePoll(body: String?): DeviceFlowPollResult {
            val json = body?.let {
                runCatching { JsonParser.parseString(it) }.getOrNull()
            }?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return DeviceFlowPollResult.Failed("GitHub returned an unexpected response.")
            val token = json.get("access_token")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.takeIf { it.isNotBlank() }
            if (token != null) {
                return DeviceFlowPollResult.Success(token)
            }
            val error = json.get("error")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                .orEmpty()
            return when (error) {
                "authorization_pending" -> DeviceFlowPollResult.Pending
                "slow_down" -> DeviceFlowPollResult.SlowDown
                "expired_token" -> DeviceFlowPollResult.Failed(
                    "The device code expired. Start the connection again."
                )
                "access_denied" -> DeviceFlowPollResult.Failed(
                    "Authorization was denied on GitHub.",
                    denied = true
                )
                "incorrect_client_credentials" -> DeviceFlowPollResult.Failed(
                    "GitHub did not recognise the OAuth client ID."
                )
                "unsupported_grant_type" -> DeviceFlowPollResult.Failed(
                    "Device flow is not enabled for this OAuth App."
                )
                else -> DeviceFlowPollResult.Failed(
                    "GitHub returned an error while polling.",
                )
            }
        }
    }
}

/**
 * Local Studio preferences: the public OAuth client ID, the repository watchlist for
 * the workspace inbox, and the last selected repository. No token and no secret is
 * stored here — that is [com.sherwood.wta.core.frontend.GitHubTokenStore]'s job.
 */
class StudioSettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var oauthClientId: String
        get() = prefs.getString(KEY_OAUTH_CLIENT_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_OAUTH_CLIENT_ID, value.trim()).apply()
        }

    var lastRepository: String?
        get() = prefs.getString(KEY_LAST_REPO, null)?.takeIf { it.contains('/') }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_REPO) else putString(KEY_LAST_REPO, value)
            }.apply()
        }

    /** Repositories shown in the workspace inbox, as `owner/repo`. */
    fun watchlist(): List<String> =
        prefs.getStringSet(KEY_WATCHLIST, emptySet()).orEmpty().sorted()

    fun setWatchlist(repos: Collection<String>) {
        prefs.edit().putStringSet(KEY_WATCHLIST, repos.toSet()).apply()
    }

    fun toggleWatch(ownerRepo: String): List<String> {
        val current = watchlist().toMutableSet()
        if (!current.add(ownerRepo)) current.remove(ownerRepo)
        setWatchlist(current)
        return current.sorted()
    }

    companion object {
        internal const val PREFS = "sherwood_studio_settings"
        internal const val KEY_OAUTH_CLIENT_ID = "oauth_client_id"
        internal const val KEY_LAST_REPO = "last_repository"
        internal const val KEY_WATCHLIST = "workspace_watchlist"

        @Volatile
        private var INSTANCE: StudioSettingsStore? = null

        fun getInstance(context: Context): StudioSettingsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StudioSettingsStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
