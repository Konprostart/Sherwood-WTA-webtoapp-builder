package com.sherwood.wta.core.agent.tool.builtin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sherwood.wta.core.agent.tool.Tool
import com.sherwood.wta.core.agent.tool.ToolContext
import com.sherwood.wta.core.agent.tool.ToolResult
import com.sherwood.wta.core.frontend.GitDataApiClient
import com.sherwood.wta.core.frontend.GitDataFailureKind
import com.sherwood.wta.core.frontend.GitDataResult
import com.sherwood.wta.core.frontend.RepoBridgeChangeSet
import com.sherwood.wta.core.frontend.RepoBridgeCommitResult
import com.sherwood.wta.core.frontend.RepoBridgeCommitUseCase
import com.sherwood.wta.core.frontend.RepoBridgeMetadata
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import com.sherwood.wta.core.frontend.RepoBridgePrepareResult
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.GitHubApiClient
import com.sherwood.wta.core.studio.GitHubFailureKind
import com.sherwood.wta.core.studio.GitHubResult
import com.sherwood.wta.core.studio.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The only way the in-app agent can send changes to GitHub.
 *
 * Four rules are enforced here in code, not by prompting the model:
 *
 * 1. **A new branch every time.** The commit is never written to the base branch the
 *    folder was imported from, never to `main`/`master`, and never to a branch that
 *    already exists remotely. The branch is created at the current base head and the
 *    ref update expects exactly that commit.
 * 2. **Pull request only.** The push and the `POST /pulls` call happen in the same
 *    invocation; there is no code path that advances a branch without opening a pull
 *    request, and no merge call at all.
 * 3. **A tap per push.** [requiresFreshApproval] makes the permission prompt ask on
 *    every call and hides "Always allow", so one tap can never pre-authorize later
 *    pushes.
 * 4. **No force-push.** `updateRef` is always called with `force = false` and the
 *    expected base commit, so a moved branch fails as a conflict instead of being
 *    overwritten.
 *
 * The source of the change is the chat's session folder, which
 * [GitHubImportRepositoryTool] filled with a copy of the repository. Only added and
 * modified files are propagated: a deletion made in the chat folder is *not* pushed,
 * because the folder can legitimately hold a partial copy of the repository.
 */

/** Bounded so one publish cannot upload an arbitrarily large tree. */
private const val MAX_PUBLISH_FILES = 2_000

/** Matches [RepoBridgeCommitUseCase.MAX_FILE_BYTES]: a bigger file could never be pushed. */
private const val MAX_PUBLISH_FILE_BYTES = 10L * 1024 * 1024
private const val MAX_PUBLISH_TOTAL_BYTES = 64L * 1024 * 1024

/** GitHub accepts longer refs, but a readable branch name is worth the cap. */
private const val MAX_BRANCH_LENGTH = 80

/** How many `-2`, `-3`, … suffixes to try before giving up on a free name. */
private const val BRANCH_ATTEMPTS = 20

