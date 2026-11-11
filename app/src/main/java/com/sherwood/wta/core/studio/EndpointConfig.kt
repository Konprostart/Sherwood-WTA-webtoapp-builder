package com.sherwood.wta.core.studio

import android.content.Context
import com.sherwood.wta.core.logging.AppLogger

/**
 * One external address the app talks to.
 *
 * Every field is HTTPS. A user may point the app at their own GitHub Enterprise
 * server, their own module-market mirror, or a fork of this repository, without
 * rebuilding: [EndpointSettingsStore] persists only the fields that differ from
 * the defaults and [EndpointConfig] validates them before they are used.
 */
data class EndpointConfig(
    val githubApiBaseUrl: String = DEFAULT_GITHUB_API,
    val githubWebBaseUrl: String = DEFAULT_GITHUB_WEB,
    val githubRawBaseUrl: String = DEFAULT_GITHUB_RAW,
    val githubDeviceCodeUrl: String = DEFAULT_GITHUB_DEVICE_CODE,
    val githubAccessTokenUrl: String = DEFAULT_GITHUB_ACCESS_TOKEN,
    /** Owner of the repository that hosts updates, modules and sample packs. */
    val projectOwner: String = DEFAULT_PROJECT_OWNER,
    /** Repository name (without the owner) that hosts updates, modules and sample packs. */
    val projectRepo: String = DEFAULT_PROJECT_REPO,
    /** Branch used for raw content (modules, sample packs, workspace manifest). */
    val projectBranch: String = DEFAULT_PROJECT_BRANCH,
    val docsBaseUrl: String = DEFAULT_DOCS,
    val modulesBaseUrl: String = DEFAULT_MODULES,
    val modulesMirrorBaseUrl: String = DEFAULT_MODULES_MIRROR,
    val sampleBundlesBaseUrl: String = DEFAULT_SAMPLE_BUNDLES,
    val sampleBundlesMirrorBaseUrl: String = DEFAULT_SAMPLE_BUNDLES_MIRROR
) {

    /** Updates API for the configured project repository. */
    fun latestReleaseApiUrl(): String =
        "$githubApiBaseUrl/repos/$projectOwner/$projectRepo/releases/latest"

    fun releasesApiUrl(): String =
        "$githubApiBaseUrl/repos/$projectOwner/$projectRepo/releases?per_page=100"

    /** GitHub HTML page of a repository. */
    fun repositoryWebUrl(owner: String, repo: String): String =
        "$githubWebBaseUrl/$owner/$repo"

    /** A workspace manifest inside a repository, read over raw content. */
    fun workspaceManifestUrl(owner: String, repo: String, branch: String = projectBranch): String =
        "$githubRawBaseUrl/$owner/$repo/$branch/$WORKSPACE_MANIFEST_PATH"

    /**
     * Returns one problem per invalid field. Empty means the config is safe to use.
     * Never throws so the settings screen can show every error at once.
     */
    fun validationErrors(): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        val urls = linkedMapOf(
            "githubApiBaseUrl" to githubApiBaseUrl,
            "githubWebBaseUrl" to githubWebBaseUrl,
            "githubRawBaseUrl" to githubRawBaseUrl,
            "githubDeviceCodeUrl" to githubDeviceCodeUrl,
            "githubAccessTokenUrl" to githubAccessTokenUrl,
            "docsBaseUrl" to docsBaseUrl,
            "modulesBaseUrl" to modulesBaseUrl,
            "modulesMirrorBaseUrl" to modulesMirrorBaseUrl,
            "sampleBundlesBaseUrl" to sampleBundlesBaseUrl,
            "sampleBundlesMirrorBaseUrl" to sampleBundlesMirrorBaseUrl
        )
        urls.forEach { (field, value) ->
            validateHttpsUrl(value)?.let { errors[field] = it }
        }
        if (!OWNER_RE.matches(projectOwner)) {
            errors["projectOwner"] = "Owner must contain only letters, digits, '-' and '_'."
        }
        if (!REPO_RE.matches(projectRepo)) {
            errors["projectRepo"] = "Repository name must contain only letters, digits, '.', '-' and '_'."
        }
        if (!isValidBranchName(projectBranch)) {
            errors["projectBranch"] = "Branch name is not valid."
        }
        return errors
    }

    fun isValid(): Boolean = validationErrors().isEmpty()

    /** Keeps the default value for every blank [overrides] entry. */
    fun withOverrides(overrides: Map<String, String>): EndpointConfig {
        fun value(key: String, current: String): String =
            overrides[key]?.trim()?.takeIf { it.isNotEmpty() } ?: current
        return copy(
            githubApiBaseUrl = value("githubApiBaseUrl", githubApiBaseUrl).trimEnd('/'),
            githubWebBaseUrl = value("githubWebBaseUrl", githubWebBaseUrl).trimEnd('/'),
            githubRawBaseUrl = value("githubRawBaseUrl", githubRawBaseUrl).trimEnd('/'),
            githubDeviceCodeUrl = value("githubDeviceCodeUrl", githubDeviceCodeUrl),
            githubAccessTokenUrl = value("githubAccessTokenUrl", githubAccessTokenUrl),
            projectOwner = value("projectOwner", projectOwner),
            projectRepo = value("projectRepo", projectRepo),
            projectBranch = value("projectBranch", projectBranch),
            docsBaseUrl = value("docsBaseUrl", docsBaseUrl).trimEnd('/'),
            modulesBaseUrl = value("modulesBaseUrl", modulesBaseUrl).trimEnd('/'),
            modulesMirrorBaseUrl = value("modulesMirrorBaseUrl", modulesMirrorBaseUrl).trimEnd('/'),
            sampleBundlesBaseUrl = value("sampleBundlesBaseUrl", sampleBundlesBaseUrl).trimEnd('/'),
            sampleBundlesMirrorBaseUrl = value("sampleBundlesMirrorBaseUrl", sampleBundlesMirrorBaseUrl)
                .trimEnd('/')
        )
    }

    /** Overrides that differ from [EndpointConfig.Companion.defaults], for storage. */
    fun differencesFromDefaults(): Map<String, String> {
        val defaults = defaults()
        val values = linkedMapOf(
            "githubApiBaseUrl" to githubApiBaseUrl,
            "githubWebBaseUrl" to githubWebBaseUrl,
            "githubRawBaseUrl" to githubRawBaseUrl,
            "githubDeviceCodeUrl" to githubDeviceCodeUrl,
            "githubAccessTokenUrl" to githubAccessTokenUrl,
            "projectOwner" to projectOwner,
            "projectRepo" to projectRepo,
            "projectBranch" to projectBranch,
            "docsBaseUrl" to docsBaseUrl,
            "modulesBaseUrl" to modulesBaseUrl,
            "modulesMirrorBaseUrl" to modulesMirrorBaseUrl,
            "sampleBundlesBaseUrl" to sampleBundlesBaseUrl,
            "sampleBundlesMirrorBaseUrl" to sampleBundlesMirrorBaseUrl
        )
        return values.filter { (key, value) -> defaults.storedValue(key) != value }
    }

    private fun storedValue(key: String): String = when (key) {
        "githubApiBaseUrl" -> githubApiBaseUrl
        "githubWebBaseUrl" -> githubWebBaseUrl
        "githubRawBaseUrl" -> githubRawBaseUrl
        "githubDeviceCodeUrl" -> githubDeviceCodeUrl
        "githubAccessTokenUrl" -> githubAccessTokenUrl
        "projectOwner" -> projectOwner
        "projectRepo" -> projectRepo
        "projectBranch" -> projectBranch
        "docsBaseUrl" -> docsBaseUrl
        "modulesBaseUrl" -> modulesBaseUrl
        "modulesMirrorBaseUrl" -> modulesMirrorBaseUrl
        "sampleBundlesBaseUrl" -> sampleBundlesBaseUrl
        "sampleBundlesMirrorBaseUrl" -> sampleBundlesMirrorBaseUrl
        else -> ""
    }

    companion object {
        const val DEFAULT_GITHUB_API = "https://api.github.com"
        const val DEFAULT_GITHUB_WEB = "https://github.com"
        const val DEFAULT_GITHUB_RAW = "https://raw.githubusercontent.com"
        const val DEFAULT_GITHUB_DEVICE_CODE = "https://github.com/login/device/code"
        const val DEFAULT_GITHUB_ACCESS_TOKEN = "https://github.com/login/oauth/access_token"

        /**
         * Identity of this project. Not the upstream WebToApp project and not the
         * author's private infrastructure: this is the repository the app is built
         * from, and any fork can replace it from the Studio settings screen.
         */
        const val DEFAULT_PROJECT_OWNER = "Konprostart"
        const val DEFAULT_PROJECT_REPO = "Sherwood-WTA-webtoapp-builder"
        const val DEFAULT_PROJECT_BRANCH = "main"

        const val DEFAULT_DOCS = "https://konprostart.github.io/Sherwood-WTA-webtoapp-builder"
        const val DEFAULT_MODULES =
            "https://raw.githubusercontent.com/Konprostart/Sherwood-WTA-webtoapp-builder/main/modules"
        const val DEFAULT_MODULES_MIRROR =
            "https://cdn.jsdelivr.net/gh/Konprostart/Sherwood-WTA-webtoapp-builder@main/modules"
        const val DEFAULT_SAMPLE_BUNDLES =
            "https://raw.githubusercontent.com/Konprostart/Sherwood-WTA-webtoapp-builder/main/sample-bundles"
        const val DEFAULT_SAMPLE_BUNDLES_MIRROR =
            "https://cdn.jsdelivr.net/gh/Konprostart/Sherwood-WTA-webtoapp-builder@main/sample-bundles"

        const val WORKSPACE_MANIFEST_PATH = ".sherwood/workspace.json"

        fun defaults(): EndpointConfig = EndpointConfig()

        private val OWNER_RE = Regex("^[A-Za-z0-9_-]{1,100}$")
        private val REPO_RE = Regex("^[A-Za-z0-9._-]{1,100}$")
        private val BRANCH_RE = Regex("^[A-Za-z0-9._/-]{1,250}$")

        /**
         * Accepts only an absolute HTTPS URL without credentials, a query or a
         * fragment. GitHub Enterprise installs still use HTTPS, so there is no
         * reason to allow cleartext here.
         */
        fun validateHttpsUrl(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty()) return "Address is required."
            if (!value.startsWith("https://", ignoreCase = true)) return "Address must start with https://"
            val rest = value.removePrefix("https://").removeSuffix("/")
            if (rest.isEmpty()) return "Address is missing a host."
            // `@` is only suspicious in the authority (userinfo). Mirrors such as
            // jsDelivr legitimately carry `@branch` in the path.
            val authority = rest.substringBefore('/')
            if (authority.contains('@')) return "Address must not contain credentials."
            if (rest.contains(' ')) return "Address must not contain spaces."
            if (authority.isEmpty() || !authority.contains('.')) {
                return "Address is missing a valid host."
            }
            if (rest.contains('?')) return "Address must not contain a query string."
            if (rest.contains('#')) return "Address must not contain a fragment."
            return null
        }

        /** Same rules as a git branch: no `..`, no leading/trailing separators, no `.lock`. */
        fun isValidBranchName(value: String): Boolean {
            val branch = value.trim()
            if (!BRANCH_RE.matches(branch)) return false
            if (branch.startsWith("/") || branch.endsWith("/")) return false
            if (branch.startsWith(".") || branch.endsWith(".")) return false
            if (branch.contains("..") || branch.contains("//")) return false
            if (branch.endsWith(".lock")) return false
            return true
        }
    }
}

