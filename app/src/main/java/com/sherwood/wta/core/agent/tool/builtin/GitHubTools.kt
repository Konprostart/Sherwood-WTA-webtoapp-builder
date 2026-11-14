package com.sherwood.wta.core.agent.tool.builtin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.tool.Tool
import com.sherwood.wta.core.agent.tool.ToolContext
import com.sherwood.wta.core.agent.tool.ToolResult
import com.sherwood.wta.core.frontend.GitHubTokenStore
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.GitHubApiClient
import com.sherwood.wta.core.studio.GitHubResult
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.core.studio.WorkspaceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GitHub tools for the in-app agent.
 *
 * They read the credential Studio already owns — [GitHubTokenStore], filled either by
 * Settings → Git access (personal access token) or by Studio's OAuth device flow — so
 * there is no second token path and no credential handling inside the agent loop.
 *
 * The boundary is narrow on purpose: these tools **read** GitHub and **write only into
 * the agent session sandbox**. Nothing here commits, pushes, merges, opens a pull
 * request or moves a branch. Those stay user-tapped actions on Studio's own screens
 * (`WorkspaceService` / `GitHubApiClient`), and the agent has no path to them.
 */

/** Session-sandbox limits for an imported repository, so a huge repo cannot fill the disk. */
private const val MAX_IMPORT_FILES = 2_000
private const val MAX_IMPORT_FILE_BYTES = 4L * 1024 * 1024
private const val MAX_IMPORT_TOTAL_BYTES = 32L * 1024 * 1024

private const val NOT_CONNECTED = """
GitHub is not connected: no token is stored on this device.
Connect it in Settings → Git access → "GitHub personal access token" (recommended: a
fine-grained token limited to the one repository), or connect through Studio →
Repositories with the OAuth device flow.
""".trimIndent()

/** What the stored credential can do, and which repository Studio is pointed at. */
internal data class GitHubConnection(
    val hasToken: Boolean,
    val login: String?,
    /** Set when a token exists but cannot be used — never silently ignored. */
    val failure: String?,
    val repository: String,
    val branch: String
)

internal suspend fun resolveGitHubConnection(ctx: ToolContext): GitHubConnection {
    val context = ctx.androidContext
    val studioSettings = StudioSettingsStore.getInstance(context)
    val endpoints = EndpointSettingsStore.current(context)
    val repository = studioSettings.lastRepository
        ?: "${endpoints.projectOwner}/${endpoints.projectRepo}".trim('/')

    val hasToken = withContext(Dispatchers.IO) {
        GitHubTokenStore(context).currentToken() != null
    }
    if (!hasToken) {
        return GitHubConnection(false, null, null, repository, endpoints.projectBranch)
    }
    return when (val user = GitHubApiClient(context, endpoints).currentUser()) {
        is GitHubResult.Success ->
            GitHubConnection(true, user.value.login, null, repository, endpoints.projectBranch)
        is GitHubResult.Failure ->
            GitHubConnection(
                hasToken = true,
                login = null,
                failure = user.message ?: "GitHub rejected the stored token (${user.kind}).",
                repository = repository,
                branch = endpoints.projectBranch
            )
    }
}

/** Accepts `owner/repo` (optionally `.git`) and rejects anything that is not one. */
internal fun parseOwnerRepo(raw: String?): Pair<String, String>? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val parts = value.split('/')
    if (parts.size != 2) return null
    val owner = parts[0].trim()
    val repo = parts[1].trim().removeSuffix(".git")
    val allowed = Regex("[A-Za-z0-9_.-]{1,100}")
    if (!owner.matches(allowed) || !repo.matches(allowed)) return null
    return owner to repo
}

