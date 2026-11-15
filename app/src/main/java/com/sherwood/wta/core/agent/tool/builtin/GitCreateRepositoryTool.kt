package com.sherwood.wta.core.agent.tool.builtin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sherwood.wta.core.agent.tool.Tool
import com.sherwood.wta.core.agent.tool.ToolContext
import com.sherwood.wta.core.agent.tool.ToolResult
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import com.sherwood.wta.core.studio.WorkspaceService
import com.sherwood.wta.core.studio.git.GitFileChange
import com.sherwood.wta.core.studio.git.GitProvider
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.git.NewGitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates a brand-new repository on the connected account, optionally seeded with the
 * files of a public repository on the same host.
 *
 * Two rules are enforced here in code rather than by prompting the model:
 *
 * 1. **A fresh tap per creation.** [requiresFreshApproval] is true, so the permission
 *    dialog asks on every call, never offers "Always allow", and Auto-approve does not
 *    skip it — the same one-shot gate the pull-request tool uses.
 * 2. **An independent repository, never a fork.** The only create call is the host's
 *    "create repository" endpoint; no fork or template endpoint is ever used, so the
 *    result has no upstream link. When a source is copied, only its *files* are committed
 *    on top of the initial commit — no history, no fork relationship.
 *
 * Ordering is deliberate: the source is validated and its whole tree is measured
 * **before** the repository is created, so the common failures (source missing, source
 * private while the target is public, copy over a cap) never leave a stray repository
 * behind.
 */

/** Copy caps. Exceeding any of them refuses the copy instead of uploading a partial tree. */
internal const val MAX_COPY_FILES = 500
internal const val MAX_COPY_FILE_BYTES = 10L * 1024 * 1024
internal const val MAX_COPY_TOTAL_BYTES = 64L * 1024 * 1024

/** The host's own limit for a repository description. */
internal const val MAX_REPOSITORY_DESCRIPTION = 350

/** The repository-name alphabet every supported host accepts. */
private val REPOSITORY_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")

/**
 * Accepts [raw] as a repository name, or returns why it cannot be one.
 *
 * The name can become a folder inside the app later (an import names its working copy
 * after the repository), so `.` and `..` are refused on top of the host's own rules.
 */
internal fun repositoryNameProblem(raw: String?): String? {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty()) return "the name is empty"
    if (name.length > 100) return "the name is longer than the 100-character limit"
    if (!REPOSITORY_NAME_PATTERN.matches(name)) {
        return "the name may only contain letters, digits, '.', '_' and '-'"
    }
    if (name == "." || name == "..") return "'$name' is not a repository name"
    if (name.contains("..")) return "the name may not contain '..'"
    if (name.none { it.isLetterOrDigit() }) return "the name needs at least one letter or digit"
    return null
}

/** Accepts [raw] as a repository description, or returns why it cannot be one. */
internal fun repositoryDescriptionProblem(raw: String?): String? {
    if (raw == null) return null
    val description = raw.trim()
    if (description.length > MAX_REPOSITORY_DESCRIPTION) {
        return "the description is ${description.length} characters; at most " +
            "$MAX_REPOSITORY_DESCRIPTION are allowed"
    }
    if (description.any { it == '\n' || it == '\r' }) {
        return "the description must be a single line"
    }
    return null
}

/** Who may see the new repository. Private is the default: never publish by accident. */
internal enum class RepositoryVisibility {
    PUBLIC,
    PRIVATE;

    val isPrivate: Boolean get() = this == PRIVATE
}

/** Parses `visibility`; null means the value is not one of the two accepted words. */
internal fun parseRepositoryVisibility(raw: String?): RepositoryVisibility? =
    when (raw?.trim()?.lowercase()) {
        null, "" -> RepositoryVisibility.PRIVATE
        "private" -> RepositoryVisibility.PRIVATE
        "public" -> RepositoryVisibility.PUBLIC
        else -> null
    }

/** One file measured for the copy. */
internal data class SourceFile(val path: String, val file: File, val size: Long)

/**
 * The measured source tree. [refusal] is set when a cap is exceeded — the copy is
 * refused rather than truncated, because a partial project would look complete.
 */
internal class SourceScan {
    val files = mutableListOf<SourceFile>()
    var bytes = 0L
    var skippedWorkflows = 0
    var skippedSymlinks = 0
    var refusal: String? = null
}

/**
 * The caps a copy is measured against. They default to the publisher's own ceiling and
 * are narrowed to the host's publish limits by the tool, so a copy is refused before it
 * is downloaded rather than after the host rejects it.
 */
