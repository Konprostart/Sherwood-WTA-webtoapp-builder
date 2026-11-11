package com.sherwood.wta.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.GitHubApiClient
import com.sherwood.wta.core.studio.GitHubBranch
import com.sherwood.wta.core.studio.GitHubPullRequest
import com.sherwood.wta.core.studio.GitHubResult
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

private data class InboxEntry(
    val ownerRepo: String,
    val pulls: List<GitHubPullRequest> = emptyList(),
    val branches: List<GitHubBranch> = emptyList(),
    val error: String? = null
)

private fun <T> GitHubResult<T>.valueOrNull(): T? = when (this) {
    is GitHubResult.Success -> value
    is GitHubResult.Failure -> null
}

@Composable
internal fun StudioInboxTab(context: Context, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val endpoints = remember { EndpointSettingsStore.current(context) }
    val api = remember { GitHubApiClient(context, endpoints) }
    val settings = remember { StudioSettingsStore.getInstance(context) }
    val uriHandler = LocalUriHandler.current
    var entries by remember { mutableStateOf<List<InboxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        entries = settings.watchlist().map { ownerRepo ->
            val parts = ownerRepo.split('/', limit = 2)
            if (parts.size != 2) {
                return@map InboxEntry(ownerRepo, error = "Invalid repository name")
            }
            val (owner, repo) = parts
            val pulls = api.listPullRequests(owner, repo)
            val branches = api.listBranches(owner, repo)
            InboxEntry(
                ownerRepo = ownerRepo,
                pulls = pulls.valueOrNull().orEmpty(),
                branches = branches.valueOrNull().orEmpty(),
                error = (pulls as? GitHubResult.Failure)?.message
                    ?: (branches as? GitHubResult.Failure)?.message
            )
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = WtaSpacing.ScreenHorizontal,
            vertical = WtaSpacing.ScreenVertical
        ),
        verticalArrangement = Arrangement.spacedBy(WtaSpacing.SectionGap)
    ) {
        if (settings.watchlist().isEmpty()) {
            item { StudioEmptyState(Strings.studioInboxEmpty) }
        }
        if (loading) {
            item { StudioBusy(Strings.loading) }
        }
        items(entries, key = { it.ownerRepo }) { entry ->
            WtaSection(title = entry.ownerRepo) {
                WtaSettingCard {
                    if (entry.error != null && entry.pulls.isEmpty() && entry.branches.isEmpty()) {
                        WtaSettingRow(
                            title = Strings.studioConnectFailed,
                            subtitle = entry.error
                        )
                    } else {
                        if (entry.pulls.isEmpty() && entry.branches.isEmpty()) {
                            WtaSettingRow(title = Strings.studioInboxEmpty)
                        } else {
                        if (entry.pulls.isNotEmpty()) {
                            entry.pulls.forEachIndexed { index, pr ->
                                if (index > 0) WtaSectionDivider()
                                val parts = entry.ownerRepo.split('/', limit = 2)
                                WtaSettingRow(
                                    title = Strings.studioPullRequest(pr.number, pr.title),
                                    subtitle = "${pr.author} · ${pr.headRef} → ${pr.baseRef}",
                                    onClick = {
                                        pr.htmlUrl.takeIf { it.startsWith("https://") }
                                            ?.let { uriHandler.openUri(it) }
                                    },
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            WtaButton(
                                                text = Strings.studioMerge,
                                                size = WtaButtonSize.Small,
                                                onClick = {
                                                    if (parts.size != 2) return@WtaButton
                                                    scope.launch {
                                                        when (
                                                            val result = api.mergePullRequest(
                                                                parts[0],
                                                                parts[1],
                                                                pr.number
                                                            )
                                                        ) {
                                                            is GitHubResult.Success ->
                                                                snackbarHostState.showSnackbar(
                                                                    Strings.studioMerged(
                                                                        pr.headRef
                                                                    )
                                                                )
                                                            is GitHubResult.Failure ->
                                                                snackbarHostState.showSnackbar(
                                                                    result.message
                                                                        ?: Strings.studioConnectFailed
                                                                )
                                                        }
                                                        load()
                                                    }
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        }
                        val repoBranches = entry.branches.filter { it.name != entry.branches.firstOrNull()?.name }
                        if (repoBranches.isNotEmpty()) {
                            WtaSectionDivider()
                            WtaSettingRow(
                                title = Strings.studioBranches,
                                subtitle = "${repoBranches.size}"
                            )
                            repoBranches.take(10).forEach { branch ->
                                val parts = entry.ownerRepo.split('/', limit = 2)
                                WtaSettingRow(
                                    title = branch.name,
                                    subtitle = branch.sha.take(10),
                                    onClick = {
                                        if (parts.size == 2) {
                                            uriHandler.openUri(
                                                "${endpoints.githubWebBaseUrl}/${parts[0]}/${parts[1]}/tree/${branch.name}"
                                            )
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
            Spacer(Modifier.fillMaxWidth().padding(4.dp))
        }
    }
}