/**
 * Branch prefixes this repository uses (AGENTS.md: plain `type/slug`, never a tool or
 * agent namespace). The conventional-commit prefix of the PR title picks one; `feat`
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
 * unprefixed name can ever reach `POST /git/refs`.
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

private const val TAG = "GitHubPublish"

private val PR_BODY_FALLBACK = """
    Prepared by the in-app agent from the files edited in this chat.

    The branch was created from the base branch's current head and the commit was pushed
    without a force-update, so the diff contains only the edited files.
""".trimIndent()

class GitHubOpenPullRequestTool : Tool {

    override val name = "GitHubOpenPullRequest"

    override val description = """
        Send the files edited in this chat to GitHub as a pull request — the only push
        path the agent has.
        Commit these rules to memory: every call creates a NEW branch from the branch the
        chat folder was imported from, commits there, and opens a pull request. It never
        commits to the base branch, never to main/master, never reuses an existing
        branch, never force-pushes and never merges.
        Requires a folder previously filled by GitHubImportRepository. Only added and
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
        val connection = resolveGitHubConnection(ctx)
        if (!connection.hasToken) return ToolResult.ok(NOT_CONNECTED)
        connection.failure?.let { return ToolResult.error(it) }

        val requestedRepository = argString(args, "repository")?.takeIf { it.isNotBlank() }
        val (owner, repo) = parseOwnerRepo(requestedRepository ?: connection.repository)
            ?: return ToolResult.error(
                "GitHubOpenPullRequest: '${requestedRepository ?: connection.repository}' is " +
                    "not an owner/repo identifier. Pass `repository` explicitly."
            )

        val title = argString(args, "title")?.trim().orEmpty()
        if (title.isEmpty()) {
            return ToolResult.error(
                "GitHubOpenPullRequest: `title` is required — it becomes the pull request " +
                    "title. Write it in English."
            )
        }
        val body = argString(args, "body")?.trim()?.takeIf { it.isNotEmpty() } ?: PR_BODY_FALLBACK
        val commitMessage = argString(args, "commitMessage")?.trim()?.takeIf { it.isNotEmpty() }
            ?: title

        val requestedFolder = argString(args, "folder")?.trim()?.takeIf { it.isNotEmpty() }
        val folder = sessionFolderName(requestedFolder, repo)
        // Unlike the importer, a malformed folder is refused instead of silently falling
        // back to the default: pushing the wrong folder would send the wrong changes.
        if (requestedFolder != null && folder != requestedFolder.trim('/')) {
            return ToolResult.error(
                "GitHubOpenPullRequest: '$requestedFolder' is not a plain folder name inside " +
                    "this chat's working folder."
            )
        }
        val folderDir = ctx.fileManager.resolveSafe(ctx.sessionId, folder)
            ?: return ToolResult.error(
                "GitHubOpenPullRequest: '$folder' is not a folder inside this chat's working folder."
            )
        if (!folderDir.isDirectory) {
            return ToolResult.error(
                "GitHubOpenPullRequest: this chat has no '$folder' folder. Call " +
                    "GitHubImportRepository first, edit the files, then call this tool."
            )
        }

        // The sandbox carries the import metadata. It tells us which repository the files
        // came from and which commit the diff must be taken against — without it the
        // upload would be an unbounded "add every file" against an unknown base.
        val metadataStore = RepoBridgeMetadataStore()
        val sandboxMetadata = metadataStore.read(folderDir)
            ?: return ToolResult.error(
                "GitHubOpenPullRequest: '$folder' was not created by GitHubImportRepository " +
                    "(no ${RepoBridgeMetadataStore.FILE_NAME}). Import the repository into " +
                    "this chat first so the push has a known base commit."
            )
        if (!sandboxMetadata.owner.equals(owner, ignoreCase = true) ||
            !sandboxMetadata.repo.equals(repo, ignoreCase = true)
        ) {
            return ToolResult.error(
                "GitHubOpenPullRequest: '$folder' holds " +
                    "${sandboxMetadata.owner}/${sandboxMetadata.repo}, not $owner/$repo. " +
                    "Import the right repository into this chat first."
            )
        }
        if (sandboxMetadata.subPath != null) {
            return ToolResult.error(
                "GitHubOpenPullRequest: '$folder' was imported from the subfolder " +
                    "'${sandboxMetadata.subPath}'. Pushing a subfolder import is not " +
                    "supported — import the repository root instead."
            )
        }
        val baseSha = sandboxMetadata.baseSha
            ?: return ToolResult.error(
                "GitHubOpenPullRequest: '$folder' has no recorded base commit (the import " +
                    "was offline or rate-limited). Re-import the repository so the push has " +
                    "a known base."
            )

        val requestedBase = argString(args, "baseBranch")?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedBase != null && requestedBase != sandboxMetadata.ref) {
            return ToolResult.error(
                "GitHubOpenPullRequest: '$folder' was imported from '${sandboxMetadata.ref}', " +
                    "so the pull request must target that branch. Re-import from " +
                    "'$requestedBase' if that is the intended base."
            )
        }
        val baseBranch = sandboxMetadata.ref
        if (!WorkspaceService.isValidBranchName(baseBranch)) {
            return ToolResult.error(
                "GitHubOpenPullRequest: the recorded base branch '$baseBranch' is not a " +
                    "valid Git branch name."
            )
        }

        val api = GitHubApiClient(ctx.androidContext, EndpointSettingsStore.current(ctx.androidContext))
        val workspace = WorkspaceService(ctx.androidContext)

        // Rule 1: a brand-new branch name, derived and checked before anything is created.
        val explicitBranch = argString(args, "branch")?.trim()?.takeIf { it.isNotEmpty() }
        val branch = explicitBranch ?: run {
            val plan = branchPlanForTitle(title)
            var chosen: String? = null
            for (attempt in 0 until BRANCH_ATTEMPTS) {
                val candidate = branchNameFor(plan, attempt)
                if (newBranchProblem(candidate, baseBranch) != null) continue
                val probe = probeBranch(api, owner, repo, candidate)
                // An unknown state means the API call itself failed: stop rather than
                // guess, and a taken name simply means "try the next suffix".
                if (probe is BranchProbe.Unknown) {
                    return ToolResult.error(
                        "GitHubOpenPullRequest: could not check whether '$candidate' exists — " +
                            probe.message
                    )
                }
                if (probe is BranchProbe.Exists) continue
                chosen = candidate
                break
            }
            chosen ?: return ToolResult.error(
                "GitHubOpenPullRequest: every candidate branch name derived from the title " +
                    "already exists. Pass an explicit `branch` such as 'feat/${plan.slug}-x'."
            )
        }
        if (explicitBranch != null) {
            newBranchProblem(explicitBranch, baseBranch)?.let { problem ->
                return ToolResult.error("GitHubOpenPullRequest: cannot use '$explicitBranch' — $problem.")
            }
            when (val probe = probeBranch(api, owner, repo, explicitBranch)) {
                is BranchProbe.Exists -> return ToolResult.error(
                    "GitHubOpenPullRequest: branch '$explicitBranch' already exists on GitHub. " +
                        "A task always gets a new branch — pass another name."
                )
                is BranchProbe.Unknown -> return ToolResult.error(
                    "GitHubOpenPullRequest: could not check whether '$explicitBranch' exists — " +
                        probe.message
                )
                is BranchProbe.Free -> Unit
            }
        }

        // Rule 4 (precondition): the base must not have moved since the import, otherwise
        // the diff would silently revert the newer commits when it is applied.
        val baseHead = when (val result = api.getBranch(owner, repo, baseBranch)) {
            is GitHubResult.Success -> result.value
            is GitHubResult.Failure -> return ToolResult.error(
                "GitHubOpenPullRequest: could not read '$baseBranch' from $owner/$repo — " +
                    (result.message ?: result.kind.name)
            )
        }
        if (baseHead.sha != baseSha) {
            return ToolResult.error(
                "GitHubOpenPullRequest: '$baseBranch' moved to ${baseHead.sha.take(8)} after " +
                    "this chat imported it at ${baseSha.take(8)}. Re-import the repository " +
                    "(GitHubImportRepository) so the pull request does not revert newer commits."
            )
        }

        // Working copy: reuse a clean Studio copy on the same base, else download one.
        val projectDir = withContext(Dispatchers.IO) {
            reusableWorkingCopy(workspace, owner, repo, baseBranch, baseSha)
        } ?: workspace.clone(owner, repo, baseBranch)
            .getOrElse { failure ->
                return ToolResult.error(
                    "GitHubOpenPullRequest: could not prepare a working copy of $owner/$repo — " +
                        (failure.message ?: failure.javaClass.simpleName)
                )
            }
            .let { File(it.localPath) }

        val workingMetadata = metadataStore.read(projectDir)
            ?: return ToolResult.error(
                "GitHubOpenPullRequest: the working copy at ${projectDir.name} has no " +
                    "repository metadata."
            )
        if (workingMetadata.ref != baseBranch || workingMetadata.baseSha != baseSha) {
            return ToolResult.error(
                "GitHubOpenPullRequest: the working copy at ${projectDir.name} tracks " +
                    "${workingMetadata.ref}@${workingMetadata.baseSha?.take(8)}, not " +
                    "$baseBranch@${baseSha.take(8)}."
            )
        }

        val stats = withContext(Dispatchers.IO) { copySessionTree(folderDir, projectDir) }
        if (stats.failed > 0) {
            // A file that cannot be written would silently keep its old content and the
            // pushed change set would not match what the agent believes it edited.
            return ToolResult.error(
                "GitHubOpenPullRequest: ${stats.failed} file(s) in '$folder' could not be " +
                    "copied into the working copy. Nothing was pushed; check free space and " +
                    "file permissions, then retry."
            )
        }
        if (stats.files == 0) {
            return ToolResult.error(
                "GitHubOpenPullRequest: '$folder' has no readable files to push."
            )
        }

        // From here the working copy reports the new branch, so the commit below advances
        // that branch and not the base. Restored on every failure before the push.
        val onTaskBranch = workingMetadata.copy(ref = branch, pendingBaseSha = null)
        val commitUseCase = RepoBridgeCommitUseCase(ctx.androidContext, projectDir)
        try {
            metadataStore.write(projectDir, onTaskBranch)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not record the task branch: ${e.javaClass.simpleName}")
            return ToolResult.error(
                "GitHubOpenPullRequest: could not record the task branch in the working copy."
            )
        }

        val prepared = commitUseCase.prepare()
        if (prepared is RepoBridgePrepareResult.Failure) {
            restoreMetadata(projectDir, workingMetadata)
            return ToolResult.error(
                "GitHubOpenPullRequest: could not diff the changes (${prepared.reason})" +
                    (prepared.detail?.let { " — $it" } ?: ".")
            )
        }
        val ready = prepared as RepoBridgePrepareResult.Ready
        if (ready.changes.isEmpty) {
            restoreMetadata(projectDir, workingMetadata)
            return ToolResult.error(
                "GitHubOpenPullRequest: nothing to push — no file in '$folder' differs from " +
                    "$owner/$repo@$baseBranch. Edit files in this chat first."
            )
        }

        val created = api.createBranch(owner, repo, branch, baseHead.sha)
        if (created is GitHubResult.Failure) {
            restoreMetadata(projectDir, workingMetadata)
            return ToolResult.error(
                "GitHubOpenPullRequest: GitHub refused to create '$branch' — " +
                    (created.message ?: created.kind.name) + "."
            )
        }

        when (val committed = commitUseCase.commit(commitMessage)) {
            is RepoBridgeCommitResult.Failure -> {
                val cleanup = undoEmptyBranch(
                    context = ctx.androidContext,
                    owner = owner,
                    repo = repo,
                    branch = branch,
                    baseSha = baseHead.sha
                )
                if (cleanup.branchRemoved) {
                    restoreMetadata(projectDir, workingMetadata)
                }
                return ToolResult.error(
                    "GitHubOpenPullRequest: the push failed (${committed.reason})" +
                        (committed.detail?.let { " at $it" } ?: "") + ". " +
                        "Nothing was merged. " +
                        (
                            if (cleanup.branchRemoved) {
                                "The empty branch '$branch' was removed again."
                            } else {
                                "Branch '$branch' may still exist at the base commit — remove " +
                                    "it from Studio → Repositories if the retry uses another name."
                            }
                            )
                )
            }
            is RepoBridgeCommitResult.Success -> {
                val pull = api.createPullRequest(
                    owner = owner,
                    repo = repo,
                    title = title,
                    head = branch,
                    base = baseBranch,
                    body = body
                )
                return when (pull) {
                    is GitHubResult.Success -> ToolResult.ok(
                        summary(
                            owner = owner,
                            repo = repo,
                            branch = branch,
                            baseBranch = baseBranch,
                            baseSha = baseHead.sha,
                            commitSha = committed.commitSha,
                            pullNumber = pull.value.number,
                            pullUrl = pull.value.htmlUrl,
                            changes = ready.changes,
                            stats = stats
                        )
                    )
                    is GitHubResult.Failure -> ToolResult.error(
                        "GitHubOpenPullRequest: the commit is on '$branch' but GitHub refused " +
                            "the pull request — " + (pull.message ?: pull.kind.name) + ". " +
                            "Open it from Studio → Repositories → Create PR " +
                            "(head '$branch' → base '$baseBranch'). " +
                            "Commit: https://github.com/$owner/$repo/commit/${committed.commitSha}"
                    )
                }
            }
        }
    }

    private fun summary(
        owner: String,
        repo: String,
        branch: String,
        baseBranch: String,
        baseSha: String,
        commitSha: String,
        pullNumber: Int,
        pullUrl: String,
        changes: RepoBridgeChangeSet,
        stats: PublishCopyStats
    ): String = buildString {
        appendLine("Pull request opened: $pullUrl")
        appendLine()
        appendLine("Repository: $owner/$repo")
        appendLine("Branch: $branch  (new, created at $baseBranch@${baseSha.take(8)})")
        appendLine("Base: $baseBranch")
        appendLine("Commit: $commitSha")
        appendLine(
            "Changes: ${changes.total} file(s) — ${changes.added.size} added, " +
                "${changes.modified.size} modified"
        )
        val shown = (changes.added + changes.modified).take(20)
        shown.forEach { appendLine("  $it") }
        if (changes.total > shown.size) appendLine("  … ${changes.total - shown.size} more")
        if (stats.skippedLarge > 0) {
            appendLine("Skipped ${stats.skippedLarge} file(s) larger than 10 MB.")
        }
        if (stats.truncated) {
            appendLine("Stopped at the publish cap — the rest of the folder was not uploaded.")
        }
        if (changes.deleted.isNotEmpty()) {
            // copySessionTree never deletes, so this only happens if the remote base has
            // files the working copy lacks (a subfolder/partial import). Refuse to hide it.
            appendLine(
                "Note: ${changes.deleted.size} remote file(s) are not in the working copy " +
                    "and were left in place."
            )
        }
        appendLine()
        appendLine(
            "No force-push: the ref update expected $baseBranch@${baseSha.take(8)}. " +
                "Review and merge the pull request when CI is green — the agent never merges."
        )
        append("Pull request: https://github.com/$owner/$repo/pull/$pullNumber")
    }.trimEnd()
}

/** Outcome of removing a branch that only ever pointed at the base commit. */
private data class BranchCleanup(val branchRemoved: Boolean)