class GitHubStatusTool : Tool {
    override val name = "GitHubStatus"
    override val description = """
        Report the GitHub connection Studio is using: whether a token is stored, which
        account it authenticates as, the repository Studio last opened, and the local
        working copies already imported for it.
        Read-only. Call this first — GitHubImportRepository defaults to the repository
        reported here, and when nothing is connected this explains what the user must do.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {}

    override fun isReadOnly(): Boolean = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val connection = resolveGitHubConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(NOT_CONNECTED)

        val workspace = WorkspaceService(ctx.androidContext)
        val imported = withContext(Dispatchers.IO) { workspace.allImportedProjects() }

        return ToolResult.ok(
            buildString {
                if (connection.login != null) {
                    appendLine("GitHub account: ${connection.login}")
                }
                // A token that exists but cannot authenticate is a broken connection, not
                // a working one; say so rather than printing a healthy-looking report.
                connection.failure?.let { appendLine("Problem: $it") }
                appendLine("Repository Studio is pointed at: ${connection.repository}")
                appendLine("Branch: ${connection.branch}")
                if (imported.isEmpty()) {
                    appendLine("Local working copies: none imported yet.")
                } else {
                    appendLine("Local working copies:")
                    imported.take(10).forEach { (_, metadata) ->
                        appendLine(
                            "- ${metadata.owner}/${metadata.repo}@${metadata.ref}" +
                                (metadata.subPath?.let { " (subfolder $it)" } ?: "")
                        )
                    }
                    if (imported.size > 10) appendLine("… ${imported.size - 10} more")
                }
                appendLine(
                    "Next: GitHubImportRepository copies a repository into this session " +
                        "folder, after which Read/Write/Edit/ListFiles/Glob/Grep work on its files."
                )
            }.trimEnd()
        )
    }
}

class GitHubListRepositoriesTool : Tool {
    override val name = "GitHubListRepositories"
    override val description = """
        List the GitHub repositories the stored token can see, newest activity first.
        Read-only. Use it to find the repository the user means when GitHubStatus shows a
        repository that does not exist or that they did not mention.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        integer("limit", "Maximum repositories to list", default = 30)
        boolean("includeForks", "Include forks", default = false)
    }

    override fun isReadOnly(): Boolean = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val connection = resolveGitHubConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(NOT_CONNECTED)
        connection.failure?.let { return ToolResult.error(it) }

        val limit = args.get("limit")?.asInt?.coerceIn(1, 100) ?: 30
        val includeForks = args.get("includeForks")?.asBoolean ?: false

        val api = GitHubApiClient(ctx.androidContext, EndpointSettingsStore.current(ctx.androidContext))
        return when (val result = api.listRepositories()) {
            is GitHubResult.Success -> {
                val repos = result.value.filter { includeForks || !it.fork }
                if (repos.isEmpty()) {
                    ToolResult.ok("The token cannot see any repositories.")
                } else {
                    val rows = repos.take(limit).joinToString("\n") { repo ->
                        buildString {
                            append("- ${repo.fullName}")
                            if (repo.privateRepo) append(" [private]")
                            if (repo.fork) append(" [fork]")
                            append(" default branch: ${repo.defaultBranch}")
                            repo.pushedAt?.let { append(" last push: $it") }
                        }
                    }
                    val tail =
                        if (repos.size > limit) "\n… ${repos.size - limit} more (raise `limit`)" else ""
                    ToolResult.ok("${repos.size} repositories:\n$rows$tail")
                }
            }
            is GitHubResult.Failure ->
                ToolResult.error(result.message ?: "Could not list repositories (${result.kind}).")
        }
    }
}

