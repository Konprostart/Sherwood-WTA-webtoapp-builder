package com.sherwood.wta.core.agent.tool.builtin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sherwood.wta.core.agent.tool.Tool
import com.sherwood.wta.core.agent.tool.ToolContext
import com.sherwood.wta.core.agent.tool.ToolResult
import com.sherwood.wta.core.frontend.RepoBridgeCommitFailureKind
import com.sherwood.wta.core.frontend.RepoBridgeCommitResult
import com.sherwood.wta.core.frontend.RepoBridgeCommitUseCase
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.WorkspaceService
import com.sherwood.wta.core.studio.git.GitCommitWriterResolver
import com.sherwood.wta.core.studio.git.GitFailureKind
import com.sherwood.wta.core.studio.git.GitProvider
import com.sherwood.wta.core.studio.git.GitProviderId
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.git.GitWriterResolution
import com.sherwood.wta.core.studio.git.NewGitChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The only way the in-app agent can send changes to the connected git host.
 *
 * Four rules are enforced here in code, not by prompting the model:
 *
 * 1. **A new branch every time.** The commit is never written to the base branch the
 *    folder was imported from, never to `main`/`master`, and never to a branch that
 *    already exists remotely. The branch is created at the imported commit and the
 *    change is appended to it.
 * 2. **Pull request only.** The push and the create-change-request call happen in the
 *    same invocation; there is no code path that advances a branch without opening a pull
 *    request, and no merge call at all.
 * 3. **A tap per push.** [requiresFreshApproval] makes the permission prompt ask on
 *    every call and hides "Always allow", so one tap can never pre-authorize later
 *    pushes.
 * 4. **No force-push.** Nothing sends `force`/`force_push`; a host that validates the
 *    base refuses a moved branch or a file changed remotely instead of overwriting it.
 *
 * The host is whatever the user configured — GitHub, Gitea or Forgejo. Publishing goes
 * through [RepoBridgeCommitUseCase.commitNewBranch], so the atomic Gitea `change-files`
 * path and GitHub's staged ref update are selected by the host, not by this tool.
 *
 * The source of the change is the chat's session folder, which [GitImportRepositoryTool]
 * filled with a copy of the repository. Only added and modified files are propagated: a
 * deletion made in the chat folder is *not* pushed, because the folder can legitimately
 * hold a partial copy of the repository.
 */

/** Bounded so one publish cannot upload an arbitrarily large tree. */
internal const val MAX_PUBLISH_FILES = 2_000

/** Matches [RepoBridgeCommitUseCase.MAX_FILE_BYTES]: a bigger file could never be pushed. */
private const val MAX_PUBLISH_FILE_BYTES = 10L * 1024 * 1024
private const val MAX_PUBLISH_TOTAL_BYTES = 64L * 1024 * 1024

/** GitHub accepts longer refs, but a readable branch name is worth the cap. */
private const val MAX_BRANCH_LENGTH = 80

/** How many `-2`, `-3`, … suffixes to try before giving up on a free name. */
private const val BRANCH_ATTEMPTS = 20

/**
 * Branch prefixes this repository uses (AGENTS.md: plain `type/slug`, never a tool or
 * agent namespace). The conventional-commit prefix of the title picks one; `feat`
 * is the fallback.
 */
internal val PUBLISH_BRANCH_PREFIXES =
    listOf("feat", "fix", "docs", "chore", "refactor", "perf", "test", "build", "ci")

/** Branches a new task branch may never be. */
private val PROTECTED_BRANCH_NAMES = setOf("main", "master", "head", "trunk", "develop")

private val CONVENTIONAL_PREFIX = Regex(
    "^(feat|fix|docs|chore|refactor|perf|test|build|ci)(?:\\([^)]*\\))?!?:\\s*",
    RegexOption.IGNORE_CASE
)

private val SLUG_SEGMENT = Regex("-+")

/** A derived branch name: the prefix from the title and a lowercase slug. */
internal data class BranchPlan(val prefix: String, val slug: String)

