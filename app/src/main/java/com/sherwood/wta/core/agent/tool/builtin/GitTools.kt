package com.sherwood.wta.core.agent.tool.builtin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.tool.Tool
import com.sherwood.wta.core.agent.tool.ToolContext
import com.sherwood.wta.core.agent.tool.ToolResult
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.core.studio.WorkspaceService
import com.sherwood.wta.core.studio.git.GitCapabilities
import com.sherwood.wta.core.studio.git.GitCredentialStore
import com.sherwood.wta.core.studio.git.GitProvider
import com.sherwood.wta.core.studio.git.GitProviderId
import com.sherwood.wta.core.studio.git.GitProviderLookup
import com.sherwood.wta.core.studio.git.GitProviderRegistry
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.git.GitStudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Git tools for the in-app agent: one set that works against whichever host the user
 * configured — GitHub, Gitea or Forgejo.
 *
 * They read the credential Studio already owns — [GitCredentialStore], reached through
 * [GitProviderRegistry], filled either by Settings → Git access (personal access token)
 * or by Studio's GitHub OAuth device flow — so there is no second token path and no
 * credential handling inside the agent loop.
 *
 * Boundary: these tools **read** the host and **write only into the agent session
 * sandbox**. Two tools write to the account itself, each behind its own fresh approval
 * tap on every call: [GitCreateRepositoryTool] in `GitCreateRepositoryTool.kt`
 * creates a new, independent repository (never a fork), and [GitOpenChangeRequestTool]
 * in `GitPublishTool.kt` is the only path that sends changes to an existing one — it
 * is bounded to a new branch and a pull request and never merges or force-pushes.
 * Nothing here merges, comments or dispatches a workflow.
 */

/** Session-sandbox limits for an imported repository, so a huge repo cannot fill the disk. */
private const val MAX_IMPORT_FILES = 2_000
private const val MAX_IMPORT_FILE_BYTES = 4L * 1024 * 1024
private const val MAX_IMPORT_TOTAL_BYTES = 32L * 1024 * 1024

/** Advice for a host with no stored token; the wording names the host and where to go. */
internal fun notConnectedMessage(connection: GitConnection): String =
    if (connection.providerId == GitProviderId.GITHUB) {
        """
        GitHub is not connected: no token is stored on this device.
        Connect it in Settings → Git access → "GitHub personal access token" (recommended: a
        fine-grained token limited to the one repository), or connect through Studio →
        Repositories with the OAuth device flow.
        """.trimIndent()
    } else {
        """
        ${connection.providerName} is not connected: no token is stored for ${connection.baseUrl}.
        Add a personal access token in Studio → Settings → Git hosting.
        """.trimIndent()
    }

/** What the stored credential can do, and which repository Studio is pointed at. */
internal data class GitConnection(
    val hasToken: Boolean,
    val login: String?,
    /** Set when a token exists but cannot be used — never silently ignored. */
    val failure: String?,
    val repository: String,
    val branch: String,
    val providerId: GitProviderId,
    val providerName: String,
    val baseUrl: String,
    val capabilities: GitCapabilities?,
    /** True when the host has a publish path in this build. */
    val canPublish: Boolean,
    val provider: GitProvider?
)

/**
 * Resolves the active connection — whatever host it is.
 *
 * The token still comes from the one credential store the Studio owns, so the agent never
 * sees a second path to a secret. Which host answers is decided by the saved provider
 * setting; an unavailable host is reported as a problem instead of falling back to GitHub.
 */
