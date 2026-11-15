package com.sherwood.wta.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.studio.BranchBuildResult
import com.sherwood.wta.core.studio.BuildPhase
import com.sherwood.wta.core.studio.BuildProgress
import com.sherwood.wta.core.studio.BuildRunDetails
import com.sherwood.wta.core.studio.BuildStudio
import com.sherwood.wta.core.studio.BuildTimeoutException
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.GitHubApiClient
import com.sherwood.wta.core.studio.GitHubArtifact
import com.sherwood.wta.core.studio.GitHubRelease
import com.sherwood.wta.core.studio.GitHubRepository
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.GitHubWorkflow
import com.sherwood.wta.core.studio.GitHubWorkflowRun
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.core.studio.WorkspaceService
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaSection
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One working copy the Studio imported, with the branch a build would run on. */
private data class ImportedBranch(
    val owner: String,
    val repo: String,
    val branch: String,
    val baseSha: String?
) {
    val repoKey: String get() = "$owner/$repo"
}

@Composable
internal fun StudioBuildsTab(context: Context, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val endpoints = remember { EndpointSettingsStore.current(context) }
    val api = remember { GitHubApiClient(context, endpoints) }
    val buildStudio = remember { BuildStudio(context) }
    val workspace = remember { WorkspaceService(context) }
    val settings = remember { StudioSettingsStore.getInstance(context) }
    val uriHandler = LocalUriHandler.current

    var repoKey by remember { mutableStateOf(settings.lastRepository.orEmpty()) }
    var repositories by remember { mutableStateOf<List<GitHubRepository>>(emptyList()) }
    var workflows by remember { mutableStateOf<List<GitHubWorkflow>>(emptyList()) }
    var runs by remember { mutableStateOf<List<GitHubWorkflowRun>>(emptyList()) }
    var releases by remember { mutableStateOf<List<GitHubRelease>>(emptyList()) }
    var imported by remember { mutableStateOf<List<ImportedBranch>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showRepoPicker by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var downloadingAssetId by remember { mutableStateOf<Long?>(null) }

    var dispatchWorkflow by remember { mutableStateOf<GitHubWorkflow?>(null) }
    var dispatchRef by remember { mutableStateOf("") }

    var detailRun by remember { mutableStateOf<GitHubWorkflowRun?>(null) }
    var detail by remember { mutableStateOf<BuildRunDetails?>(null) }
    var detailArtifacts by remember { mutableStateOf<List<GitHubArtifact>>(emptyList()) }
    var detailLogs by remember { mutableStateOf<String?>(null) }
    var detailBusy by remember { mutableStateOf(false) }

    var buildTarget by remember { mutableStateOf<ImportedBranch?>(null) }
    // Editable: a build usually wants the PR branch the agent just pushed, not the
    // branch the working copy was imported from.
    var buildRef by remember { mutableStateOf("") }
    var buildWorkflow by remember { mutableStateOf<GitHubWorkflow?>(null) }
    var buildProgress by remember { mutableStateOf<BuildProgress?>(null) }
    var buildResult by remember { mutableStateOf<BranchBuildResult?>(null) }
    var buildError by remember { mutableStateOf<String?>(null) }
    var buildJob by remember { mutableStateOf<Job?>(null) }

    fun ownerRepo(): Pair<String, String>? {
        val parts = repoKey.split('/', limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0] to parts[1]
        } else {
            null
        }
    }

    /**
     * Imported working copies, de-duplicated to the newest copy of each
     * repository/branch pair. The importer keeps every copy it ever wrote, so the
     * same branch can appear several times.
     */
    fun loadImported() {
        imported = workspace.allImportedProjects()
            .mapNotNull { (_, metadata) ->
                if (!metadata.isValid()) return@mapNotNull null
                ImportedBranch(
                    owner = metadata.owner,
                    repo = metadata.repo,
                    branch = metadata.ref,
                    baseSha = metadata.baseSha
                )
            }
            .distinctBy { "${it.owner}/${it.repo}#${it.branch}" }
    }

    suspend fun reload() {
        val pair = ownerRepo() ?: return
        loading = true
        val (owner, repo) = pair
        when (val repoResult = api.getRepository(owner, repo)) {
            is GitResult.Success -> {
                dispatchRef = repoResult.value.defaultBranch
            }
            is GitResult.Failure -> Unit
        }
        val wf = buildStudio.workflows(owner, repo)
        if (wf.isSuccess) {
            workflows = wf.getOrThrow()
        } else {
            snackbarHostState.showSnackbar(
                wf.exceptionOrNull()?.message ?: Strings.studioConnectFailed
            )
        }
        val runResult = buildStudio.runs(owner, repo, perPage = 20)
        if (runResult.isSuccess) {
            runs = runResult.getOrThrow()
        } else {
            snackbarHostState.showSnackbar(
                runResult.exceptionOrNull()?.message ?: Strings.studioConnectFailed
            )
        }
        // Releases are an optional source: a repository without them must not turn
        // the build tab into an error screen.
        releases = buildStudio.releases(owner, repo, perPage = 10).getOrDefault(emptyList())
        loadImported()
        loading = false
    }

    LaunchedEffect(Unit) {
        when (val result = api.listRepositories()) {
            is GitResult.Success -> {
                repositories = result.value
                if (ownerRepo() == null) {
                    val initial = repositories.firstOrNull()
                    if (initial != null) repoKey = initial.ownerRepo
                }
            }
            is GitResult.Failure -> snackbarHostState.showSnackbar(
                result.message ?: Strings.studioConnectFailed
            )
        }
        loadImported()
    }

    LaunchedEffect(repoKey, refreshKey) {
        if (ownerRepo() != null) {
            reload()
        }
    }

    fun startBranchBuild(target: ImportedBranch, workflow: GitHubWorkflow, ref: String) {
        buildResult = null
        buildError = null
        buildProgress = BuildProgress(BuildPhase.DISPATCHING)
        buildJob = scope.launch {
            buildStudio.buildBranch(
                owner = target.owner,
                repo = target.repo,
                workflow = workflow,
                ref = ref,
                onProgress = { buildProgress = it }
            ).onSuccess { result ->
                buildResult = result
                reload()
                when {
                    !result.succeeded -> snackbarHostState.showSnackbar(
                        Strings.studioBuildFailed(result.run.conclusion.orEmpty())
                    )
                    result.downloadError != null -> snackbarHostState.showSnackbar(
                        Strings.studioBuildArtifactsFailed(result.downloadError.orEmpty())
                    )
                    result.artifacts.records.isEmpty() -> snackbarHostState.showSnackbar(
                        Strings.studioNoArtifacts
                    )
                    else -> snackbarHostState.showSnackbar(
                        Strings.studioArtifactsSaved(result.artifacts.records.size)
                    )
                }
            }.onFailure { error ->
                // Clear the phase so the dialog falls back to the workflow picker and
                // the user can retry without closing it.
                buildProgress = null
                buildError = if (error is BuildTimeoutException) {
                    Strings.studioBuildTimeout
                } else {
                    error.message ?: Strings.studioConnectFailed
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = WtaSpacing.ScreenHorizontal,
            vertical = WtaSpacing.ScreenVertical
        ),
        verticalArrangement = Arrangement.spacedBy(WtaSpacing.SectionGap)
    ) {
        item {
            WtaSection(title = Strings.studioTabRepos) {
                WtaSettingCard {
                    WtaSettingRow(
                        title = repoKey.ifBlank { Strings.studioReposEmpty },
                        subtitle = Strings.studioTabBuilds,
                        onClick = { showRepoPicker = true }
                    )
                    WtaSectionDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = WtaSpacing.RowHorizontal, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WtaButton(
                            text = Strings.refresh,
                            size = WtaButtonSize.Small,
                            onClick = { refreshKey++ }
                        )
                    }
                }
            }
        }

        if (loading) {
            item { StudioBusy(Strings.loading) }
        }

        // ------------------------------------------------- imported branches
        item {
            val visible = imported.filter { ownerRepo() == null || it.repoKey == repoKey }
            WtaSection(title = Strings.studioImportedBranches) {
                WtaSettingCard {
                    if (visible.isEmpty()) {
                        WtaSettingRow(title = Strings.studioImportedBranchesEmpty)
                    }
                    visible.forEachIndexed { index, branch ->
                        if (index > 0) WtaSectionDivider()
                        WtaSettingRow(
                            title = branch.branch,
                            subtitle = buildString {
                                append(branch.repoKey)
                                branch.baseSha?.let { append(" · ").append(it.take(10)) }
                            },
                            trailing = {
                                WtaButton(
                                    text = Strings.studioBuildThisBranch,
                                    size = WtaButtonSize.Small,
                                    onClick = {
                                        buildTarget = branch
                                        buildRef = branch.branch
                                        buildResult = null
                                        buildError = null
                                        buildProgress = null
                                        val known = runs.firstOrNull { it.branch == branch.branch }
                                            ?.workflowId
                                        buildWorkflow = workflows.firstOrNull { it.id == known }
                                            ?: workflows.firstOrNull()
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------- workflows
        item {
            WtaSection(title = Strings.studioWorkflows) {
                WtaSettingCard {
                    if (workflows.isEmpty() && !loading) {
                        WtaSettingRow(title = Strings.studioReposEmpty)
                    }
                    workflows.take(10).forEachIndexed { index, workflow ->
                        if (index > 0) WtaSectionDivider()
                        WtaSettingRow(
                            title = workflow.name,
                            subtitle = workflow.path,
                            trailing = {
                                WtaButton(
                                    text = Strings.studioRunWorkflow,
                                    size = WtaButtonSize.Small,
                                    onClick = {
                                        dispatchWorkflow = workflow
                                        if (dispatchRef.isBlank()) dispatchRef = "main"
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------- runs
        item {
            WtaSection(title = Strings.studioRuns) {
                WtaSettingCard {
                    if (runs.isEmpty() && !loading) {
                        WtaSettingRow(title = Strings.studioReposEmpty)
                    }
                    runs.forEachIndexed { index, run ->
                        if (index > 0) WtaSectionDivider()
                        WtaSettingRow(
                            title = "#${run.runNumber} ${run.displayTitle ?: run.name}",
                            subtitle = buildString {
                                append(run.status)
                                run.conclusion?.let { append(" · ").append(it) }
                                run.branch?.let { append(" · ").append(it) }
                            },
                            onClick = {
                                detailRun = run
                                detail = null
                                detailArtifacts = emptyList()
                                detailLogs = null
                                detailBusy = true
                                val pair = ownerRepo()
                                if (pair != null) {
                                    scope.launch {
                                        val (owner, repo) = pair
                                        detail = buildStudio.runDetails(owner, repo, run.id)
                                            .getOrNull()
                                        detailArtifacts = buildStudio
                                            .artifacts(owner, repo, run.id)
                                            .getOrDefault(emptyList())
                                        detailBusy = false
                                    }
                                } else {
                                    detailBusy = false
                                }
                            },
                            trailing = {
                                run.htmlUrl?.let { url ->
                                    if (url.startsWith("https://")) {
                                        TextButton(onClick = { uriHandler.openUri(url) }) {
                                            Text(Strings.studioOpenOnGitHub)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------- releases
        item {
            WtaSection(title = Strings.studioReleases) {
                WtaSettingCard {
                    if (releases.isEmpty() && !loading) {
                        WtaSettingRow(title = Strings.studioNoReleases)
                    }
                    releases.forEachIndexed { index, release ->
                        if (index > 0) WtaSectionDivider()
                        val assets = release.assets.filter { asset ->
                            asset.isDownloadable && isInstallableAsset(asset.name)
                        }
                        WtaSettingRow(
                            title = release.name ?: release.tagName,
                            subtitle = buildString {
                                append(release.tagName)
                                if (release.draft) append(" · ").append(Strings.studioReleaseDraft)
                                if (release.prerelease) {
                                    append(" · ").append(Strings.studioReleasePrerelease)
                                }
                                if (assets.isEmpty()) {
                                    append(" · ").append(Strings.studioReleaseNoAssets)
                                }
                            },
                            trailing = {
                                release.htmlUrl?.let { url ->
                                    if (url.startsWith("https://")) {
                                        TextButton(onClick = { uriHandler.openUri(url) }) {
                                            Text(Strings.studioOpenOnGitHub)
                                        }
                                    }
                                }
                            }
                        )
                        val pair = ownerRepo()
                        assets.forEach { asset ->
                            WtaSectionDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = WtaSpacing.RowHorizontal, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${asset.name} · " +
                                        Strings.studioFileSizeKb(asset.sizeInBytes / 1024),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                WtaButton(
                                    text = if (downloadingAssetId == asset.id) Strings.loading
                                    else Strings.studioDownloadToVault,
                                    size = WtaButtonSize.Small,
                                    variant = WtaButtonVariant.Outlined,
                                    enabled = downloadingAssetId == null && pair != null,
                                    onClick = {
                                        if (pair != null) {
                                            val (owner, repo) = pair
                                            scope.launch {
                                                downloadingAssetId = asset.id
                                                buildStudio.downloadReleaseAsset(
                                                    owner, repo, release, asset
                                                )
                                                    .onSuccess {
                                                        snackbarHostState.showSnackbar(
                                                            Strings.studioArtifactsSaved(1)
                                                        )
                                                    }
                                                    .onFailure {
                                                        snackbarHostState.showSnackbar(
                                                            it.message ?: Strings.studioConnectFailed
                                                        )
                                                    }
                                                downloadingAssetId = null
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
    }

    if (showRepoPicker) {
        AlertDialog(
            onDismissRequest = { showRepoPicker = false },
            title = { Text(Strings.studioTabRepos) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    repositories.forEach { repo ->
                        WtaSettingRow(
                            title = repo.fullName,
                            subtitle = repo.description,
                            active = repo.ownerRepo == repoKey,
                            onClick = {
                                repoKey = repo.ownerRepo
                                settings.lastRepository = repo.ownerRepo
                                showRepoPicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRepoPicker = false }) { Text(Strings.close) }
            }
        )
    }

    // ------------------------------------------------- build this branch
    val target = buildTarget
    if (target != null) {
        val running = buildProgress != null &&
            buildProgress?.phase != BuildPhase.FINISHED
        AlertDialog(
            onDismissRequest = {
                buildJob?.cancel()
                buildJob = null
                buildTarget = null
                buildProgress = null
                buildResult = null
                buildError = null
            },
            title = { Text(Strings.studioBuildThisBranch) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(target.repoKey, style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = buildRef,
                        onValueChange = { buildRef = it.trim() },
                        label = { Text(Strings.studioDispatchRef) },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (buildRef == target.branch) {
                        target.baseSha?.let { sha ->
                            Text(
                                text = sha.take(10),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (!running && buildProgress == null) {
                        Text(
                            text = Strings.studioBuildWorkflow,
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (workflows.isEmpty()) {
                            Text(
                                text = Strings.studioReposEmpty,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        workflows.forEach { workflow ->
                            WtaSettingRow(
                                title = workflow.name,
                                subtitle = workflow.path,
                                active = buildWorkflow?.id == workflow.id,
                                onClick = { buildWorkflow = workflow }
                            )
                        }
                    }
                    val progress = buildProgress
                    if (progress != null) {
                        Text(
                            text = when (progress.phase) {
                                BuildPhase.DISPATCHING -> Strings.studioBuildPhaseDispatching
                                BuildPhase.WAITING_FOR_RUN -> Strings.studioBuildPhaseWaiting
                                BuildPhase.RUNNING -> buildString {
                                    append(Strings.studioBuildPhaseRunning)
                                    progress.run?.let { run ->
                                        append(" · ").append(run.status)
                                        run.conclusion?.let { append(" · ").append(it) }
                                    }
                                }
                                BuildPhase.DOWNLOADING -> Strings.studioBuildPhaseDownloading
                                BuildPhase.FINISHED -> Strings.studioBuildPhaseFinished
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    buildError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    buildResult?.let { result ->
                        val run = result.run
                        Text(
                            text = "#${run.runNumber} ${run.status}" +
                                (run.conclusion?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (result.succeeded && !result.downloadError.isNullOrBlank()) {
                            Text(
                                text = Strings.studioBuildArtifactsFailed(
                                    result.downloadError.orEmpty()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        result.artifacts.records.forEach { record ->
                            Text(
                                text = "${record.fileName} · " +
                                    Strings.studioFileSizeKb(record.sizeBytes / 1024),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        result.artifacts.skipped.forEach { skipped ->
                            Text(
                                text = "$skipped · ${Strings.studioNoArtifacts}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        run.htmlUrl?.let { url ->
                            if (url.startsWith("https://")) {
                                TextButton(onClick = { uriHandler.openUri(url) }) {
                                    Text(Strings.studioOpenOnGitHub)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val workflow = buildWorkflow
                TextButton(
                    enabled = !running && workflow != null && buildRef.isNotBlank() &&
                        buildResult == null,
                    onClick = {
                        if (workflow != null) startBranchBuild(target, workflow, buildRef)
                    }
                ) { Text(if (running) Strings.loading else Strings.studioBuildThisBranch) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        buildJob?.cancel()
                        buildJob = null
                        buildTarget = null
                        buildProgress = null
                        buildResult = null
                        buildError = null
                    }
                ) { Text(Strings.close) }
            }
        )
    }

    if (dispatchWorkflow != null) {
        AlertDialog(
            onDismissRequest = { dispatchWorkflow = null },
            title = { Text(dispatchWorkflow?.name ?: Strings.studioRunWorkflow) },
            text = {
                Column {
                    OutlinedTextField(
                        value = dispatchRef,
                        onValueChange = { dispatchRef = it.trim() },
                        label = { Text(Strings.studioDispatchRef) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = dispatchRef.isNotBlank(),
                    onClick = {
                        val pair = ownerRepo()
                        val workflow = dispatchWorkflow
                        if (pair != null && workflow != null) {
                            dispatchWorkflow = null
                            scope.launch {
                                val (owner, repo) = pair
                                buildStudio.dispatch(owner, repo, workflow.id.toString(), dispatchRef)
                                    .onSuccess {
                                        snackbarHostState.showSnackbar(Strings.studioDispatchStarted)
                                        refreshKey++
                                    }
                                    .onFailure {
                                        snackbarHostState.showSnackbar(
                                            it.message ?: Strings.studioConnectFailed
                                        )
                                    }
                            }
                        }
                    }
                ) { Text(Strings.studioRunWorkflow) }
            },
            dismissButton = {
                TextButton(onClick = { dispatchWorkflow = null }) { Text(Strings.cancel) }
            }
        )
    }

    if (detailRun != null) {
        AlertDialog(
            onDismissRequest = { detailRun = null },
            title = { Text("#${detailRun?.runNumber} ${detailRun?.name.orEmpty()}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (detailBusy) {
                        StudioBusy(Strings.loading)
                    } else {
                        val run = detail?.run ?: detailRun
                        Text(
                            "${run?.status.orEmpty()} · ${run?.conclusion.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(Strings.studioJobs, style = MaterialTheme.typography.labelMedium)
                        detail?.jobs?.forEach { job ->
                            Text(
                                "${job.name}: ${job.status}${job.conclusion?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            job.steps.forEach { step ->
                                Text(
                                    "  ${step.number}. ${step.name} · ${step.status}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WtaButton(
                                text = Strings.studioLogs,
                                size = WtaButtonSize.Small,
                                variant = WtaButtonVariant.Outlined,
                                enabled = detailLogs == null,
                                onClick = {
                                    val pair = ownerRepo()
                                    val run = detail?.run ?: detailRun
                                    if (pair != null && run != null) {
                                        scope.launch {
                                            val (owner, repo) = pair
                                            buildStudio.logs(owner, repo, run.id)
                                                .onSuccess { detailLogs = it.takeLast(20_000) }
                                                .onFailure {
                                                    snackbarHostState.showSnackbar(
                                                        it.message ?: Strings.studioConnectFailed
                                                    )
                                                }
                                        }
                                    }
                                }
                            )
                            WtaButton(
                                text = Strings.studioDownloadToVault,
                                size = WtaButtonSize.Small,
                                enabled = detailArtifacts.isNotEmpty(),
                                onClick = {
                                    val pair = ownerRepo()
                                    val run = detail?.run ?: detailRun
                                    if (pair != null && run != null) {
                                        scope.launch {
                                            detailBusy = true
                                            val (owner, repo) = pair
                                            buildStudio.downloadRunArtifacts(owner, repo, run)
                                                .onSuccess { result ->
                                                    snackbarHostState.showSnackbar(
                                                        Strings.studioArtifactsSaved(result.records.size)
                                                    )
                                                }
                                                .onFailure {
                                                    snackbarHostState.showSnackbar(
                                                        it.message ?: Strings.studioConnectFailed
                                                    )
                                                }
                                            detailBusy = false
                                        }
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(Strings.studioArtifacts, style = MaterialTheme.typography.labelMedium)
                        if (detailArtifacts.isEmpty()) {
                            Text(
                                Strings.studioNoArtifacts,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        detailArtifacts.forEach { artifact ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${artifact.name} · ${Strings.studioFileSizeKb(artifact.sizeInBytes / 1024)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                WtaButton(
                                    text = Strings.studioDownloadToVault,
                                    size = WtaButtonSize.Small,
                                    variant = WtaButtonVariant.Outlined,
                                    onClick = {
                                        val pair = ownerRepo()
                                        val run = detail?.run ?: detailRun
                                        if (pair != null && run != null) {
                                            scope.launch {
                                                val (owner, repo) = pair
                                                buildStudio.downloadArtifact(
                                                    owner,
                                                    repo,
                                                    artifact,
                                                    run
                                                ).onSuccess {
                                                    snackbarHostState.showSnackbar(
                                                        Strings.studioArtifactsSaved(it.size)
                                                    )
                                                }.onFailure {
                                                    snackbarHostState.showSnackbar(
                                                        it.message ?: Strings.studioConnectFailed
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        if (detailLogs != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                detailLogs.orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detailRun = null }) { Text(Strings.close) }
            }
        )
    }
}

/** Release assets worth importing: the app package itself, not checksums or docs. */
private fun isInstallableAsset(name: String): Boolean =
    name.endsWith(".apk", ignoreCase = true) || name.endsWith(".aab", ignoreCase = true)