class GitHubImportRepositoryTool : Tool {
    override val name = "GitHubImportRepository"
    override val description = """
        Copy a GitHub repository's files into this chat's working folder so the file tools
        can read and edit them. Defaults to the repository and branch Studio is pointed at
        (see GitHubStatus).
        Local only: this downloads files into the app's session storage. It does NOT
        commit, push, open a pull request or change the remote branch — the user does that
        from Studio's Repositories tab.
        Re-running replaces previously imported files of the same folder. Large
        repositories are capped (2000 files / 32 MB, 4 MB per file).
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string("repository", "owner/repo. Omit to use the repository Studio is pointed at.")
        string("branch", "Branch to import. Omit to use the default branch.")
        string("folder", "Folder inside this chat's working folder. Omit to use the repository name.")
    }

    override fun isReadOnly(): Boolean = false

    override fun activityDescription(args: JsonObject): String? {
        val repo = args.get("repository")?.asString?.takeIf { it.isNotBlank() }
        return repo?.let { "Importing $it" } ?: "Importing the connected repository"
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val connection = resolveGitHubConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(NOT_CONNECTED)
        connection.failure?.let { return ToolResult.error(it) }

        val requested = args.get("repository")?.asString?.takeIf { it.isNotBlank() }
        val parsed = parseOwnerRepo(requested ?: connection.repository)
            ?: return ToolResult.error(
                "GitHubImportRepository: '${requested ?: connection.repository}' is not " +
                    "an owner/repo identifier. Pass the repository explicitly."
            )
        val (owner, repo) = parsed
        val branch = args.get("branch")?.asString?.takeIf { it.isNotBlank() } ?: connection.branch
        val folder = args.get("folder")?.asString?.trim()?.trim('/')
            ?.takeIf { it.isNotEmpty() && !it.contains("..") } ?: repo

        val workspace = WorkspaceService(ctx.androidContext)
        val outcome = withContext(Dispatchers.IO) { importSource(workspace, owner, repo, branch) }
        val source = outcome.getOrElse { failure ->
            return ToolResult.error(
                "GitHubImportRepository: could not fetch $owner/$repo@$branch — " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        }

        val stats = withContext(Dispatchers.IO) {
            copyIntoSession(ctx.fileManager, ctx.sessionId, source, folder)
        }
        if (stats.files == 0) {
            return ToolResult.error(
                "GitHubImportRepository: $owner/$repo@$branch produced no readable files."
            )
        }

        return ToolResult.ok(
            buildString {
                appendLine("Imported $owner/$repo@$branch into '$folder/' in this chat's working folder.")
                appendLine("Files: ${stats.files} (${stats.bytes / 1024} KB)")
                if (stats.tooLarge > 0) {
                    appendLine("Skipped ${stats.tooLarge} file(s) larger than 4 MB.")
                }
                if (stats.truncated) {
                    appendLine("Stopped at the import cap — the rest of the repository is not copied.")
                }
                stats.samplePaths.take(20).forEach { appendLine("  $folder/$it") }
                if (stats.samplePaths.size > 20) appendLine("  …")
                appendLine(
                    "Read and edit them with the file tools. Changes stay local — commit and " +
                        "push from Studio → Repositories when the user asks for it."
                )
            }.trimEnd()
        )
    }

    /**
     * Reuses the newest Studio working copy of the same repository/branch when one
     * exists (no duplicate download), and fetches a fresh one otherwise.
     */
    private suspend fun importSource(
        workspace: WorkspaceService,
        owner: String,
        repo: String,
        branch: String
    ): Result<File> {
        val existing = runCatching { workspace.importedProjects(owner, repo) }
            .getOrDefault(emptyList())
            .firstOrNull { dir ->
                val metadata = workspace.metadata(dir) ?: return@firstOrNull false
                metadata.ref == branch
            }
        if (existing != null) return Result.success(existing)

        return workspace.clone(owner, repo, branch).mapCatching { fetched ->
            File(fetched.localPath)
        }
    }
}

/** What one repository copy produced. */
internal class ImportStats {
    var files = 0
    var bytes = 0L
    var tooLarge = 0
    var truncated = false
    val samplePaths = mutableListOf<String>()
}

/**
 * Copies [source] into the session sandbox through [ProjectFileManager], which is the
 * only writer allowed to name a path there. Symlinks that resolve outside the source
 * tree are skipped, and `.git` is never imported.
 */
internal fun copyIntoSession(
    fileManager: ProjectFileManager,
    sessionId: String,
    source: File,
    folder: String
): ImportStats {
    val stats = ImportStats()
    val canonicalRoot = runCatching { source.canonicalPath }.getOrNull() ?: return stats
    val pending = ArrayDeque<Pair<File, String>>()
    pending.addLast(source to "")

    while (pending.isNotEmpty()) {
        val (directory, prefix) = pending.removeLast()
        val children = directory.listFiles() ?: continue
        for (child in children) {
            if (stats.files >= MAX_IMPORT_FILES || stats.bytes >= MAX_IMPORT_TOTAL_BYTES) {
                stats.truncated = true
                return stats
            }
            if (child.name == ".git") continue
            val childRelative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val canonical = runCatching { child.canonicalPath }.getOrNull() ?: continue
            if (!canonical.startsWith(canonicalRoot + File.separator)) continue
            if (child.isDirectory) {
                pending.addLast(child to childRelative)
                continue
            }
            if (!child.isFile) continue
            val size = child.length()
            if (size > MAX_IMPORT_FILE_BYTES) {
                stats.tooLarge++
                continue
            }
            if (stats.bytes + size > MAX_IMPORT_TOTAL_BYTES) {
                stats.truncated = true
                return stats
            }
            val written = child.inputStream().use { stream ->
                fileManager.writeStream(sessionId, "$folder/$childRelative", stream)
            }
            if (written == null) continue
            stats.files++
            stats.bytes += size
            if (stats.samplePaths.size < 200) stats.samplePaths += childRelative
        }
    }
    return stats
}