/**
 * Persists the user's endpoint overrides. Only changed fields are written, so a
 * future default change reaches every user who never overrode that field.
 *
 * Deliberately no token is stored here; GitHub credentials live in
 * [com.sherwood.wta.core.frontend.GitHubTokenStore].
 */
class EndpointSettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): EndpointConfig {
        val stored = prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
        val config = EndpointConfig.defaults().withOverrides(stored)
        return if (config.isValid()) config else EndpointConfig.defaults()
    }

    fun save(config: EndpointConfig): Result<Unit> {
        val errors = config.validationErrors()
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(errors.values.first()))
        }
        val differences = config.differencesFromDefaults()
        val editor = prefs.edit().clear()
        differences.forEach { (key, value) -> editor.putString(key, value) }
        return if (editor.commit()) Result.success(Unit) else {
            Result.failure(IllegalStateException("Could not save endpoint settings"))
        }
    }

    fun reset(): Result<Unit> = if (prefs.edit().clear().commit()) {
        Result.success(Unit)
    } else {
        Result.failure(IllegalStateException("Could not reset endpoint settings"))
    }

    fun endpointFor(field: String): String = when (field) {
        "githubApiBaseUrl" -> EndpointConfig.DEFAULT_GITHUB_API
        "githubWebBaseUrl" -> EndpointConfig.DEFAULT_GITHUB_WEB
        "githubRawBaseUrl" -> EndpointConfig.DEFAULT_GITHUB_RAW
        "githubDeviceCodeUrl" -> EndpointConfig.DEFAULT_GITHUB_DEVICE_CODE
        "githubAccessTokenUrl" -> EndpointConfig.DEFAULT_GITHUB_ACCESS_TOKEN
        "projectOwner" -> EndpointConfig.DEFAULT_PROJECT_OWNER
        "projectRepo" -> EndpointConfig.DEFAULT_PROJECT_REPO
        "projectBranch" -> EndpointConfig.DEFAULT_PROJECT_BRANCH
        "docsBaseUrl" -> EndpointConfig.DEFAULT_DOCS
        "modulesBaseUrl" -> EndpointConfig.DEFAULT_MODULES
        "modulesMirrorBaseUrl" -> EndpointConfig.DEFAULT_MODULES_MIRROR
        "sampleBundlesBaseUrl" -> EndpointConfig.DEFAULT_SAMPLE_BUNDLES
        "sampleBundlesMirrorBaseUrl" -> EndpointConfig.DEFAULT_SAMPLE_BUNDLES_MIRROR
        else -> ""
    }

    companion object {
        private const val TAG = "EndpointSettings"
        private const val PREFS = "sherwood_studio_endpoints"

        @Volatile
        private var INSTANCE: EndpointSettingsStore? = null

        fun getInstance(context: Context): EndpointSettingsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: EndpointSettingsStore(context.applicationContext).also { INSTANCE = it }
            }

        /** Convenience for clients that only need the effective endpoints. */
        fun current(context: Context): EndpointConfig = try {
            getInstance(context).load()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read endpoint settings: ${e.javaClass.simpleName}")
            EndpointConfig.defaults()
        }
    }
}
