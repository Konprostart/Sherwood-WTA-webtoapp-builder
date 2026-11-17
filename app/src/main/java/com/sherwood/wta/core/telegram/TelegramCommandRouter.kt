package com.sherwood.wta.core.telegram

import android.content.Context
import com.sherwood.wta.BuildConfig
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.ArtifactKind
import com.sherwood.wta.core.studio.ArtifactVault
import com.sherwood.wta.core.studio.BuildStudio
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.core.studio.git.GitFailureKind
import com.sherwood.wta.core.studio.git.GitPipeline
import com.sherwood.wta.core.studio.git.GitPipelineRun
import com.sherwood.wta.core.studio.git.GitProvider
import com.sherwood.wta.core.studio.git.GitProviderId
import com.sherwood.wta.core.studio.git.GitProviderLookup
import com.sherwood.wta.core.studio.git.GitProviderRegistry
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns one inbound chat message into one reply.
 *
 * Authorisation is enforced here and nowhere else: a chat that is not paired gets a
 * single "not paired" line and can read nothing, whatever command it sends. Long-running
 * work (a build) is started on [scope] so the polling loop keeps answering other
 * messages, and its result is delivered later through [TelegramNotifications].
 *
 * The router never mutates a git host: it can start a pipeline, which is what the user
 * asked for, and that is the whole remote-write surface of this module. Push, merge and
 * PR creation stay behind the in-app approval dialogs.
 */