/**
 * Derives the branch prefix and slug from a pull-request title.
 *
 * `feat(studio): make publish safe` becomes `feat/make-publish-safe`. A title without a
 * conventional prefix keeps `feat`. Non-ASCII runs collapse to `-`, so a non-English
 * title still produces a valid ref (the tool asks for an English title anyway).
 */
internal fun branchPlanForTitle(title: String): BranchPlan {
    val trimmed = title.trim()
    val match = CONVENTIONAL_PREFIX.find(trimmed)
    val prefix = match?.groupValues?.get(1)?.lowercase()
        ?.takeIf { it in PUBLISH_BRANCH_PREFIXES }
        ?: "feat"
    val rest = if (match != null) trimmed.removeRange(match.range) else trimmed
    val slug = rest
        .lowercase()
        .map { character ->
            when {
                character in 'a'..'z' || character in '0'..'9' -> character
                else -> '-'
            }
        }
        .joinToString("")
        .replace(SLUG_SEGMENT, "-")
        .trim('-')
        .take(48)
        .trim('-')
        .ifEmpty { "changes" }
    return BranchPlan(prefix, slug)
}

/** `attempt` 0 is the bare name; later attempts append a numeric suffix. */
internal fun branchNameFor(plan: BranchPlan, attempt: Int): String =
    if (attempt <= 0) {
        "${plan.prefix}/${plan.slug}"
    } else {
        "${plan.prefix}/${plan.slug}-${attempt + 1}"
    }

/**
 * Returns why [candidate] may not be used as the task branch, or null when it is fine.
 *
 * This is the structural part of rule 1: no base branch, no protected branch and no
 * unprefixed name can ever reach a create-branch call.
 */
internal fun newBranchProblem(candidate: String, baseBranch: String): String? {
    val name = candidate.trim()
    if (name.isEmpty()) return "the branch name is empty"
    if (name.length > MAX_BRANCH_LENGTH) {
        return "the branch name is longer than $MAX_BRANCH_LENGTH characters"
    }
    if (!WorkspaceService.isValidBranchName(name)) {
        return "the branch name contains characters Git refuses"
    }
    val lower = name.lowercase()
    if (lower in PROTECTED_BRANCH_NAMES) {
        return "'$name' is a protected branch; a task always gets a new one"
    }
    if (lower == baseBranch.trim().lowercase()) {
        return "'$name' is the branch the folder was imported from; a task always gets a new one"
    }
    val slash = name.indexOf('/')
    if (slash <= 0) {
        return "the branch name must be '<type>/<slug>' with one of ${PUBLISH_BRANCH_PREFIXES.joinToString(", ")}"
    }
    val prefix = name.substring(0, slash)
    if (prefix !in PUBLISH_BRANCH_PREFIXES) {
        return "'$prefix' is not an allowed branch prefix (${PUBLISH_BRANCH_PREFIXES.joinToString(", ")})"
    }
    val slug = name.substring(slash + 1)
    if (!Regex("^[a-z0-9][a-z0-9._-]*$").matches(slug)) {
        return "the branch slug must be lowercase letters, digits, '.', '_' or '-'"
    }
    if (slug.endsWith(".")) return "the branch slug may not end with '.'"
    return null
}

/** What one session-folder copy produced. */
internal class PublishCopyStats {
    var files = 0
    var bytes = 0L
    var skippedLarge = 0

    /** A file that could not be written — the publish is aborted rather than half-applied. */
    var failed = 0
    var truncated = false
}

/**
 * Copies the files the agent edited from [source] into the repository working copy at
 * [target].
 *
 * `.git`, `.changes` and the repository metadata file are never copied: the first two
 * are not repository content, and overwriting `repo_bridge.json` would replace the
 * working copy's branch record with the one from the chat import. Symlinks that resolve
 * outside [source] are skipped, and every destination is checked to stay inside
 * [target].
 */
