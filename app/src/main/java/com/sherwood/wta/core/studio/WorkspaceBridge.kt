package com.sherwood.wta.core.studio

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.sherwood.wta.core.frontend.GitHubUrlPolicy
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The file a repository carries so an external workspace (for example a ChatGPT
 * workspace with GitHub access) can understand this project without calling any
 * Sherwood server.
 *
 * This is a documented, versioned contract, not a proprietary API:
 * `GET` the file over `raw.githubusercontent.com`, edit code, open a branch or a
 * pull request; the Studio's inbox picks it up and the Build Studio can build it.
 */
data class WorkspaceManifest(
    val schema: Int = SCHEMA,
    val project: String,
    val branch: String,
    val packageName: String? = null,
    val buildWorkflow: String? = null,
    val appVersion: String? = null,
    val generatedBy: String = GENERATOR,
    val updatedAt: String = now(),
    val notes: String? = null
) {
    fun isValid(): Boolean {
        if (schema != SCHEMA) return false
        if (project.count { it == '/' } != 1) return false
        if (branch.isBlank() || branch.any { it.isWhitespace() }) return false
        return true
    }

    companion object {
        const val SCHEMA = 1
        const val GENERATOR = "Sherwood Studio"
        const val DEFAULT_WORKFLOW = "release.yml"

        fun now(): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date())
        }
    }
}

/**
 * Reads and writes the workspace manifest, and produces the human-readable
 * instructions a user pastes into their workspace tool.
 *
 * The manifest is written next to the project (`repo_bridge.json` stays the
 * import provenance) and is meant to be committed on a branch. No credentials are
 * ever part of it.
 */
class WorkspaceBridgeService(private val context: Context) {

    private val gson = Gson()

    fun localManifestFile(projectDir: File): File =
        File(projectDir, MANIFEST_PATH)

    fun readLocal(projectDir: File): WorkspaceManifest? {
        val file = localManifestFile(projectDir)
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), WorkspaceManifest::class.java)
                ?.takeIf { it.isValid() }
        }.getOrNull()
    }

    fun writeLocal(projectDir: File, manifest: WorkspaceManifest): Result<File> {
        if (!manifest.isValid()) {
            return Result.failure(IllegalArgumentException("Invalid workspace manifest"))
        }
        return runCatching {
            val target = localManifestFile(projectDir)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(gson.toJson(manifest), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Could not write ${target.name}" }
            }
            target
        }
    }

    /** Builds a manifest for the imported project, including package/version when known. */
    fun manifestFor(
        projectDir: File,
        metadata: com.sherwood.wta.core.frontend.RepoBridgeMetadata,
        packageName: String? = null,
        appVersion: String? = null,
        workflow: String? = null
    ): WorkspaceManifest = WorkspaceManifest(
        project = "${metadata.owner}/${metadata.repo}",
        branch = metadata.ref,
        packageName = packageName,
        buildWorkflow = workflow,
        appVersion = appVersion
    )

    /** Reads `.sherwood/workspace.json` from the branch head over raw content. */
    suspend fun readRemote(
        owner: String,
        repo: String,
        branch: String,
        endpoints: EndpointConfig
    ): WorkspaceManifest? = withContext(Dispatchers.IO) {
        val url = endpoints.workspaceManifestUrl(owner, repo, branch)
        try {
            GitHubUrlPolicy.requireHttps(url)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                runCatching {
                    val element = JsonParser.parseString(body).takeIf { it.isJsonObject }
                        ?: return@runCatching null
                    gson.fromJson(element, WorkspaceManifest::class.java)?.takeIf { it.isValid() }
                }.getOrNull()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read remote manifest: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * The instructions shown on the Workspace card. Written in English because it is
     * a protocol document that the user pastes into another tool, but it explains the
     * no-server contract in full: branch, commit, pull request, build.
     */
    fun instructions(ownerRepo: String): String = """
        # Sherwood Studio workspace contract

        Repository: $ownerRepo

        Sherwood Studio talks to a workspace only through GitHub. There is no
        Sherwood server, account or API key involved.

        1. Read `.sherwood/workspace.json` from the branch you work on. It names the
           project, branch, package name and build workflow.
        2. Create a branch, commit changes and open a pull request against the
           default branch. Do not push secrets, keystores or `local.properties`.
        3. Sherwood Studio shows the branch and the pull request in its Workspace
           Inbox, where it can be reviewed, merged and built.
        4. Building is done by `.github/workflows/` in the repository itself
           (GitHub Actions). Sherwood Studio only starts the workflow and downloads
           the resulting APK/AAB artifact.
        5. Every downloaded artifact is hashed (SHA-256) and stored in the local
           vault with its source commit and run id.

        The manifest format is version 1:
        {
          "schema": 1,
          "project": "owner/repo",
          "branch": "main",
          "packageName": "com.example.app",
          "buildWorkflow": "release.yml",
          "appVersion": "1.0.0",
          "generatedBy": "Sherwood Studio",
          "updatedAt": "2026-01-01T00:00:00Z"
        }
    """.trimIndent()

    private val client: OkHttpClient by lazy {
        NetworkModule.downloadClient.newBuilder().followSslRedirects(false).build()
    }

    companion object {
        private const val TAG = "WorkspaceBridge"
        const val MANIFEST_PATH = ".sherwood/workspace.json"
    }
}