internal data class CopyLimits(
    val maxFiles: Int = MAX_COPY_FILES,
    val maxFileBytes: Long = MAX_COPY_FILE_BYTES,
    val maxBytes: Long = MAX_COPY_TOTAL_BYTES
)

/** Working-copy plumbing that is not repository content. */
internal fun isExcludedCopyPath(relative: String): Boolean {
    if (relative == RepoBridgeMetadataStore.FILE_NAME) return true
    if (relative == "${RepoBridgeMetadataStore.FILE_NAME}.tmp") return true
    return relative.split('/').any { it == ".git" || it == ".changes" }
}

/**
 * A CI definition is never copied from a source project.
 *
 * Two reasons, both structural: publishing a workflow file needs a token with the
 * workflow scope on GitHub (a fine-grained token without it fails the whole copy), and an
 * unknown public repository's pipeline would start running with the user's repository and
 * secrets on the next push. This covers every supported host, not only GitHub: a copied
 * `.gitea/workflows` file would run on Gitea just the same.
 */
internal fun isWorkflowCopyPath(relative: String): Boolean =
    relative == ".github/workflows" || relative.startsWith(".github/workflows/") ||
        relative == ".gitea/workflows" || relative.startsWith(".gitea/workflows/") ||
        relative == ".forgejo/workflows" || relative.startsWith(".forgejo/workflows/") ||
        relative == ".gitlab-ci.yml"

/**
 * Measures every file under [root] that the copy would upload, applying [limits].
 *
 * Symlinks that resolve outside [root] are skipped; the walk stops at the first cap it
 * exceeds and records a refusal.
 */
internal fun scanSourceTree(root: File, limits: CopyLimits = CopyLimits()): SourceScan {
    val scan = SourceScan()
    val canonicalRoot = runCatching { root.canonicalPath }.getOrNull()
    if (canonicalRoot == null) {
        scan.refusal = "the downloaded copy cannot be read"
        return scan
    }
    val pending = ArrayDeque<Pair<File, String>>()
    pending.addLast(root to "")
    while (pending.isNotEmpty()) {
        val (directory, prefix) = pending.removeLast()
        val children = directory.listFiles() ?: continue
        for (child in children) {
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (isExcludedCopyPath(relative)) continue
            val canonical = runCatching { child.canonicalPath }.getOrNull() ?: continue
            if (!canonical.startsWith(canonicalRoot + File.separator)) {
                scan.skippedSymlinks++
                continue
            }
            if (child.isDirectory) {
                pending.addLast(child to relative)
                continue
            }
            if (!child.isFile) continue
            if (isWorkflowCopyPath(relative)) {
                scan.skippedWorkflows++
                continue
            }
            val size = child.length()
            if (size > limits.maxFileBytes) {
                scan.refusal = "'$relative' is ${size / (1024 * 1024)} MB; a single file may " +
                    "be at most ${limits.maxFileBytes / (1024 * 1024)} MB"
                return scan
            }
            if (scan.files.size >= limits.maxFiles) {
                scan.refusal = "the repository has more than ${limits.maxFiles} files"
                return scan
            }
            if (scan.bytes + size > limits.maxBytes) {
                scan.refusal = "the files add up to more than " +
                    "${limits.maxBytes / (1024 * 1024)} MB"
                return scan
            }
            scan.files += SourceFile(relative, child, size)
            scan.bytes += size
        }
    }
    scan.files.sortBy { it.path }
    return scan
}

/** A validated source, ready to be uploaded after the repository exists. */
private data class PreparedSource(
    val owner: String,
    val repo: String,
    val branch: String,
    val scan: SourceScan
)

private sealed class SourcePreparation {
    data class Ready(val source: PreparedSource) : SourcePreparation()
    data class Failure(val message: String) : SourcePreparation()
}

class GitCreateRepositoryTool : Tool {

    override val name = "GitCreateRepository"