internal fun copySessionTree(source: File, target: File): PublishCopyStats {
    val stats = PublishCopyStats()
    val sourceRoot = runCatching { source.canonicalPath }.getOrNull() ?: return stats
    val pending = ArrayDeque<Pair<File, String>>()
    pending.addLast(source to "")

    while (pending.isNotEmpty()) {
        val (directory, prefix) = pending.removeLast()
        val children = directory.listFiles() ?: continue
        for (child in children) {
            if (stats.files >= MAX_PUBLISH_FILES || stats.bytes >= MAX_PUBLISH_TOTAL_BYTES) {
                stats.truncated = true
                return stats
            }
            if (child.name == ".git" || child.name == ".changes") continue
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (relative == RepoBridgeMetadataStore.FILE_NAME ||
                relative == "${RepoBridgeMetadataStore.FILE_NAME}.tmp"
            ) {
                continue
            }
            val canonical = runCatching { child.canonicalPath }.getOrNull() ?: continue
            if (!canonical.startsWith(sourceRoot + File.separator)) continue
            if (child.isDirectory) {
                pending.addLast(child to relative)
                continue
            }
            if (!child.isFile) continue
            val size = child.length()
            if (size > MAX_PUBLISH_FILE_BYTES) {
                stats.skippedLarge++
                continue
            }
            if (stats.bytes + size > MAX_PUBLISH_TOTAL_BYTES) {
                stats.truncated = true
                return stats
            }
            val destination = File(target, relative)
            if (!WorkspaceService.isInsidePath(target, destination)) continue
            destination.parentFile?.mkdirs()
            val copied = runCatching {
                child.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
            if (!copied) {
                runCatching { destination.delete() }
                stats.failed++
                AppLogger.w(TAG, "Copy failed for $relative")
                continue
            }
            stats.files++
            stats.bytes += size
        }
    }
    return stats
}

private const val TAG = "GitPublish"

private val CHANGE_REQUEST_BODY_FALLBACK = """
    Prepared by the in-app agent from the files edited in this chat.

    The branch was created from the base branch's current head and the commit was pushed
    without a force-update, so the diff contains only the edited files.
""".trimIndent()

class GitOpenChangeRequestTool : Tool {

    override val name = "GitOpenChangeRequest"

    override val description = """
        Send the files edited in this chat to the connected git host (GitHub, Gitea or
        Forgejo) as a pull request — the only push path the agent has.
        Commit these rules to memory: every call creates a NEW branch from the branch the
        chat folder was imported from, commits there, and opens a pull request. It never
        commits to the base branch, never to main/master, never reuses an existing
        branch, never force-pushes and never merges.
        Requires a folder previously filled by GitImportRepository. Only added and
        modified files are uploaded — a file deleted in the chat folder stays in the
        repository. The user is asked to approve the push with a fresh tap on every call,
        so call this only when the change is complete and the user asked to send it;
        calling it early spends the user's approval on a partial change.
        `title` and `body` are the pull request text: write them in English (repository
        policy), describe what changed and why, and include full URLs when referring to
        an issue. The tool returns the pull request URL.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string(
            "title",
            "Pull request title in English, e.g. 'fix(studio): keep the token row visible'.",
            required = true
        )
        string(
            "body",
            "Pull request description in English. Omit and a short fallback body is used."
        )
        string("commitMessage", "Commit message. Omit to use the title.")
        string("repository", "owner/repo. Omit to use the repository Studio is pointed at.")
        string(
            "baseBranch",
            "Branch the pull request targets. Must be the branch the chat folder was " +
                "imported from; omit to use it (recommended)."
        )
        string(
            "branch",
            "New branch name as '<type>/<slug>' (feat, fix, docs, chore, refactor, perf, " +
                "test, build, ci). Omit to derive it from the title. The branch must not " +
                "exist yet."
        )
        string(
            "folder",
            "Folder inside this chat's working folder that holds the repository copy. " +
                "Omit to use the repository name."
        )
    }

    override fun isReadOnly(): Boolean = false

    /** Rule 3: a fresh tap every time; "Always allow" is not offered for this tool. */
    override fun requiresFreshApproval(): Boolean = true

    override fun activityDescription(args: JsonObject): String? {
        val title = argString(args, "title")?.takeIf { it.isNotBlank() }
        val branch = argString(args, "branch")?.takeIf { it.isNotBlank() }
        return buildString {
            append("Push changes to a new branch and open a pull request")
            if (title != null) append(": \"$title\"") else append(".")
            if (branch != null) append(" (branch $branch)")
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val connection = resolveGitConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(notConnectedMessage(connection))
        connection.failure?.let { return ToolResult.error(it) }
        val provider = connection.provider
            ?: return ToolResult.error("${connection.providerName} is not usable in this build.")
        if (!connection.canPublish) {
            return ToolResult.error(
                "${connection.providerName} has no publish path in this build. Pushing " +
                    "changes and opening a pull request are not available; browsing, " +
                    "importing and downloading build artifacts still work."
            )
        }

        val requestedRepository = argString(args, "repository")?.takeIf { it.isNotBlank() }
        val (owner, repo) = parseOwnerRepo(requestedRepository ?: connection.repository)
            ?: return ToolResult.error(
                "${name}: '${requestedRepository ?: connection.repository}' is not an " +
                    "owner/repo identifier. Pass `repository` explicitly."
            )
        val repoRef = GitRepoRef.of(owner, repo)
            ?: return ToolResult.error("$name: '$owner/$repo' is not a valid repository.")

        val title = argString(args, "title")?.trim().orEmpty()
        if (title.isEmpty()) {
            return ToolResult.error(
                "$name: `title` is required — it becomes the pull request title. " +
                    "Write it in English."
            )
        }
        val body = argString(args, "body")?.trim()?.takeIf { it.isNotEmpty() }
            ?: CHANGE_REQUEST_BODY_FALLBACK
        val commitMessage = argString(args, "commitMessage")?.trim()?.takeIf { it.isNotEmpty() }
            ?: title

        val requestedFolder = argString(args, "folder")?.trim()?.takeIf { it.isNotEmpty() }
        val folder = sessionFolderName(requestedFolder, repo)
        // Unlike the importer, a malformed folder is refused instead of silently falling
        // back to the default: pushing the wrong folder would send the wrong changes.
        if (requestedFolder != null && folder != requestedFolder.trim('/')) {
            return ToolResult.error(
                "$name: '$requestedFolder' is not a plain folder name inside this chat's " +
                    "working folder."
            )
        }
        val folderDir = ctx.fileManager.resolveSafe(ctx.sessionId, folder)
            ?: return ToolResult.error(
                "$name: '$folder' is not a folder inside this chat's working folder."
            )
        if (!folderDir.isDirectory) {
            return ToolResult.error(
                "$name: this chat has no '$folder' folder. Call GitImportRepository " +
                    "first, edit the files, then call this tool."
            )
        }

        // The sandbox carries the import metadata. It tells us which repository the files
        // came from and which commit the diff must be taken against — without it the
        // upload would be an unbounded "add every file" against an unknown base.
        val metadataStore = RepoBridgeMetadataStore()
        val sandboxMetadata = metadataStore.read(folderDir)
            ?: return ToolResult.error(
                "$name: '$folder' was not created by GitImportRepository (no " +
                    "${RepoBridgeMetadataStore.FILE_NAME}). Import the repository into " +
                    "this chat first so the push has a known base commit."
            )
        if (sandboxMetadata.providerId != connection.providerId) {
            return ToolResult.error(
                "$name: '$folder' was imported from " +
                    "${GitStudioNames.of(sandboxMetadata.providerId)}, but the active host " +
                    "is ${connection.providerName}. Switch the provider back, or import " +
                    "the repository from the active host."
            )
        }
        if (!sandboxMetadata.owner.equals(owner, ignoreCase = true) ||
            !sandboxMetadata.repo.equals(repo, ignoreCase = true)
        ) {
            return ToolResult.error(
                "$name: '$folder' holds " +
                    "${sandboxMetadata.owner}/${sandboxMetadata.repo}, not $owner/$repo. " +
                    "Import the right repository into this chat first."
            )
        }
        if (sandboxMetadata.subPath != null) {
            return ToolResult.error(
                "$name: '$folder' was imported from the subfolder " +
                    "'${sandboxMetadata.subPath}'. Pushing a subfolder import is not " +
                    "supported — import the repository root instead."
            )
        }
        val baseSha = sandboxMetadata.baseSha
            ?: return ToolResult.error(
                "$name: '$folder' has no recorded base commit (the import was offline or " +
                    "rate-limited). Re-import the repository so the push has a known base."
            )

        val requestedBase = argString(args, "baseBranch")?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedBase != null && requestedBase != sandboxMetadata.ref) {
            return ToolResult.error(
                "$name: '$folder' was imported from '${sandboxMetadata.ref}', so the pull " +
                    "request must target that branch. Re-import from '$requestedBase' if " +
                    "that is the intended base."
            )
        }
        val baseBranch = sandboxMetadata.ref
        if (!WorkspaceService.isValidBranchName(baseBranch)) {
            return ToolResult.error(
                "$name: the recorded base branch '$baseBranch' is not a valid Git branch name."
            )
        }

        val workspace = WorkspaceService(ctx.androidContext)

        // Rule 1: a brand-new branch name, derived and checked before anything is created.
        val explicitBranch = argString(args, "branch")?.trim()?.takeIf { it.isNotEmpty() }
        val branch = explicitBranch ?: run {
            val plan = branchPlanForTitle(title)
            var chosen: String? = null
            for (attempt in 0 until BRANCH_ATTEMPTS) {
                val candidate = branchNameFor(plan, attempt)
                if (newBranchProblem(candidate, baseBranch) != null) continue
                val probe = probeBranch(provider, repoRef, candidate)
                // An unknown state means the API call itself failed: stop rather than
                // guess, and a taken name simply means "try the next suffix".
                if (probe is BranchProbe.Unknown) {
                    return ToolResult.error(
                        "$name: could not check whether '$candidate' exists — " + probe.message
                    )
                }
                if (probe is BranchProbe.Exists) continue
                chosen = candidate
                break
            }
            chosen ?: return ToolResult.error(
                "$name: every candidate branch name derived from the title already exists. " +
                    "Pass an explicit `branch` such as 'feat/${plan.slug}-x'."
            )
        }
        if (explicitBranch != null) {
            newBranchProblem(explicitBranch, baseBranch)?.let { problem ->
                return ToolResult.error("$name: cannot use '$explicitBranch' — $problem.")
            }
            when (val probe = probeBranch(provider, repoRef, explicitBranch)) {
                is BranchProbe.Exists -> return ToolResult.error(
                    "$name: branch '$explicitBranch' already exists on " +
                        "${connection.providerName}. A task always gets a new branch — " +
                        "pass another name."
                )
                is BranchProbe.Unknown -> return ToolResult.error(
                    "$name: could not check whether '$explicitBranch' exists — " +
                        probe.message
                )
                is BranchProbe.Free -> Unit
            }
        }

        // Rule 4 (precondition): the base must not have moved since the import, otherwise
        // the diff would silently revert the newer commits when it is applied.
        val baseHead = when (val result = provider.branch(repoRef, baseBranch)) {
            is GitResult.Success -> result.value
            is GitResult.Failure -> return ToolResult.error(
                "$name: could not read '$baseBranch' from $owner/$repo — " +
                    (result.message ?: result.kind.name)
            )
        }
        if (baseHead.sha != baseSha) {
            return ToolResult.error(
                "$name: '$baseBranch' moved to ${baseHead.sha.take(8)} after this chat " +
                    "imported it at ${baseSha.take(8)}. Re-import the repository " +
                    "(GitImportRepository) so the pull request does not revert newer commits."
            )
        }

        // Working copy: reuse a clean Studio copy on the same base, else download one.
        val projectDir = withContext(Dispatchers.IO) {
            reusableWorkingCopy(workspace, owner, repo, baseBranch, baseSha, connection.providerId)
        } ?: workspace.clone(owner, repo, baseBranch)
            .getOrElse { failure ->
                return ToolResult.error(
                    "$name: could not prepare a working copy of $owner/$repo — " +
                        (failure.message ?: failure.javaClass.simpleName)
                )
            }
            .let { File(it.localPath) }

        val workingMetadata = metadataStore.read(projectDir)
            ?: return ToolResult.error(
                "$name: the working copy at ${projectDir.name} has no repository metadata."
            )
        if (workingMetadata.providerId != connection.providerId) {
            return ToolResult.error(
                "$name: the working copy at ${projectDir.name} belongs to " +
                    "${GitStudioNames.of(workingMetadata.providerId)}, not " +
                    "${connection.providerName}."
            )
        }
        if (workingMetadata.ref != baseBranch || workingMetadata.baseSha != baseSha) {
            return ToolResult.error(
                "$name: the working copy at ${projectDir.name} tracks " +
                    "${workingMetadata.ref}@${workingMetadata.baseSha?.take(8)}, not " +
                    "$baseBranch@${baseSha.take(8)}."
            )
        }

        // The host's own limits decide whether this change set can be one commit; reading
        // them before the copy keeps a too-large tree from being written to disk first.
        val writer = (GitCommitWriterResolver.resolve(ctx.androidContext, projectDir)
            as? GitWriterResolution.Ok)?.writer
            ?: return ToolResult.error(
                "$name: the working copy at ${projectDir.name} has no publisher for " +
                    "${connection.providerName}."
            )
        val limits = writer.publishLimits

        val stats = withContext(Dispatchers.IO) { copySessionTree(folderDir, projectDir) }
        if (stats.failed > 0) {
            // A file that cannot be written would silently keep its old content and the
            // pushed change set would not match what the agent believes it edited.
            return ToolResult.error(
                "$name: ${stats.failed} file(s) in '$folder' could not be " +
                    "copied into the working copy. Nothing was pushed; check free space and " +
                    "file permissions, then retry."
            )
        }
        if (stats.skippedLarge > 0) {
            // Such a file cannot be uploaded at all, so the pull request would silently
            // miss it; refuse instead of publishing an incomplete change set.
            return ToolResult.error(
                "$name: ${stats.skippedLarge} file(s) in '$folder' are larger than " +
                    "${MAX_PUBLISH_FILE_BYTES / (1024 * 1024)} MB, which a push cannot " +
                    "upload. Nothing was pushed — split or shrink the file."
            )
        }
        if (stats.files == 0) {
            return ToolResult.error("$name: '$folder' has no readable files to push.")
        }
        if (stats.truncated || stats.files > limits.maxFiles || stats.bytes > limits.maxBytes) {
            // Publishing a capped copy would silently leave the other edits out of the
            // pull request, so the whole publish fails instead.
            return ToolResult.error(
                "$name: '$folder' holds more than one publish can send on " +
                    "${connection.providerName} (${limits.maxFiles} files / " +
                    "${limits.maxBytes / (1024 * 1024)} MB). Nothing was pushed — reduce " +
                    "the change set and retry."
            )
        }

        // The commit lands on a brand-new branch. The use case enforces that rule again
        // and never forces a ref; a failure leaves the working copy as it was.
        val commitUseCase = RepoBridgeCommitUseCase(ctx.androidContext, projectDir)
        val committed = commitUseCase.commitNewBranch(commitMessage, branch)
        if (committed is RepoBridgeCommitResult.Failure) {
            return ToolResult.error(
                descriptionOf(committed) +
                    " Nothing was merged." +
                    branchStateNote(committed.reason, branch)
            )
        }
        val success = committed as RepoBridgeCommitResult.Success

        val changeRequest = when (
            val created = provider.createChangeRequest(
                repoRef,
                NewGitChangeRequest(
                    title = title,
                    head = branch,
                    base = baseBranch,
                    body = body
                )
            )
        ) {
            is GitResult.Success -> created.value
            is GitResult.Failure -> return ToolResult.error(
                "$name: the commit is on '$branch' but ${connection.providerName} refused " +
                    "the pull request — " + (created.message ?: created.kind.name) + ". " +
                    "Open it from Studio → Repositories → Create PR " +
                    "(head '$branch' → base '$baseBranch'). " +
                    "Commit: ${connection.baseUrl}/$owner/$repo/commit/${success.commitSha}"
            )
        }

        return ToolResult.ok(
            summary(
                providerName = connection.providerName,
                baseUrl = connection.baseUrl,
                owner = owner,
                repo = repo,
                branch = branch,
                baseBranch = baseBranch,
                baseSha = baseHead.sha,
                commitSha = success.commitSha,
                pullNumber = changeRequest.number,
                pullUrl = changeRequest.htmlUrl,
                files = success.changedFiles,
                bytes = stats.bytes
            )
        )
    }

    private fun summary(
        providerName: String,
        baseUrl: String,
        owner: String,
        repo: String,
        branch: String,
        baseBranch: String,
        baseSha: String,
        commitSha: String,
        pullNumber: Int,
        pullUrl: String,
        files: Int,
        bytes: Long
    ): String = buildString {
        appendLine("Pull request opened: $pullUrl")
        appendLine()
        appendLine("Repository: $owner/$repo on $providerName")
        appendLine("Branch: $branch  (new, created at $baseBranch@${baseSha.take(8)})")
        appendLine("Base: $baseBranch")
        appendLine("Commit: $commitSha")
        appendLine("Changes: $files file(s) pushed (${bytes / 1024} KB copied from this chat)")
        appendLine()
        appendLine(
            "No force-push: the change was applied on top of " +
                "$baseBranch@${baseSha.take(8)}, and the host refuses an overwrite of a " +
                "file that changed remotely. Review and merge the pull request when CI is " +
                "green — the agent never merges."
        )
        append("Pull request: $pullUrl")
    }.trimEnd()

    /** Turns a publisher failure into a sentence that names the reason and the next step. */
    private fun descriptionOf(failure: RepoBridgeCommitResult.Failure): String {
        val detail = failure.detail?.takeIf { it.isNotBlank() }
        return when (failure.reason) {
            RepoBridgeCommitFailureKind.NO_TOKEN ->
                "$name: no token is stored for the active host."
            RepoBridgeCommitFailureKind.UNAUTHORIZED ->
                "$name: the stored token is invalid or expired."
            RepoBridgeCommitFailureKind.FORBIDDEN ->
                "$name: the token may not write to this repository."
            RepoBridgeCommitFailureKind.NOT_FOUND ->
                "$name: the repository or branch no longer exists."
            RepoBridgeCommitFailureKind.CONFLICT ->
                "$name: the remote changed after this chat imported it" +
                    (detail?.let { " ($it)" } ?: "") +
                    ". Re-import the repository and try again."
            RepoBridgeCommitFailureKind.RATE_LIMITED ->
                "$name: the host is rate-limiting the token; wait and retry."
            RepoBridgeCommitFailureKind.NETWORK ->
                "$name: the network request failed; check the connection and retry."
            RepoBridgeCommitFailureKind.TOO_MANY_FILES,
            RepoBridgeCommitFailureKind.FILE_TOO_LARGE ->
                "$name: the change set is too large for one push" +
                    (detail?.let { " ($it)" } ?: "") + "."
            RepoBridgeCommitFailureKind.NO_CHANGES ->
                "$name: nothing to push — no file in '$detail' differs from the remote."
            RepoBridgeCommitFailureKind.NO_COMMIT_MESSAGE ->
                "$name: a commit message is required."
            RepoBridgeCommitFailureKind.INVALID_BRANCH ->
                "$name: the branch cannot be used" +
                    (detail?.let { " — $it" } ?: "") + "."
            RepoBridgeCommitFailureKind.REVIEW_STALE ->
                "$name: the files changed while they were being pushed; retry."
            RepoBridgeCommitFailureKind.SECRET_BLOCKED,
            RepoBridgeCommitFailureKind.SECRET_WARNINGS_UNACCEPTED ->
                "$name: a file looks like a credential" +
                    (detail?.let { " ($it)" } ?: "") + ", so the push was refused."
            RepoBridgeCommitFailureKind.PROVIDER_READ_ONLY ->
                "$name: this host has no publish path in this build."
            RepoBridgeCommitFailureKind.NO_METADATA,
            RepoBridgeCommitFailureKind.NO_BASE,
            RepoBridgeCommitFailureKind.IO_ERROR,
            RepoBridgeCommitFailureKind.UNEXPECTED ->
                "$name: the working copy could not be prepared" +
                    (detail?.let { " ($it)" } ?: "") + "."
            RepoBridgeCommitFailureKind.MASS_DELETION_UNCONFIRMED,
            RepoBridgeCommitFailureKind.REMOTE_REJECTED ->
                "$name: the host rejected the change set" +
                    (detail?.let { " ($it)" } ?: "") + "."
        }
    }

    /**
     * What the user should look for after a failed push. A single-step host leaves nothing
     * behind; a staged host removes the empty branch again, but only if no commit landed.
     */
    private fun branchStateNote(reason: RepoBridgeCommitFailureKind, branch: String): String =
        when (reason) {
            RepoBridgeCommitFailureKind.CONFLICT,
            RepoBridgeCommitFailureKind.REMOTE_REJECTED,
            RepoBridgeCommitFailureKind.FORBIDDEN,
            RepoBridgeCommitFailureKind.UNAUTHORIZED,
            RepoBridgeCommitFailureKind.NOT_FOUND,
            RepoBridgeCommitFailureKind.NETWORK,
            RepoBridgeCommitFailureKind.RATE_LIMITED ->
                " Nothing was pushed to '$branch'."
            else -> ""
        }
}

/** Host names in tool messages; not translated because they are product names. */
private object GitStudioNames {
    fun of(id: GitProviderId): String = when (id) {
        GitProviderId.GITHUB -> "GitHub"
        GitProviderId.GITEA -> "Gitea"
        GitProviderId.FORGEJO -> "Forgejo"
    }
}

/**
 * The newest Studio working copy of [owner]/[repo] on [providerId] that tracks
 * [baseBranch] at [baseSha] and has no uncommitted changes. Reusing it avoids downloading
 * the repository twice.
 */
private suspend fun reusableWorkingCopy(
    workspace: WorkspaceService,
    owner: String,
    repo: String,
    baseBranch: String,
    baseSha: String,
    providerId: GitProviderId
): File? {
    val candidates = try {
        workspace.importedProjects(owner, repo)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Could not list imported projects: ${e.javaClass.simpleName}")
        return null
    }
    for (directory in candidates) {
        val metadata = workspace.metadata(directory) ?: continue
        // A same-named repository on another host is a different project.
        if (metadata.providerId != providerId) continue
        if (metadata.ref != baseBranch) continue
        if (metadata.baseSha != baseSha) continue
        if (metadata.subPath != null) continue
        // A working copy with uncommitted Studio edits must not be overwritten by the
        // chat folder; a fresh clone is used instead.
        val clean = try {
            workspace.status(directory).getOrNull()?.isClean == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read status: ${e.javaClass.simpleName}")
            false
        }
        if (!clean) continue
        return directory
    }
    return null
}

private sealed class BranchProbe {
    object Free : BranchProbe()
    object Exists : BranchProbe()
    data class Unknown(val message: String) : BranchProbe()
}

private suspend fun probeBranch(
    provider: GitProvider,
    ref: GitRepoRef,
    branch: String
): BranchProbe = when (val result = provider.branch(ref, branch)) {
    is GitResult.Success -> BranchProbe.Exists
    is GitResult.Failure ->
        // NOT_FOUND is the only answer that means "free". Anything else (auth, network)
        // must stop the run rather than attempt to create a branch that may exist.
        if (result.kind == GitFailureKind.NOT_FOUND) {
            BranchProbe.Free
        } else {
            BranchProbe.Unknown(result.message ?: result.kind.name)
        }
}