class TelegramCommandRouter(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val settings = TelegramSettings.getInstance(context)
    private val providerSettings = com.sherwood.wta.core.studio.git.GitProviderSettings.getInstance(context)
    private val studioSettings = StudioSettingsStore.getInstance(context)
    private val vault = ArtifactVault.getInstance(context)

    /** Ad-hoc per-chat rate limiting; a stuck client could otherwise spin the poll loop. */
    private val recentCommands = ConcurrentHashMap<Long, MutableList<Long>>()

    suspend fun route(message: TelegramMessage): TelegramReply {
        val chat = message.chat ?: return TelegramReply.Ignore
        val command = TelegramCommand.parse(message.text) ?: return TelegramReply.Ignore
        val chatId = chat.id
        val paired = chatId in settings.pairedChats

        // Pairing must work before the chat is trusted, so it is the one command that is
        // evaluated before the allowlist check. It is still rate-limited, and only a
        // private chat may pair: a group would hand every member the bot's powers.
        if (command.name == "pair") {
            if (!chat.isPrivate) {
                return TelegramReply.Text(Strings.telegramPairPrivateOnly)
            }
            if (!allow(chatId)) {
                return TelegramReply.Text(Strings.telegramRateLimited)
            }
            return TelegramReply.Text(redeemPairingCode(command.firstArg, chatId))
        }

        if (!paired) {
            return TelegramReply.Text(Strings.telegramNotPaired)
        }
        if (!allow(chatId)) {
            return TelegramReply.Text(Strings.telegramRateLimited)
        }

        return when (command.name) {
            "start", "help" -> TelegramReply.Text(
                Strings.telegramHelp(botMention()),
                controlsKeyboard()
            )
            "version" -> TelegramReply.Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                controlsKeyboard()
            )
            "status" -> TelegramReply.Text(status(), controlsKeyboard())
            "repos" -> TelegramReply.Text(repositories(), controlsKeyboard())
            "repo" -> TelegramReply.Text(selectRepository(command.firstArg), controlsKeyboard())
            "runs" -> TelegramReply.Text(runs(command.firstArg), controlsKeyboard())
            "build" -> startBuild(command.firstArg, command.arg(1))
            "vault" -> TelegramReply.Text(vaultSummary(), vaultKeyboard())
            "apk" -> latestApk()
            else -> TelegramReply.Text(
                Strings.telegramUnknownCommand(command.name),
                controlsKeyboard()
            )
        }
    }

    /**
     * Handles a tap on an inline button.
     *
     * Authorisation runs here too, and in the same order as a typed command: an unpaired
     * chat learns nothing, and taps share the same per-chat rate limit. The button data
     * is matched against a whitelist — it is never used to build a path, a URL or a
     * command string.
     */
    suspend fun routeCallback(query: TelegramCallbackQuery): TelegramReply {
        // The pairing allowlist is keyed on a chat id; a user id is never an acceptable
        // substitute, so a callback without its message is ignored.
        val chatId = query.message?.chat?.id ?: return TelegramReply.Ignore
        if (chatId !in settings.pairedChats) {
            return TelegramReply.Text(Strings.telegramNotPaired)
        }
        if (!allow(chatId)) {
            return TelegramReply.Text(Strings.telegramRateLimited)
        }
        val data = query.data.orEmpty()
        if (!TelegramControls.isValid(data)) {
            return TelegramReply.Text(Strings.telegramCallbackUnknown, controlsKeyboard())
        }
        return when {
            data == TelegramControls.STATUS -> TelegramReply.Text(status(), controlsKeyboard())
            data == TelegramControls.RUNS -> TelegramReply.Text(runs(null), controlsKeyboard())
            data == TelegramControls.REPOS -> TelegramReply.Text(repositories(), controlsKeyboard())
            data == TelegramControls.VAULT -> TelegramReply.Text(vaultSummary(), vaultKeyboard())
            data == TelegramControls.APK -> latestApk()
            data.startsWith(TelegramControls.APK_PREFIX) -> apkForRun(
                data.removePrefix(TelegramControls.APK_PREFIX).toLongOrNull()
            )
            data == TelegramControls.BUILD -> startBuildFromButton(null)
            data.startsWith(TelegramControls.BUILD_PREFIX) ->
                startBuildFromButton(data.removePrefix(TelegramControls.BUILD_PREFIX))
            else -> TelegramReply.Text(Strings.telegramCallbackUnknown, controlsKeyboard())
        }
    }

    /**
     * Builds the repository's default branch from a button tap. The workflow is chosen by
     * [startBuild] (single workflow, or an explanatory reply when there are several);
     * a workflow id carried in the button is validated before it is used.
     */
    private suspend fun startBuildFromButton(workflowId: String?): TelegramReply {
        if (!settings.allowRemoteBuilds) {
            return TelegramReply.Text(Strings.telegramBuildOff, controlsKeyboard())
        }
        val lookup = GitProviderRegistry.active(context)
        if (lookup !is GitProviderLookup.Available) {
            return TelegramReply.Text(Strings.telegramNoProvider)
        }
        val ref = currentRepositoryRef()
            ?: return TelegramReply.Text(Strings.telegramNoRepository)
        val branch = when (val repository = lookup.provider.repository(ref)) {
            is GitResult.Failure -> return TelegramReply.Text(descriptionOf(repository.kind))
            is GitResult.Success -> repository.value.defaultBranch
        }
        val requested = workflowId?.takeIf { TelegramControls.isValidWorkflowId(it) }
        return startBuild(branch, requested)
    }

    // ------------------------------------------------------------------ pairing

    private fun redeemPairingCode(code: String?, chatId: Long): String {
        if (code.isNullOrBlank()) {
            return Strings.telegramPairNoCode
        }
        return when (settings.redeemPairingCode(code, chatId)) {
            TelegramSettings.PairingOutcome.Paired -> Strings.telegramPaired
            TelegramSettings.PairingOutcome.Mismatch -> Strings.telegramBadCode
            TelegramSettings.PairingOutcome.Expired -> Strings.telegramPairExpired
            TelegramSettings.PairingOutcome.NoActiveCode -> Strings.telegramPairNoCode
        }
    }

    private fun botMention(): String =
        settings.botUsername?.takeIf { it.isNotBlank() }?.let { "@$it " } ?: ""

    // ------------------------------------------------------------------ reads

    private suspend fun status(): String = withProvider { provider, ref ->
        val account = when (val result = provider.account()) {
            is GitResult.Success -> result.value.login
            is GitResult.Failure -> descriptionOf(result.kind)
        }
        buildString {
            appendLine("${provider.id.displayName} · ${provider.baseUrl}")
            appendLine("account: $account")
            appendLine("repo: ${ref?.path ?: Strings.telegramNoRepository}")
            appendLine("builds from chat: ${if (settings.allowRemoteBuilds) "on" else "off"}")
            append("paired chats: ${settings.pairedChats.size}")
        }
    }

    private suspend fun repositories(): String = withProvider { provider, _ ->
        when (val result = provider.repositories(1)) {
            is GitResult.Failure -> descriptionOf(result.kind)
            is GitResult.Success -> {
                if (result.value.isEmpty()) {
                    Strings.telegramNoRepository
                } else {
                    result.value.take(MAX_LIST).joinToString("\n") { repo ->
                        val marker = if (repo.privateRepo) " (private)" else ""
                        "• ${repo.ownerRepo}$marker"
                    }
                }
            }
        }
    }

    private fun selectRepository(raw: String?): String {
        if (raw.isNullOrBlank()) {
            val current = currentRepository()
            return current ?: Strings.telegramNoRepository
        }
        val ref = GitRepoRef.parse(raw) ?: return Strings.telegramNoRepository
        studioSettings.setLastRepository(providerSettings.activeProvider, ref.path)
        return ref.path
    }

    private suspend fun runs(branchArg: String?): String = withProvider { provider, ref ->
        if (ref == null) return@withProvider Strings.telegramNoRepository
        when (val result = provider.runs(ref, branchArg, 8)) {
            is GitResult.Failure -> descriptionOf(result.kind)
            is GitResult.Success -> {
                if (result.value.isEmpty()) {
                    "—"
                } else {
                    result.value.joinToString("\n") { describeRun(it) }
                }
            }
        }
    }

    private fun describeRun(run: GitPipelineRun): String {
        val state = run.conclusion ?: run.status
        val branch = run.branch?.let { " · $it" } ?: ""
        return "• #${run.id} $state$branch"
    }

    private fun vaultSummary(): String {
        val artifacts = vault.list()
        if (artifacts.isEmpty()) return "—"
        return artifacts.take(MAX_LIST).joinToString("\n") { artifact ->
            val version = artifact.versionName?.let { " $it" } ?: ""
            "• ${artifact.fileName}$version · ${artifact.sha256.take(12)}"
        }
    }

    private fun latestApk(): TelegramReply {
        val artifact = vault.list().firstOrNull {
            it.kindEnum == com.sherwood.wta.core.studio.ArtifactKind.APK
        } ?: return TelegramReply.Text("—")
        return apkReply(artifact)
    }

    /** The APK that a specific run produced, so a notification downloads the right build. */
    private fun apkForRun(runId: Long?): TelegramReply {
        if (runId == null) return latestApk()
        val artifact = vault.list().firstOrNull {
            it.runId == runId && it.kindEnum == com.sherwood.wta.core.studio.ArtifactKind.APK
        } ?: return TelegramReply.Text("—")
        return apkReply(artifact)
    }

    private fun apkReply(artifact: com.sherwood.wta.core.studio.VaultArtifact): TelegramReply {
        val file = vault.fileFor(artifact)
        if (!file.isFile) return TelegramReply.Text("—")
        if (file.length() > MAX_DOCUMENT_BYTES) {
            return TelegramReply.Text("${artifact.fileName} · ${file.length() / (1024 * 1024)} MB")
        }
        val version = artifact.versionName?.let { " $it" } ?: ""
        return TelegramReply.Document(file, "${artifact.fileName}$version")
    }

    // ------------------------------------------------------------------ build

    private suspend fun startBuild(branch: String?, workflowArg: String?): TelegramReply {
        if (!settings.allowRemoteBuilds) {
            return TelegramReply.Text(Strings.telegramBuildOff)
        }
        if (branch.isNullOrBlank()) {
            return TelegramReply.Text(Strings.telegramNoRepository)
        }
        val lookup = GitProviderRegistry.active(context)
        if (lookup !is GitProviderLookup.Available) {
            return TelegramReply.Text(Strings.telegramNoProvider)
        }
        val provider = lookup.provider
        if (!provider.capabilities.pipelineDispatch) {
            return TelegramReply.Text(Strings.telegramBuildUnsupported)
        }
        val repoRef = currentRepositoryRef()
            ?: return TelegramReply.Text(Strings.telegramNoRepository)

        val pipelines = when (val listed = provider.pipelines(repoRef)) {
            is GitResult.Failure -> return TelegramReply.Text(descriptionOf(listed.kind))
            is GitResult.Success -> listed.value
        }
        if (pipelines.isEmpty()) {
            return TelegramReply.Text(Strings.telegramBuildUnsupported)
        }
        val workflow = resolveWorkflow(pipelines, workflowArg)
            ?: return TelegramReply.Text(
                Strings.telegramBuildChooseWorkflow(
                    pipelines.take(MAX_LIST).joinToString(", ") { it.name }
                )
            )

        val owner = repoRef.owner
        val repo = repoRef.name
        val workflowName = workflow.name
        val targetBranch = branch.trim()
        scope.launch(Dispatchers.IO) {
            val studio = BuildStudio(context)
            // Not nested inside `runCatching`: a build reports failure through its own
            // Result, and wrapping that in another Result is both confusing and awkward
            // to type.
            val result: Result<com.sherwood.wta.core.studio.BranchBuildResult> = try {
                studio.buildBranch(
                    owner = owner,
                    repo = repo,
                    workflow = workflow,
                    ref = targetBranch
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onSuccess { finished ->
                // The BuildEvents collector announces the same run; mark it here so a
                // build started from chat is reported exactly once.
                if (!TelegramBuildNotifier.markNotified(finished.run.id)) return@onSuccess
                val hasApk = finished.artifacts.records.any {
                    it.kindEnum == com.sherwood.wta.core.studio.ArtifactKind.APK
                }
                TelegramNotifications.publish(
                    TelegramReply.Text(
                        buildCompletionText(
                            repo = "$owner/$repo",
                            branch = targetBranch,
                            run = finished.run,
                            succeeded = finished.succeeded,
                            artifactCount = finished.artifacts.records.size,
                            error = finished.downloadError,
                            runUrl = finished.run.htmlUrl
                        ),
                        TelegramKeyboards.buildResult(finished.run.htmlUrl, hasApk, finished.run.id)
                    )
                )
            }.onFailure { error ->
                AppLogger.w(TAG, "Remote build could not be observed: ${error.javaClass.simpleName}")
                TelegramNotifications.publish(
                    TelegramReply.Text(
                        Strings.telegramBuildFailed(error.message ?: error.javaClass.simpleName)
                    )
                )
            }
        }
        return TelegramReply.Text(Strings.telegramBuildStarted(targetBranch, workflowName))
    }

    private fun resolveWorkflow(
        pipelines: List<GitPipeline>,
        requested: String?
    ): GitPipeline? {
        if (requested.isNullOrBlank()) {
            // One workflow is unambiguous; several need a choice rather than a guess.
            return pipelines.singleOrNull()
        }
        val needle = requested.trim()
        return pipelines.firstOrNull { it.id.equals(needle, ignoreCase = true) }
            ?: pipelines.firstOrNull { it.name.equals(needle, ignoreCase = true) }
            ?: pipelines.firstOrNull { it.path.substringAfterLast('/').equals(needle, ignoreCase = true) }
    }

    private fun buildCompletionText(
        repo: String,
        branch: String,
        run: GitPipelineRun,
        succeeded: Boolean,
        artifactCount: Int,
        error: String?,
        runUrl: String? = null
    ): String {
        val conclusion = run.conclusion ?: if (succeeded) "success" else "failure"
        return buildString {
            append(Strings.telegramNotifyBuild(repo, branch, conclusion, run.id))
            if (artifactCount > 0) {
                append('\n')
                append(Strings.telegramNotifyArtifacts(artifactCount))
            }
            if (!error.isNullOrBlank()) {
                append('\n')
                append(error)
            }
            if (!runUrl.isNullOrBlank()) {
                append('\n')
                append(runUrl)
            }
        }
    }

    // --------------------------------------------------------------- buttons

    private fun controlsKeyboard(): List<List<TelegramButton>> = buildList {
        add(
            listOf(
                TelegramButton.callback(Strings.telegramButtonStatus, TelegramControls.STATUS),
                TelegramButton.callback(Strings.telegramButtonRuns, TelegramControls.RUNS)
            )
        )
        add(
            listOf(
                TelegramButton.callback(Strings.telegramButtonRepos, TelegramControls.REPOS),
                TelegramButton.callback(Strings.telegramButtonVault, TelegramControls.VAULT)
            )
        )
        add(listOf(TelegramButton.callback(Strings.telegramButtonApk, TelegramControls.APK)))
        // Offering "build" on a server that cannot dispatch, or with the switch off,
        // would be a button that always fails.
        if (settings.allowRemoteBuilds && currentRepository() != null) {
            add(listOf(TelegramButton.callback(Strings.telegramButtonBuild, TelegramControls.BUILD)))
        }
    }

    private fun vaultKeyboard(): List<List<TelegramButton>> = listOf(
        listOf(TelegramButton.callback(Strings.telegramButtonApk, TelegramControls.APK)),
        listOf(TelegramButton.callback(Strings.telegramButtonStatus, TelegramControls.STATUS))
    )

    // --------------------------------------------------------------- plumbing

    private fun allow(chatId: Long): Boolean {
        val now = System.currentTimeMillis()
        // The map is keyed by chat id, so a stranger talking to the bot once would
        // otherwise leave an entry forever.
        if (recentCommands.size > MAX_TRACKED_CHATS) {
            recentCommands.keys.filter { key ->
                val stamps = recentCommands[key] ?: return@filter true
                synchronized(stamps) { stamps.isEmpty() || now - stamps.last() > RATE_WINDOW_MS }
            }.forEach { recentCommands.remove(it) }
        }
        val stamps = recentCommands.getOrPut(chatId) { mutableListOf() }
        synchronized(stamps) {
            stamps.removeAll { now - it > RATE_WINDOW_MS }
            if (stamps.size >= MAX_COMMANDS_PER_WINDOW) return false
            stamps.add(now)
        }
        return true
    }

    private fun currentRepository(): String? {
        val providerId = providerSettings.activeProvider
        return studioSettings.lastRepository(providerId)?.takeIf { it.contains('/') }
    }

    private fun currentRepositoryRef(): GitRepoRef? {
        val current = currentRepository() ?: return null
        return GitRepoRef.parse(current)
    }

    private suspend fun withProvider(block: suspend (GitProvider, GitRepoRef?) -> String): String {
        val lookup = GitProviderRegistry.active(context)
        if (lookup !is GitProviderLookup.Available) {
            return Strings.telegramNoProvider
        }
        return block(lookup.provider, currentRepositoryRef())
    }

    private fun descriptionOf(kind: GitFailureKind): String = when (kind) {
        GitFailureKind.NO_TOKEN -> Strings.telegramNoToken
        GitFailureKind.UNAUTHORIZED -> Strings.telegramNoToken
        GitFailureKind.FORBIDDEN -> Strings.telegramNoAccess
        GitFailureKind.NOT_FOUND -> Strings.telegramNoRepository
        GitFailureKind.RATE_LIMITED -> Strings.telegramRateLimited
        GitFailureKind.NETWORK -> Strings.telegramNetworkError
        GitFailureKind.UNSUPPORTED -> Strings.telegramBuildUnsupported
        else -> Strings.telegramNetworkError
    }

    private val GitProviderId.displayName: String
        get() = when (this) {
            GitProviderId.GITHUB -> "GitHub"
            GitProviderId.GITEA -> "Gitea"
            GitProviderId.FORGEJO -> "Forgejo"
        }

    companion object {
        private const val TAG = "TelegramCommandRouter"
        private const val MAX_LIST = 8
        private const val MAX_DOCUMENT_BYTES = 45L * 1024 * 1024
        private const val RATE_WINDOW_MS = 60_000L
        private const val MAX_COMMANDS_PER_WINDOW = 12

        /** Tracked chats; beyond this the map is swept of idle entries. */
        private const val MAX_TRACKED_CHATS = 64
    }
}

/** Reply queue shared between background work and the polling service. */
object TelegramNotifications {
    private val _outbox = kotlinx.coroutines.flow.MutableSharedFlow<TelegramReply>(
        extraBufferCapacity = 16
    )
    val outbox: kotlinx.coroutines.flow.SharedFlow<TelegramReply> = _outbox

    fun publish(reply: TelegramReply) {
        _outbox.tryEmit(reply)
    }
}