/**
 * Deletes [branch] only when its head is still exactly [baseSha].
 *
 * A branch that received the commit is never removed here: that state means the push
 * succeeded even though a later step failed, and the user must be able to open the pull
 * request from it.
 */
private suspend fun undoEmptyBranch(
    context: android.content.Context,
    owner: String,
    repo: String,
    branch: String,
    baseSha: String
): BranchCleanup {
    val git = GitDataApiClient(context)
    return when (val ref = git.getRef(owner, repo, branch)) {
        is GitDataResult.Success -> {
            if (ref.value.sha != baseSha) {
                BranchCleanup(branchRemoved = false)
            } else {
                when (val deleted = git.deleteRef(owner, repo, branch)) {
                    is GitDataResult.Success -> BranchCleanup(branchRemoved = true)
                    is GitDataResult.Failure -> {
                        if (deleted.kind == GitDataFailureKind.NOT_FOUND) {
                            BranchCleanup(branchRemoved = true)
                        } else {
                            AppLogger.w(TAG, "Branch cleanup failed: ${deleted.kind}")
                            BranchCleanup(branchRemoved = false)
                        }
                    }
                }
            }
        }
        is GitDataResult.Failure -> BranchCleanup(branchRemoved = false)
    }
}

/** Restores the branch record after a failure that never reached the remote. */
private fun restoreMetadata(projectDir: File, original: RepoBridgeMetadata) {
    runCatching {
        RepoBridgeMetadataStore().write(projectDir, original.copy(pendingBaseSha = null))
    }.onFailure {
        AppLogger.w(TAG, "Could not restore metadata: ${it.javaClass.simpleName}")
    }
}

/**
 * The newest Studio working copy of [owner]/[repo] that tracks [baseBranch] at [baseSha]
 * and has no uncommitted changes. Reusing it avoids downloading the repository twice.
 */
private suspend fun reusableWorkingCopy(
    workspace: WorkspaceService,
    owner: String,
    repo: String,
    baseBranch: String,
    baseSha: String
): File? {
    val candidates = try {
        workspace.importedProjects(owner, repo)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Could not list imported projects: ${e.javaClass.simpleName}")
        return null
    }
    for (directory in candidates) {
        val metadata = workspace.metadata(directory) ?: continue
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
    api: GitHubApiClient,
    owner: String,
    repo: String,
    branch: String
): BranchProbe = when (val result = api.getBranch(owner, repo, branch)) {
    is GitHubResult.Success -> BranchProbe.Exists
    is GitHubResult.Failure ->
        // NOT_FOUND is the only answer that means "free". Anything else (auth, network)
        // must stop the run rather than attempt to create a branch that may exist.
        if (result.kind == GitHubFailureKind.NOT_FOUND) {
            BranchProbe.Free
        } else {
            BranchProbe.Unknown(result.message ?: result.kind.name)
        }
}
