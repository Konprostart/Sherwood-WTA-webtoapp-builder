package com.sherwood.wta.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.studio.DeviceFlowChallenge
import com.sherwood.wta.core.studio.DeviceFlowPollResult
import com.sherwood.wta.core.studio.DeviceFlowStartResult
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.FileDiff
import com.sherwood.wta.core.studio.git.GitBranch
import com.sherwood.wta.core.studio.git.GitCommitSummary
import com.sherwood.wta.core.studio.GitHubOAuthClient
import com.sherwood.wta.core.studio.git.GitRepository
import com.sherwood.wta.core.studio.git.GitFailureKind
import com.sherwood.wta.core.studio.git.GitProviderId
import com.sherwood.wta.core.studio.git.GitProviderLookup
import com.sherwood.wta.core.studio.git.GitProviderRegistry
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.git.GitStudio
import com.sherwood.wta.core.studio.git.NewGitChangeRequest
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.core.studio.WorkspaceService
import com.sherwood.wta.core.studio.WorkspaceStatus
import com.sherwood.wta.ui.components.PremiumTextField
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaSection
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import com.sherwood.wta.ui.theme.LocalShowDescriptions
import com.sherwood.wta.ui.theme.ifDescriptionsShown
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun StudioReposTab(
    context: Context,
    snackbarHostState: SnackbarHostState,
    onOpenGitSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val endpoints = remember { EndpointSettingsStore.current(context) }
    val oauth = remember { GitHubOAuthClient(endpoints = endpoints) }
    val studioSettings = remember { StudioSettingsStore.getInstance(context) }
    val workspace = remember { WorkspaceService(context) }
    val uriHandler = LocalUriHandler.current

    // The active connection, resolved once. Everything below reads this instead of
    // assuming GitHub: the capability flags decide which controls can be offered.
    val studio = remember { GitStudio.state(context) }
    val provider = remember(studio.id, studio.baseUrl) {
        (GitProviderRegistry.active(context) as? GitProviderLookup.Available)?.provider
    }
    val capabilities = studio.capabilities
    val isGitHub = studio.id == GitProviderId.GITHUB

    var connectedLogin by remember { mutableStateOf<String?>(null) }
    var repos by remember { mutableStateOf<List<GitRepository>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var selectedRepo by remember { mutableStateOf<GitRepository?>(null) }
    var branches by remember { mutableStateOf<List<GitBranch>>(emptyList()) }
    var selectedBranch by remember { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf<List<File>>(emptyList()) }
    var status by remember { mutableStateOf<WorkspaceStatus?>(null) }
    var history by remember { mutableStateOf<List<GitCommitSummary>>(emptyList()) }
    var watchlist by remember { mutableStateOf(studioSettings.watchlist(studio.id).toSet()) }

    var challenge by remember { mutableStateOf<DeviceFlowChallenge?>(null) }
    var oauthError by remember { mutableStateOf<String?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var polling by remember { mutableStateOf(false) }

    var showNewBranch by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var newBranchFrom by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf<FileDiff?>(null) }
    var diffError by remember { mutableStateOf<String?>(null) }
    var commitProject by remember { mutableStateOf<File?>(null) }

    var showNewPr by remember { mutableStateOf(false) }
    var prTitle by remember { mutableStateOf("") }
    var prBodyText by remember { mutableStateOf("") }
    var prDraft by remember { mutableStateOf(false) }
    var prHead by remember { mutableStateOf("") }
    var prBase by remember { mutableStateOf("") }
    var prBusy by remember { mutableStateOf(false) }

    suspend fun loadRepositories() {
        val active = provider ?: return
        when (val user = active.account()) {
            is GitResult.Success -> connectedLogin = user.value.login
            is GitResult.Failure -> {
                if (!studio.hasToken || user.kind == GitFailureKind.UNAUTHORIZED) {
                    connectedLogin = null
                }
                snackbarHostState.showSnackbar(user.message ?: Strings.studioConnectFailed)
                return
            }
        }
        when (val result = active.repositories()) {
            is GitResult.Success -> repos = result.value
            is GitResult.Failure -> {
                if (!studio.hasToken || result.kind == GitFailureKind.UNAUTHORIZED) {
                    connectedLogin = null
                }
                snackbarHostState.showSnackbar(
                    result.message ?: Strings.studioConnectFailed
                )
            }
        }
    }

    suspend fun loadRepository(repo: GitRepository) {
        selectedRepo = repo
        studioSettings.setLastRepository(studio.id, repo.ownerRepo)
        selectedBranch = repo.defaultBranch
        branches = runCatching { workspace.branches(repo.owner, repo.name) }.getOrDefault(emptyList())
        projects = workspace.importedProjects(repo.owner, repo.name)
        status = null
        history = emptyList()
        projects.firstOrNull()?.let { project ->
            status = workspace.status(project).getOrNull()
        }
        history = runCatching {
            workspace.history(repo.owner, repo.name, repo.defaultBranch, 10)
        }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) {
        loading = true
        if (studio.hasToken) {
            loadRepositories()
        }
        loading = false
    }

    fun startDeviceFlow() {
        val clientId = studioSettings.oauthClientId
        // The device flow has no fallback: without a Client ID GitHub cannot issue a
        // device code. Say exactly what is missing instead of repeating the hint.
        if (!GitHubOAuthClient.isValidClientId(clientId)) {
            oauthError = Strings.studioClientIdMissing
            return
        }
        oauthError = null
        scope.launch {
            when (val started = oauth.startDeviceFlow(clientId)) {
                is DeviceFlowStartResult.Success -> challenge = started.challenge
                is DeviceFlowStartResult.Failure ->
                    snackbarHostState.showSnackbar(started.message)
            }
        }
    }

    LaunchedEffect(challenge) {
        val current = challenge ?: return@LaunchedEffect
        polling = true
        var interval = current.intervalSeconds
        val deadline = System.currentTimeMillis() + current.expiresInSeconds * 1000L
        try {
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                when (val result = oauth.pollForToken(studioSettings.oauthClientId, current.deviceCode)) {
                    is DeviceFlowPollResult.Success -> {
                        val saved = tokenStore.saveToken(result.token)
                        if (saved.isSuccess) {
                            challenge = null
                            loading = true
                            loadRepositories()
                            loading = false
                        } else {
                            snackbarHostState.showSnackbar(Strings.studioConnectFailed)
                        }
                        return@LaunchedEffect
                    }
                    DeviceFlowPollResult.Pending -> Unit
                    DeviceFlowPollResult.SlowDown -> interval += 5
                    is DeviceFlowPollResult.Failed -> {
                        challenge = null
                        snackbarHostState.showSnackbar(result.message)
                        return@LaunchedEffect
                    }
                }
            }
            challenge = null
            snackbarHostState.showSnackbar(Strings.studioConnectFailed)
        } finally {
            polling = false
        }
    }

    val filtered = repos.filter {
        query.isBlank() || it.fullName.contains(query, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = WtaSpacing.ScreenHorizontal,
            vertical = WtaSpacing.ScreenVertical
        ),
        verticalArrangement = Arrangement.spacedBy(WtaSpacing.SectionGap)
    ) {
        item {
            WtaSection(title = studio.displayName, description = Strings.studioAccountDesc) {
                WtaSettingCard {
                    if (connectedLogin != null) {
                        WtaSettingRow(
                            title = Strings.studioConnected(connectedLogin.orEmpty()),
                            subtitle = studio.baseUrl
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = WtaSpacing.RowHorizontal,
                                vertical = WtaSpacing.ContentGap
                            )
                        ) {
                            when {
                                // An unavailable provider is stated, never silently
                                // replaced by GitHub: the token must not go elsewhere.
                                studio.unavailableReason != null -> Text(
                                    when (studio.unavailableReason) {
                                        GitProviderUnavailableReason.MISSING_BASE_URL ->
                                            Strings.studioProviderMissingUrl
                                        GitProviderUnavailableReason.NON_HTTPS_BASE_URL ->
                                            Strings.studioProviderInsecureUrl
                                        else -> Strings.studioProviderNotImplemented
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                // The device flow exists only on GitHub; Gitea/Forgejo
                                // are connected with a personal access token.
                                !isGitHub -> Text(
                                    Strings.studioGiteaConnectHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                else -> {
                                    val clientIdValid = GitHubOAuthClient.isValidClientId(
                                        studioSettings.oauthClientId
                                    )
                                    // State first, then the action: the Connect button
                                    // cannot work without a Client ID, so say that before
                                    // it is tapped instead of after.
                                    if (!clientIdValid) {
                                        Text(
                                            Strings.studioClientIdMissing,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else if (LocalShowDescriptions.current) {
                                        Text(
                                            Strings.studioClientIdHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    WtaButton(
                                        onClick = { startDeviceFlow() },
                                        text = Strings.studioConnect
                                    )
                                    if (oauthError != null) {
                                        Text(
                                            oauthError.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        // The token path is an equal alternative, not a hidden one. On
                        // GitHub it already has its own screen; on a self-hosted server
                        // it is entered in Studio settings, next to the server URL.
                        WtaSectionDivider()
                        if (isGitHub) {
                            WtaSettingRow(
                                title = Strings.gitTokenTitle,
                                subtitle = Strings.studioTokenAlternate.ifDescriptionsShown(),
                                onClick = onOpenGitSettings
                            )
                        } else {
                            WtaSettingRow(
                                title = Strings.studioProviderToken,
                                subtitle = if (studio.hasToken) {
                                    Strings.studioProviderTokenSaved
                                } else {
                                    Strings.studioProviderTokenMissing
                                }
                            )
                        }
                    }
                    if (!isGitHub) {
                        WtaSectionDivider()
                        Column(
                            modifier = Modifier.padding(
                                horizontal = WtaSpacing.RowHorizontal,
                                vertical = WtaSpacing.ContentGap
                            )
                        ) {
                            Text(
                                Strings.studioProviderReadOnly,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (connectedLogin != null) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(Strings.search) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (loading) {
                item { StudioBusy(Strings.loading) }
            } else if (filtered.isEmpty()) {
                item { StudioEmptyState(Strings.studioReposEmpty) }
            } else {
                item {
                    WtaSection(title = Strings.studioTabRepos) {
                        WtaSettingCard {
                            filtered.forEachIndexed { index, repo ->
                                if (index > 0) {
                                    com.sherwood.wta.ui.design.WtaSectionDivider()
                                }
                                val watched = watchlist.contains(repo.ownerRepo)
                                WtaSettingRow(
                                    title = repo.fullName,
                                    subtitle = repo.description,
                                    active = selectedRepo?.ownerRepo == repo.ownerRepo,
                                    onClick = {
                                        scope.launch {
                                            loading = true
                                            loadRepository(repo)
                                            loading = false
                                        }
                                    },
                                    trailing = {
                                        WtaButton(
                                            onClick = {
                                                watchlist = studioSettings.toggleWatch(studio.id, repo.ownerRepo).toSet()
                                            },
                                            text = if (watched) Strings.studioUnwatch else Strings.studioWatch,
                                            variant = WtaButtonVariant.Outlined,
                                            size = WtaButtonSize.Small
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        val repo = selectedRepo
        if (repo != null) {
            item {
                WtaSection(title = Strings.studioBranches) {
                    WtaSettingCard {
                        if (capabilities?.createBranches == true) {
                            WtaSettingRow(
                                title = Strings.studioNewBranch,
                                onClick = {
                                    newBranchName = ""
                                    newBranchFrom = repo.defaultBranch
                                    showNewBranch = true
                                }
                            )
                            com.sherwood.wta.ui.design.WtaSectionDivider()
                        } else {
                            WtaSettingRow(
                                title = Strings.studioNewBranch,
                                subtitle = Strings.studioReadOnlyAction
                            )
                            com.sherwood.wta.ui.design.WtaSectionDivider()
                        }
                        branches.forEachIndexed { index, branch ->
                            if (index > 0) com.sherwood.wta.ui.design.WtaSectionDivider()
                            WtaSettingRow(
                                title = branch.name,
                                subtitle = branch.sha.take(10),
                                active = selectedBranch == branch.name,
                                onClick = { selectedBranch = branch.name }
                            )
                        }
                    }
                }
            }

            item {
                WtaSection(title = Strings.studioStatus) {
                    WtaSettingCard {
                        if (projects.isEmpty()) {
                            WtaSettingRow(title = Strings.studioNoProject)
                        } else {
                            val project = projects.first()
                            // Change tracking diffs against the host's git objects, which
                            // a read-only driver cannot serve. Name that instead of a
                            // status row that can never leave "loading".
                            WtaSettingRow(
                                title = if (!studio.canPublish) {
                                    Strings.studioProviderReadOnly
                                } else {
                                    status?.let {
                                        if (it.isClean) Strings.studioStatusClean
                                        else Strings.studioStatusChanges(it.changes.total)
                                    } ?: Strings.loading
                                },
                                subtitle = project.absolutePath
                            )
                            com.sherwood.wta.ui.design.WtaSectionDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = WtaSpacing.RowHorizontal,
                                        vertical = WtaSpacing.ContentGap
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Checkout and pull both need the working copy's diff
                                // against the base commit, which a read-only driver
                                // cannot compute; offering them would fail every time.
                                if (studio.canPublish) {
                                WtaButton(
                                    text = Strings.studioCheckout,
                                    size = WtaButtonSize.Small,
                                    onClick = {
                                        val branch = selectedBranch ?: repo.defaultBranch
                                        scope.launch {
                                            loading = true
                                            val result = workspace.checkout(project, branch)
                                            loading = false
                                            result.onSuccess {
                                                status = workspace.status(project).getOrNull()
                                                snackbarHostState.showSnackbar(
                                                    Strings.studioCheckoutDone(branch)
                                                )
                                            }.onFailure { error ->
                                                val message = when (error) {
                                                    is com.sherwood.wta.core.studio.WorkspaceException.CleanRequired ->
                                                        Strings.studioCheckoutDirty(error.changed)
                                                    else -> error.message ?: Strings.studioConnectFailed
                                                }
                                                snackbarHostState.showSnackbar(message)
                                            }
                                        }
                                    }
                                )
                                WtaButton(
                                    text = Strings.studioPull,
                                    size = WtaButtonSize.Small,
                                    variant = WtaButtonVariant.Outlined,
                                    onClick = {
                                        scope.launch {
                                            loading = true
                                            workspace.pull(project).onFailure {
                                                snackbarHostState.showSnackbar(
                                                    it.message ?: Strings.studioConnectFailed
                                                )
                                            }
                                            status = workspace.status(project).getOrNull()
                                            loading = false
                                        }
                                    }
                                )
                                }
                                WtaButton(
                                    text = Strings.studioManifest,
                                    size = WtaButtonSize.Small,
                                    variant = WtaButtonVariant.Outlined,
                                    onClick = {
                                        val metadata = workspace.metadata(project)
                                        if (metadata == null) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(Strings.studioNoProject)
                                            }
                                            return@WtaButton
                                        }
                                        scope.launch {
                                            val bridge = com.sherwood.wta.core.studio.WorkspaceBridgeService(context)
                                            val manifest = bridge.manifestFor(project, metadata)
                                            bridge.writeLocal(project, manifest)
                                                .onSuccess {
                                                    snackbarHostState.showSnackbar(
                                                        Strings.studioManifest
                                                    )
                                                }
                                                .onFailure {
                                                    snackbarHostState.showSnackbar(
                                                        it.message ?: Strings.studioConnectFailed
                                                    )
                                                }
                                        }
                                    }
                                )
                                // A pull request is how a Studio branch reaches the
                                // rest of the world (including an external workspace).
                                // A driver without that API says so instead of offering a
                                // button that would always fail.
                                if (capabilities?.createChangeRequests == true) {
                                    WtaButton(
                                        text = Strings.studioCreatePr,
                                        size = WtaButtonSize.Small,
                                        variant = WtaButtonVariant.Outlined,
                                        onClick = {
                                            prHead = selectedBranch ?: repo.defaultBranch
                                            prBase = repo.defaultBranch
                                            prTitle = ""
                                            prBodyText = ""
                                            prDraft = false
                                            showNewPr = true
                                        }
                                    )
                                }
                            }
                            if (studio.canPublish) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = WtaSpacing.RowHorizontal,
                                        vertical = 8.dp
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                WtaButton(
                                    text = Strings.refresh,
                                    size = WtaButtonSize.Small,
                                    variant = WtaButtonVariant.Outlined,
                                    onClick = {
                                        scope.launch {
                                            loading = true
                                            workspace.fetch(project)
                                                .onSuccess { remote ->
                                                    status = workspace.status(project).getOrNull()
                                                    history = runCatching {
                                                        workspace.history(
                                                            repo.owner,
                                                            repo.name,
                                                            remote.branch,
                                                            10
                                                        )
                                                    }.getOrDefault(history)
                                                }
                                                .onFailure {
                                                    snackbarHostState.showSnackbar(
                                                        it.message ?: Strings.studioConnectFailed
                                                    )
                                                }
                                            loading = false
                                        }
                                    }
                                )
                                WtaButton(
                                    text = Strings.repoBridgeCommitTitle,
                                    size = WtaButtonSize.Small,
                                    variant = WtaButtonVariant.Outlined,
                                    onClick = { commitProject = project }
                                )
                            }
                            }
                            if (status?.changes?.total?.let { it > 0 } == true) {
                                val changes = status?.changes
                                val changedPaths = buildList {
                                    changes?.added?.forEach { add(it) }
                                    changes?.modified?.forEach { add(it) }
                                    changes?.deleted?.forEach { add(it) }
                                }
                                changedPaths.take(20).forEach { path ->
                                    com.sherwood.wta.ui.design.WtaSectionDivider()
                                    WtaSettingRow(
                                        title = path,
                                        subtitle = Strings.studioDiff,
                                        onClick = {
                                            scope.launch {
                                                diffError = null
                                                workspace.diff(project, path)
                                                    .onSuccess { diff = it }
                                                    .onFailure {
                                                        diffError = it.message
                                                            ?: Strings.studioConnectFailed
                                                    }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                WtaSection(title = Strings.studioHistory) {
                    WtaSettingCard {
                        if (history.isEmpty()) {
                            WtaSettingRow(title = Strings.loading)
                        } else {
                            history.forEachIndexed { index, commit ->
                                if (index > 0) {
                                    com.sherwood.wta.ui.design.WtaSectionDivider()
                                }
                                WtaSettingRow(
                                    title = commit.message.ifBlank { commit.sha.take(7) },
                                    subtitle = "${commit.authorName} · ${commit.sha.take(7)}",
                                    onClick = commit.htmlUrl?.let { url ->
                                        { uriHandler.openUri(url) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                WtaSection(title = Strings.studioMerge) {
                    WtaSettingCard {
                        if (capabilities?.mergeBranches != true) {
                            WtaSettingRow(
                                title = Strings.studioMerge,
                                subtitle = Strings.studioReadOnlyAction
                            )
                        } else {
                        WtaSettingRow(
                            title = "${selectedBranch ?: repo.defaultBranch} → ${repo.defaultBranch}",
                            subtitle = Strings.studioMerge,
                            onClick = {
                                val head = selectedBranch ?: repo.defaultBranch
                                if (head == repo.defaultBranch) return@WtaSettingRow
                                scope.launch {
                                    loading = true
                                    workspace.merge(
                                        repo.owner,
                                        repo.name,
                                        repo.defaultBranch,
                                        head
                                    ).onSuccess {
                                        snackbarHostState.showSnackbar(Strings.studioMerged(head))
                                        branches = runCatching {
                                            workspace.branches(repo.owner, repo.name)
                                        }.getOrDefault(branches)
                                    }.onFailure {
                                        snackbarHostState.showSnackbar(
                                            it.message ?: Strings.studioConnectFailed
                                        )
                                    }
                                    loading = false
                                }
                            }
                        )
                        }
                    }
                }
            }
        }

        if (loading) {
            item { StudioBusy(Strings.loading) }
        }
    }

    commitProject?.let { project ->
        val activeRepo = selectedRepo
        RepoBridgeCommitDialog(
            projectDir = project,
            onDismiss = { commitProject = null },
            onCommitted = { sha ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        Strings.repoBridgeCommitSuccess(sha.take(7))
                    )
                    status = workspace.status(project).getOrNull()
                    if (activeRepo != null) {
                        history = runCatching {
                            workspace.history(
                                activeRepo.owner,
                                activeRepo.name,
                                selectedBranch ?: activeRepo.defaultBranch,
                                10
                            )
                        }.getOrDefault(history)
                    }
                }
            }
        )
    }

    if (showNewBranch) {
        AlertDialog(
            onDismissRequest = { showNewBranch = false },
            title = { Text(Strings.studioNewBranch) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        label = { Text(Strings.studioNewBranch) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newBranchFrom,
                        onValueChange = { newBranchFrom = it },
                        label = { Text(Strings.studioBranchFrom) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newBranchName.isNotBlank() && newBranchFrom.isNotBlank(),
                    onClick = {
                        val repo = selectedRepo ?: return@TextButton
                        showNewBranch = false
                        scope.launch {
                            loading = true
                            workspace.createBranch(
                                repo.owner,
                                repo.name,
                                newBranchName.trim(),
                                newBranchFrom.trim()
                            ).onSuccess {
                                snackbarHostState.showSnackbar(
                                    Strings.studioBranchCreated(it.name)
                                )
                                branches = runCatching {
                                    workspace.branches(repo.owner, repo.name)
                                }.getOrDefault(branches)
                            }.onFailure {
                                snackbarHostState.showSnackbar(
                                    it.message ?: Strings.studioConnectFailed
                                )
                            }
                            loading = false
                        }
                    }
                ) { Text(Strings.save) }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranch = false }) { Text(Strings.cancel) }
            }
        )
    }

    if (challenge != null) {
        AlertDialog(
            onDismissRequest = {
                pollJob?.cancel()
                challenge = null
            },
            title = { Text(Strings.studioConnect) },
            text = {
                Column {
                    Text(Strings.studioUserCode(challenge?.userCode.orEmpty()))
                    Spacer(Modifier.height(6.dp))
                    if (polling) StudioBusy(Strings.studioWaiting)
                    TextButton(onClick = {
                        challenge?.verificationUri?.let { uriHandler.openUri(it) }
                    }) { Text(Strings.studioOpenGitHub) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pollJob?.cancel()
                    challenge = null
                }) { Text(Strings.cancel) }
            }
        )
    }

    if (diff != null || diffError != null) {
        AlertDialog(
            onDismissRequest = {
                diff = null
                diffError = null
            },
            title = { Text(diff?.repoPath ?: Strings.studioDiff) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (diffError != null) {
                        Text(diffError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(
                            diff?.unified?.ifBlank { Strings.studioDiff } ?: "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    diff = null
                    diffError = null
                }) { Text(Strings.close) }
            }
        )
    }

    val repoForPr = selectedRepo
    if (showNewPr && repoForPr != null) {
        AlertDialog(
            onDismissRequest = { if (!prBusy) showNewPr = false },
            title = { Text(Strings.studioCreatePr) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "${repoForPr.fullName} · $prHead → $prBase",
                        style = MaterialTheme.typography.bodySmall
                    )
                    PremiumTextField(
                        value = prTitle,
                        onValueChange = { prTitle = it },
                        label = { Text(Strings.studioPrTitle) },
                        singleLine = true
                    )
                    PremiumTextField(
                        value = prBodyText,
                        onValueChange = { prBodyText = it },
                        label = { Text(Strings.studioPrBody) },
                        singleLine = false
                    )
                    PremiumTextField(
                        value = prBase,
                        onValueChange = { prBase = it },
                        label = { Text(Strings.studioPrBase) },
                        singleLine = true
                    )
                    PremiumTextField(
                        value = prHead,
                        onValueChange = { prHead = it },
                        label = { Text(Strings.studioPrHead) },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WtaButton(
                            text = Strings.studioPrDraft,
                            size = WtaButtonSize.Small,
                            variant = if (prDraft) WtaButtonVariant.Tonal
                            else WtaButtonVariant.Outlined,
                            onClick = { prDraft = !prDraft }
                        )
                        if (prBusy) StudioBusy(Strings.loading)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !prBusy && prTitle.isNotBlank() && prHead.isNotBlank() && prBase.isNotBlank(),
                    onClick = {
                        scope.launch {
                            prBusy = true
                            val active = provider
                            val repoRef = GitRepoRef.of(repoForPr.owner, repoForPr.name)
                            val result = if (active == null || repoRef == null) {
                                GitResult.Failure(
                                    GitFailureKind.UNSUPPORTED,
                                    message = Strings.studioConnectFailed
                                )
                            } else {
                                active.createChangeRequest(
                                    repoRef,
                                    NewGitChangeRequest(
                                        title = prTitle,
                                        head = prHead.trim(),
                                        base = prBase.trim(),
                                        body = prBodyText.takeIf { it.isNotBlank() },
                                        draft = prDraft
                                    )
                                )
                            }
                            when (result) {
                                is GitResult.Success -> {
                                    showNewPr = false
                                    snackbarHostState.showSnackbar(
                                        Strings.studioPrCreated(result.value.number)
                                    )
                                }
                                is GitResult.Failure -> snackbarHostState.showSnackbar(
                                    result.message ?: Strings.studioConnectFailed
                                )
                            }
                            prBusy = false
                        }
                    }
                ) { Text(Strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showNewPr = false }) { Text(Strings.cancel) }
            }
        )
    }
}