internal suspend fun resolveGitConnection(ctx: ToolContext): GitConnection {
    val context = ctx.androidContext
    val studioSettings = StudioSettingsStore.getInstance(context)
    val endpoints = EndpointSettingsStore.current(context)
    val state = GitStudio.state(context)
    val repository = studioSettings.lastRepository(state.id)
        ?: "${endpoints.projectOwner}/${endpoints.projectRepo}".trim('/')
    val branch = endpoints.projectBranch

    val lookup = GitProviderRegistry.active(context)
    val provider = (lookup as? GitProviderLookup.Available)?.provider
    if (provider == null) {
        val reason = (lookup as? GitProviderLookup.Unavailable)?.reason
        return GitConnection(
            hasToken = state.hasToken,
            login = null,
            failure = "${state.displayName} is not usable in this build ($reason).",
            repository = repository,
            branch = branch,
            providerId = state.id,
            providerName = state.displayName,
            baseUrl = state.baseUrl,
            capabilities = state.capabilities,
            canPublish = state.canPublish,
            provider = null
        )
    }
    if (!state.hasToken) {
        return GitConnection(
            hasToken = false,
            login = null,
            failure = null,
            repository = repository,
            branch = branch,
            providerId = state.id,
            providerName = state.displayName,
            baseUrl = state.baseUrl,
            capabilities = state.capabilities,
            canPublish = state.canPublish,
            provider = provider
        )
    }
    return when (val user = provider.account()) {
        is GitResult.Success -> GitConnection(
            hasToken = true,
            login = user.value.login,
            failure = null,
            repository = repository,
            branch = branch,
            providerId = state.id,
            providerName = state.displayName,
            baseUrl = state.baseUrl,
            capabilities = state.capabilities,
            canPublish = state.canPublish,
            provider = provider
        )
        is GitResult.Failure -> GitConnection(
            hasToken = true,
            login = null,
            failure = user.message
                ?: "${state.displayName} rejected the stored token (${user.kind}).",
            repository = repository,
            branch = branch,
            providerId = state.id,
            providerName = state.displayName,
            baseUrl = state.baseUrl,
            capabilities = state.capabilities,
            canPublish = state.canPublish,
            provider = provider
        )
    }
}

/**
 * Host account names are alphanumeric with single hyphens and may neither start nor end
 * with one; repository names also allow `_` and `.` (`.github` is a real repository).
 * `.` and `..` are refused explicitly: the parsed name becomes a folder inside the session
 * sandbox, so a traversal segment must never be accepted as a repository.
 */
private val OWNER_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
private val REPO_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")

/** Accepts `owner/repo` (optionally `.git`) and rejects anything that is not one. */
internal fun parseOwnerRepo(raw: String?): Pair<String, String>? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val parts = value.split('/')
    if (parts.size != 2) return null
    val owner = parts[0].trim()
    val repo = parts[1].trim().removeSuffix(".git")
    if (!OWNER_PATTERN.matches(owner)) return null
    if (!REPO_PATTERN.matches(repo)) return null
    if (repo == "." || repo == "..") return null
    return owner to repo
}

/** Reads a string argument without throwing on a JSON null or a non-string value. */
internal fun argString(args: JsonObject, key: String): String? {
    val element = args.get(key) ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asString }.getOrNull()
}

/**
 * Folder name inside the session sandbox. It becomes a path segment, so it must be a
 * plain relative name: no separators, no traversal segment, not the root itself.
 */
internal fun sessionFolderName(raw: String?, fallback: String): String =
    raw?.trim()?.trim('/')
        ?.takeIf { candidate ->
            candidate.isNotEmpty() &&
                candidate != "." &&
                candidate != ".." &&
                !candidate.contains("..") &&
                !candidate.contains('/') &&
                !candidate.contains('\\')
        } ?: fallback

