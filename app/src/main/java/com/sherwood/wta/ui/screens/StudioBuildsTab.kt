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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.studio.BuildRunDetails
import com.sherwood.wta.core.studio.BuildStudio
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.GitHubApiClient
import com.sherwood.wta.core.studio.GitHubArtifact
import com.sherwood.wta.core.studio.GitHubRepository
import com.sherwood.wta.core.studio.GitHubResult
import com.sherwood.wta.core.studio.GitHubWorkflow
import com.sherwood.wta.core.studio.GitHubWorkflowRun
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaSection
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import kotlinx.coroutines.launch

@Composable
internal fun StudioBuildsTab(context: Context, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val endpoints = remember { EndpointSettingsStore.current(context) }
    val api = remember { GitHubApiClient(context, endpoints) }
    val buildStudio = remember { BuildStudio(context) }
    val settings = remember { StudioSettingsStore.getInstance(context) }
    val uriHandler = LocalUriHandler.current

    var repoKey by remember { mutableStateOf(settings.lastRepository.orEmpty()) }
    var repositories by remember { mutableStateOf<List<GitHubRepository>>(emptyList()) }
    var workflows by remember { mutableStateOf<List<GitHubWorkflow>>(emptyList()) }
    var runs by remember { mutableStateOf<List<GitHubWorkflowRun>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showRepoPicker by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    var dispatchWorkflow by remember { mutableStateOf<GitHubWorkflow?>(null) }
    var dispatchRef by remember { mutableStateOf("") }

    var detailRun by remember { mutableStateOf<GitHubWorkflowRun?>(null) }
    var detail by remember { mutableStateOf<BuildRunDetails?>(null) }
    var detailArtifacts by remember { mutableStateOf<List<GitHubArtifact>>(emptyList()) }
    var detailLogs by remember { mutableStateOf<String?>(null) }
    var detailBusy by remember { mutableStateOf(false) }

    fun ownerRepo(): Pair<String, String>? {
        val parts = repoKey.split('/', limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0] to parts[1]
        } else {
            null
        }
    }

    suspend fun reload() {
        val pair = ownerRepo() ?: return
        loading = true
        val (owner, repo) = pair
        when (val repoResult = api.getRepository(owner, repo)) {
            is GitHubResult.Success -> {
                dispatchRef = repoResult.value.defaultBranch
            }
            is GitHubResult.Failure -> Unit
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
        loading = false
    }

    LaunchedEffect(Unit) {
        when (val result = api.listRepositories()) {
            is GitHubResult.Success -> {
                repositories = result.value
                if (ownerRepo() == null) {
                    val initial = repositories.firstOrNull()
                    if (initial != null) repoKey = initial.ownerRepo
                }
            }
            is GitHubResult.Failure -> snackbarHostState.showSnackbar(
                result.message ?: Strings.studioConnectFailed
            )
        }
    }

    LaunchedEffect(repoKey, refreshKey) {
        if (ownerRepo() != null) {
            reload()
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
                                    "${artifact.name} · ${artifact.sizeInBytes / 1024} KB",
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