    override val description = """
        Create a NEW, independent repository on the connected git host (GitHub, Gitea or
        Forgejo) — no need to open the website. The repository is never a fork: it has no
        upstream link to any other project. The initial commit is created automatically,
        so its default branch exists immediately and can be imported or used as a
        pull-request base.
        The user approves with a fresh tap on every call; Auto-approve does not skip it.
        Optional `sourceRepository` ("owner/repo", on the SAME host) seeds the new
        repository with a copy of a PUBLIC repository's files. That is the rebrand path:
        create the copy, then call GitImportRepository for it, change the app name /
        package name / logo, and send the result with GitOpenChangeRequest.
        The copy carries files only — no commit history, no fork link, and no CI
        definition (.github/workflows, .gitea/workflows, .forgejo/workflows,
        .gitlab-ci.yml): a copied pipeline could run with the user's credentials.
        A private source is refused when the new repository would be public.
        Copy caps, refused rather than truncated: ${MAX_COPY_FILES} files,
        ${MAX_COPY_FILE_BYTES / (1024 * 1024)} MB per file and
        ${MAX_COPY_TOTAL_BYTES / (1024 * 1024)} MB in total (narrower on hosts that accept
        less in one commit).
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string(
            "name",
            "Repository name: letters, digits, '.', '_' and '-', at most 100 characters.",
            required = true
        )
        enum(
            "visibility",
            listOf("private", "public"),
            "Who can see the repository. Defaults to private — make it public only when " +
                "the user asked for that explicitly."
        )
        string("description", "One-line repository description (at most 350 characters).")
        string(
            "sourceRepository",
            "owner/repo on the connected host of a PUBLIC repository whose files seed the " +
                "new repository. Omit to create an empty repository with only the initial " +
                "README."
        )
        string(
            "sourceBranch",
            "Branch of `sourceRepository` to copy. Omit to use its default branch."
        )
    }

    override fun isReadOnly(): Boolean = false

    /** A repository is created on a remote account: one fresh tap per call. */
    override fun requiresFreshApproval(): Boolean = true

    override fun activityDescription(args: JsonObject): String? {
        val name = argString(args, "name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Create a new git repository"
        val visibility = parseRepositoryVisibility(argString(args, "visibility"))
            ?: RepositoryVisibility.PRIVATE
        val source = argString(args, "sourceRepository")?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Create ")
            append(if (visibility.isPrivate) "a private" else "a PUBLIC")
            append(" git repository \"$name\"")
            if (source != null) append(" with a copy of $source")
            else append(".")
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val connection = resolveGitConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(notConnectedMessage(connection))
        connection.failure?.let { return ToolResult.error(it) }
        val provider = connection.provider
            ?: return ToolResult.error("${connection.providerName} is not usable in this build.")
        if (connection.capabilities?.createRepository != true) {
            return ToolResult.error(
                "${connection.providerName} cannot create a repository from this build. " +
                    "Create it on the server, then use GitImportRepository."
            )
        }

        val name = argString(args, "name")?.trim().orEmpty()
        repositoryNameProblem(name)?.let { problem ->
            return ToolResult.error("GitCreateRepository: cannot create '$name' — $problem.")
        }

        val description = argString(args, "description")
        repositoryDescriptionProblem(description)?.let { problem ->
            return ToolResult.error("GitCreateRepository: $problem.")
        }

        val visibility = parseRepositoryVisibility(argString(args, "visibility"))
            ?: return ToolResult.error(
                "GitCreateRepository: `visibility` must be 'private' or 'public'."
            )

        val sourceInput = argString(args, "sourceRepository")?.trim()?.takeIf { it.isNotEmpty() }
        val source = sourceInput?.let { raw ->
            parseOwnerRepo(raw) ?: return ToolResult.error(
                "GitCreateRepository: '$raw' is not an owner/repo identifier."
            )
        }
        val account = connection.login
        if (source != null && account != null &&
            source.first.equals(account, ignoreCase = true) &&
            source.second.equals(name, ignoreCase = true)
        ) {
            return ToolResult.error(
                "GitCreateRepository: '$account/$name' is the repository being created — " +
                    "it cannot also be the source."
            )
        }

        // The copy is committed through the same publisher a normal push uses, so its
        // ceiling is the host's, not a number guessed here.
        val writer = provider.commitWriter()
            ?: return ToolResult.error(
                "GitCreateRepository: ${connection.providerName} has no publish path in " +
                    "this build, so a source cannot be copied into the new repository."
            )
        val copyLimits = CopyLimits(
            maxFiles = minOf(MAX_COPY_FILES, writer.publishLimits.maxFiles),
            maxFileBytes = minOf(MAX_COPY_FILE_BYTES, writer.publishLimits.maxFileBytes),
            maxBytes = minOf(MAX_COPY_TOTAL_BYTES, writer.publishLimits.maxBytes)
        )

        // Validate and measure the source before anything is created, so a refused source
        // never leaves a stray repository behind.
        val prepared = if (source == null) {
            null
        } else {
            when (
                val result = prepareSource(
                    ctx = ctx,
                    provider = provider,
                    owner = source.first,
                    repo = source.second,
                    branch = argString(args, "sourceBranch")?.trim()?.takeIf { it.isNotEmpty() },
                    visibility = visibility,
                    limits = copyLimits
                )
            ) {
                is SourcePreparation.Failure ->
                    return ToolResult.error("GitCreateRepository: ${result.message}")
                is SourcePreparation.Ready -> result.source
            }
        }

        val repository = when (
            val created = provider.createRepository(
                NewGitRepository(
                    name = name,
                    description = description?.trim()?.takeIf { it.isNotEmpty() },
                    privateRepository = visibility.isPrivate,
                    autoInit = true
                )
            )
        ) {
            is GitResult.Success -> created.value
            is GitResult.Failure -> return ToolResult.error(
                buildString {
                    append("GitCreateRepository: ${connection.providerName} refused to create ")
                    append("'$name' — ")
                    append(created.message ?: created.kind.name)
                    append('.')
                    if (created.kind == com.sherwood.wta.core.studio.git.GitFailureKind.FORBIDDEN ||
                        created.kind == com.sherwood.wta.core.studio.git.GitFailureKind.NOT_FOUND
                    ) {
                        // The usual cause is a token that may not create repositories:
                        // a fine-grained GitHub token needs that permission, a classic one
                        // the `repo` scope; a Gitea token needs the repository scope.
                        append(
                            " The stored token may not create repositories on this " +
                                "account — check its permissions in Settings → Git access."
                        )
                    }
                    append(" Nothing was copied.")
                }
            )
        }

        val copied = if (prepared == null) {
            null
        } else {
            when (val outcome = copyIntoRepository(provider, repository, prepared, writer)) {
                is CopyOutcome.Failure -> return ToolResult.error(outcome.message)
                is CopyOutcome.Success -> outcome
            }
        }

        return ToolResult.ok(
            summary(
                providerName = connection.providerName,
                baseUrl = connection.baseUrl,
                repository = repository,
                visibility = visibility,
                source = prepared,
                copied = copied
            )
        )
    }

    /** Rejects an unusable or unsafe source, then download and measure it. */
    private suspend fun prepareSource(
        ctx: ToolContext,
        provider: GitProvider,
        owner: String,
        repo: String,
        branch: String?,
        visibility: RepositoryVisibility,
        limits: CopyLimits
    ): SourcePreparation {
        val repoRef = GitRepoRef.of(owner, repo)
            ?: return SourcePreparation.Failure("'$owner/$repo' is not a valid repository.")
        val sourceRepository = when (val fetched = provider.repository(repoRef)) {
            is GitResult.Success -> fetched.value
            is GitResult.Failure -> return SourcePreparation.Failure(
                "could not read the source repository $owner/$repo — " +
                    (fetched.message ?: fetched.kind.name) + "."
            )
        }
        // Copying a private project's files into a public repository would publish it.
        if (sourceRepository.privateRepo && !visibility.isPrivate) {
            return SourcePreparation.Failure(
                "$owner/$repo is private, so its files cannot be copied into a public " +
                    "repository. Create the new repository as private, or pick a public source."
            )
        }

        val workspace = WorkspaceService(ctx.androidContext)
        val downloaded = withContext(Dispatchers.IO) { workspace.clone(owner, repo, branch) }
            .getOrElse { failure ->
                return SourcePreparation.Failure(
                    "could not download $owner/$repo" +
                        (branch?.let { "@$it" } ?: "") + " — " +
                        (failure.message ?: failure.javaClass.simpleName) + "."
                )
            }

        val scan = withContext(Dispatchers.IO) {
            scanSourceTree(File(downloaded.localPath), limits)
        }
        scan.refusal?.let { refusal ->
            return SourcePreparation.Failure(
                "$owner/$repo cannot be copied — $refusal. Create an empty repository " +
                    "instead, or copy a smaller project."
            )
        }
        if (scan.files.isEmpty()) {
            return SourcePreparation.Failure(
                "$owner/$repo@${downloaded.branch} has no files to copy (only excluded paths)."
            )
        }
        return SourcePreparation.Ready(
            PreparedSource(
                owner = owner,
                repo = repo,
                branch = downloaded.branch,
                scan = scan
            )
        )
    }

    /**
     * Commits the prepared files on top of the repository's initial commit.
     *
     * The copy goes through the host's publisher, so a single-step host (Gitea/Forgejo)
     * writes one atomic commit and a staged host (GitHub) builds every blob before the
     * tree and the commit. Either way a failure while reading or uploading leaves the
     * branch at the initial commit: no half-applied project.
     *
     * The files are uploaded with `upsert`, because the automatically created README may
     * share a path with a file from the copied project; the repository was created a
     * moment ago, so there is no third-party content to protect.
     */
    private suspend fun copyIntoRepository(
        provider: GitProvider,
        repository: com.sherwood.wta.core.studio.git.GitRepository,
        source: PreparedSource,
        writer: com.sherwood.wta.core.studio.git.GitCommitWriter
    ): CopyOutcome {
        val owner = repository.owner
        val repo = repository.name
        val branch = repository.defaultBranch
        val url = provider.baseUrl.trimEnd('/') + "/$owner/$repo"
        val repoRef = GitRepoRef.of(owner, repo)
            ?: return CopyOutcome.Failure("GitCreateRepository: created $url but its name is not usable.")

        val head = when (val branchResult = provider.branch(repoRef, branch)) {
            is GitResult.Success -> branchResult.value.sha
            is GitResult.Failure -> return CopyOutcome.Failure(
                "GitCreateRepository: created $url but could not read its '$branch' " +
                    "branch (${branchResult.kind}). The repository holds only the initial " +
                    "commit; the copy did not start."
            )
        }

        val changes = ArrayList<GitFileChange>(source.scan.files.size)
        for (file in source.scan.files) {
            val content = runCatching { file.file.readBytes() }.getOrElse { failure ->
                return CopyOutcome.Failure(
                    "GitCreateRepository: created $url but '${file.path}' cannot be read " +
                        "(${failure.javaClass.simpleName}). Nothing was committed, so the " +
                        "repository still holds only the initial commit."
                )
            }
            changes += GitFileChange(
                path = file.path,
                content = content,
                // A fresh repository: the copied README may replace the generated one.
                upsert = true
            )
        }

        val published = when (
            val result = writer.publishToBranch(
                ref = repoRef,
                branch = branch,
                expectedBaseSha = head,
                message = "Import files from ${source.owner}/${source.repo}",
                changes = changes
            )
        ) {
            is GitResult.Success -> result.value
            is GitResult.Failure -> return CopyOutcome.Failure(
                "GitCreateRepository: created $url but the copy failed — " +
                    (result.message ?: result.kind.name) + ". The repository still holds " +
                    "only the initial commit."
            )
        }

        return CopyOutcome.Success(
            files = source.scan.files.size,
            bytes = source.scan.bytes,
            skippedWorkflows = source.scan.skippedWorkflows,
            initialCommitSha = head,
            commitSha = published.commitSha
        )
    }

    private fun summary(
        providerName: String,
        baseUrl: String,
        repository: com.sherwood.wta.core.studio.git.GitRepository,
        visibility: RepositoryVisibility,
        source: PreparedSource?,
        copied: CopyOutcome.Success?
    ): String = buildString {
        val url = "${baseUrl.trimEnd('/')}/${repository.fullName}"
        appendLine(
            "Created ${repository.fullName} on $providerName  " +
                "(${if (visibility.isPrivate) "private" else "public"})"
        )
        appendLine("Independent repository: no fork and no upstream link.")
        appendLine("Default branch: ${repository.defaultBranch} (initial commit present)")
        if (source != null && copied != null) {
            appendLine()
            appendLine(
                "Copied ${copied.files} file(s) (${copied.bytes / 1024} KB) from " +
                    "${source.owner}/${source.repo}@${source.branch}."
            )
            appendLine("Commit: ${copied.commitSha} (on top of ${copied.initialCommitSha.take(8)})")
            if (copied.skippedWorkflows > 0) {
                appendLine(
                    "Not copied: ${copied.skippedWorkflows} CI workflow file(s) — a copied " +
                        "pipeline would run with the user's credentials. Add CI deliberately " +
                        "when the project needs it."
                )
            }
            appendLine(
                "History was not copied: the repository holds the initial commit plus one " +
                    "import commit."
            )
        }
        appendLine()
        appendLine("Next:")
        appendLine(
            "1. GitImportRepository with repository \"${repository.fullName}\" and " +
                "branch \"${repository.defaultBranch}\" to edit the files in this chat."
        )
        appendLine("2. Change the app name, package name and logo.")
        appendLine(
            "3. GitOpenChangeRequest to send the result as a new branch plus a pull " +
                "request (the user approves that push separately)."
        )
        append("Repository: $url")
    }.trimEnd()
}

private sealed class CopyOutcome {
    data class Success(
        val files: Int,
        val bytes: Long,
        val skippedWorkflows: Int,
        val initialCommitSha: String,
        val commitSha: String
    ) : CopyOutcome()

    data class Failure(val message: String) : CopyOutcome()
}