class GitStatusTool : Tool {
    override val name = "GitStatus"
    override val description = """
        Report the git host Studio is using — GitHub, Gitea or Forgejo, whichever the user
        configured: whether a token is stored, which account it authenticates as, the
        repository Studio last opened, the local working copies already imported, and
        whether this host can publish in this build.
        Read-only. Call this first — GitImportRepository defaults to the repository
        reported here, and when nothing is connected this explains what the user must do.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {}

    override fun isReadOnly(): Boolean = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val connection = resolveGitConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(notConnectedMessage(connection))

        val workspace = WorkspaceService(ctx.androidContext)
        val imported = withContext(Dispatchers.IO) { workspace.allImportedProjects() }

        return ToolResult.ok(
            buildString {
                appendLine("Host: ${connection.providerName} (${connection.baseUrl})")
                if (connection.login != null) {
                    appendLine("Account: ${connection.login}")
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
                if (connection.capabilities?.createRepository == true) {
                    appendLine(
                        "GitCreateRepository creates a new repository on this account " +
                            "(optionally seeded with a public repository's files) — it also " +
                            "asks for a fresh approval tap."
                    )
                } else {
                    appendLine(
                        "Creating a repository is not available on this host: the server " +
                            "does not expose a create endpoint this build can use."
                    )
                }
                if (!connection.canPublish) {
                    appendLine(
                        "This host has no publish path in this build: opening a pull " +
                            "request and pushing a commit are unavailable."
                    )
                }
                appendLine(
                    "Next: GitImportRepository copies a repository into this session " +
                        "folder, after which Read/Write/Edit/ListFiles/Glob/Grep work on its files."
                )
                if (connection.canPublish) {
                    appendLine(
                        "GitOpenChangeRequest then sends those edits as a NEW branch plus a " +
                            "pull request, with a fresh approval tap on every call."
                    )
                }
            }.trimEnd()
        )
    }
}

class GitListRepositoriesTool : Tool {
    override val name = "GitListRepositories"
    override val description = """
        List the repositories the stored token can see on the configured git host
        (GitHub, Gitea or Forgejo), newest activity first.
        Read-only. Use it to find the repository the user means when GitStatus shows a
        repository that does not exist or that they did not mention.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        integer("limit", "Maximum repositories to list", default = 30)
        boolean("includeForks", "Include forks", default = false)
    }

    override fun isReadOnly(): Boolean = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val connection = resolveGitConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(notConnectedMessage(connection))
        connection.failure?.let { return ToolResult.error(it) }
        val provider = connection.provider
            ?: return ToolResult.error("${connection.providerName} is not usable in this build.")

        val limit = args.get("limit")?.asInt?.coerceIn(1, 100) ?: 30
        val includeForks = args.get("includeForks")?.asBoolean ?: false

        return when (val result = provider.repositories()) {
            is GitResult.Success -> {
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
            is GitResult.Failure ->
                ToolResult.error(result.message ?: "Could not list repositories (${result.kind}).")
        }
    }
}

class GitImportRepositoryTool : Tool {
    override val name = "GitImportRepository"
    override val description = """
        Copy a repository's files from the configured git host (GitHub, Gitea or Forgejo)
        into this chat's working folder so the file tools can read and edit them. Defaults
        to the repository and branch Studio is pointed at (see GitStatus).
        Local only: this downloads files into the app's session storage. It does NOT
        commit, push, open a pull request or change the remote branch. On a host that
        cannot publish, importing is the end of the chain; elsewhere the edits can be sent
        with GitOpenChangeRequest (new branch + pull request) or from Studio's
        Repositories tab.
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
        val connection = resolveGitConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(notConnectedMessage(connection))
        connection.failure?.let { return ToolResult.error(it) }

        val requested = argString(args, "repository")?.takeIf { it.isNotBlank() }
        val parsed = parseOwnerRepo(requested ?: connection.repository)
            ?: return ToolResult.error(
                "GitImportRepository: '${requested ?: connection.repository}' is not " +
                    "an owner/repo identifier. Pass the repository explicitly."
            )
        val (owner, repo) = parsed
        val branch = argString(args, "branch")?.takeIf { it.isNotBlank() } ?: connection.branch
        // The folder becomes a path segment inside the session sandbox, so it must be a
        // plain relative name: no separators, no traversal segment, not the root itself.
        val folder = sessionFolderName(argString(args, "folder"), repo)

        val workspace = WorkspaceService(ctx.androidContext)
        val outcome = withContext(Dispatchers.IO) {
            importSource(workspace, owner, repo, branch, connection.providerId)
        }
        val source = outcome.getOrElse { failure ->
            return ToolResult.error(
                "GitImportRepository: could not fetch $owner/$repo@$branch — " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        }

        val stats = withContext(Dispatchers.IO) {
            copyIntoSession(ctx.fileManager, ctx.sessionId, source, folder)
        }
        if (stats.files == 0) {
            return ToolResult.error(
                "GitImportRepository: $owner/$repo@$branch produced no readable files."
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
                    "Read and edit them with the file tools."
                )
                if (connection.canPublish) {
                    appendLine(
                        "When the change is complete, call GitOpenChangeRequest to push it " +
                            "to a new branch and open a pull request (the user approves " +
                            "each push)."
                    )
                } else {
                    appendLine(
                        "${connection.providerName} has no publish path in this build, so " +
                            "the edits stay in this chat folder; they cannot be pushed from here."
                    )
                }
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
        branch: String,
        providerId: GitProviderId
    ): Result<File> {
        val existing = runCatching { workspace.importedProjects(owner, repo) }
            .getOrDefault(emptyList())
            .firstOrNull { dir ->
                val metadata = workspace.metadata(dir) ?: return@firstOrNull false
                // A same-named repository on another host is a different project.
                metadata.providerId == providerId && metadata.ref == branch
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
