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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.studio.git.ChecksState
import com.sherwood.wta.core.studio.git.GitDiffLines
import com.sherwood.wta.core.studio.git.GitProvider
import com.sherwood.wta.core.studio.git.GitProviderLookup
import com.sherwood.wta.core.studio.git.GitProviderRegistry
import com.sherwood.wta.core.studio.git.GitRepoRef
import com.sherwood.wta.core.studio.git.GitResult
import com.sherwood.wta.core.studio.git.GitStudio
import com.sherwood.wta.core.studio.git.GitBranch
import com.sherwood.wta.core.studio.git.GitCheckSummary
import com.sherwood.wta.core.studio.git.GitChangeRequest
import com.sherwood.wta.core.studio.git.GitChangeRequestDetails
import com.sherwood.wta.core.studio.git.GitChangeRequestFile
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.ui.components.PremiumTextField
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaRowTone
import com.sherwood.wta.ui.design.WtaSection
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import kotlinx.coroutines.launch

private data class InboxEntry(
    val ownerRepo: String,
    val pulls: List<GitChangeRequest> = emptyList(),
    val branches: List<GitBranch> = emptyList(),
    val error: String? = null
)

/** Everything fetched when a pull request is expanded. */
private data class PrDetail(
    val details: GitChangeRequestDetails,
    val files: List<GitChangeRequestFile>,
    val checks: GitCheckSummary
)

private fun <T> GitResult<T>.valueOrNull(): T? = when (this) {
    is GitResult.Success -> value
    is GitResult.Failure -> null
}

@Composable
internal fun StudioInboxTab(context: Context, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val settings = remember { StudioSettingsStore.getInstance(context) }
    // The active connection; the capability flags decide which controls exist.
    val studio = remember { GitStudio.state(context) }
    val provider = remember(studio.id, studio.baseUrl) {
        (GitProviderRegistry.active(context) as? GitProviderLookup.Available)?.provider
    }
    val capabilities = studio.capabilities
    val watchlist = remember(studio.id) { settings.watchlist(studio.id) }
    val uriHandler = LocalUriHandler.current
    var entries by remember { mutableStateOf<List<InboxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<PrDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var reviewText by remember { mutableStateOf("") }
    var requestChanges by remember { mutableStateOf(false) }
    var reviewBusy by remember { mutableStateOf(false) }
    var inlinePath by remember { mutableStateOf<String?>(null) }
    var inlineLine by remember { mutableStateOf("") }
    var inlineBody by remember { mutableStateOf("") }
    var inlineBusy by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        val active = provider
        entries = watchlist.map { ownerRepo ->
            val parts = ownerRepo.split('/', limit = 2)
            val repoRef = if (parts.size == 2) GitRepoRef.of(parts[0], parts[1]) else null
            if (active == null || repoRef == null) {
                return@map InboxEntry(ownerRepo, error = Strings.studioConnectFailed)
            }
            val pulls = active.changeRequests(repoRef, "open")
            val branches = active.branches(repoRef)
            InboxEntry(
                ownerRepo = ownerRepo,
                pulls = pulls.valueOrNull().orEmpty(),
                branches = branches.valueOrNull().orEmpty(),
                error = (pulls as? GitResult.Failure)?.message
                    ?: (branches as? GitResult.Failure)?.message
            )
        }
        loading = false
    }

    suspend fun loadDetail(owner: String, repo: String, pr: GitChangeRequest) {
        detailLoading = true
        detail = null
        val repoRef = GitRepoRef.of(owner, repo)
        val active = provider
        val details = if (repoRef == null || active == null) null
        else active.changeRequest(repoRef, pr.number).valueOrNull()
        if (details == null || repoRef == null || active == null) {
            detailLoading = false
            snackbarHostState.showSnackbar(Strings.studioConnectFailed)
            return
        }
        val files = active.changeRequestFiles(repoRef, pr.number).valueOrNull().orEmpty()
        val checks = active.checks(repoRef, details.headSha).valueOrNull()
            ?: GitCheckSummary(
                state = ChecksState.UNKNOWN,
                total = 0,
                pending = 0,
                passing = 0,
                failing = 0,
                checks = emptyList(),
                combinedState = null
            )
        detail = PrDetail(details, files, checks)
        detailLoading = false
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
        if (watchlist.isEmpty()) {
            item { StudioEmptyState(Strings.studioInboxEmpty) }
        }
        if (loading) {
            item { StudioBusy(Strings.loading) }
        }
        items(entries, key = { it.ownerRepo }) { entry ->
            val parts = entry.ownerRepo.split('/', limit = 2)
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
                        }
                        entry.pulls.forEachIndexed { index, pr ->
                            val key = "${entry.ownerRepo}#${pr.number}"
                            if (index > 0) WtaSectionDivider()
                            WtaSettingRow(
                                title = Strings.studioPullRequest(pr.number, pr.title),
                                subtitle = "${pr.author} · ${pr.headRef} → ${pr.baseRef}" +
                                    if (pr.draft) " · ${Strings.studioPrDraft}" else "",
                                active = expandedKey == key,
                                onClick = {
                                    if (parts.size != 2) return@WtaSettingRow
                                    if (expandedKey == key) {
                                        expandedKey = null
                                        detail = null
                                        return@WtaSettingRow
                                    }
                                    expandedKey = key
                                    reviewText = ""
                                    requestChanges = false
                                    inlinePath = null
                                    inlineLine = ""
                                    inlineBody = ""
                                    scope.launch { loadDetail(parts[0], parts[1], pr) }
                                },
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        // Merging is a write; a read-only driver does not
                                        // offer the button at all.
                                        if (capabilities?.mergeChangeRequests == true) {
                                            WtaButton(
                                                text = Strings.studioMerge,
                                                size = WtaButtonSize.Small,
                                                onClick = {
                                                    if (parts.size != 2) return@WtaButton
                                                    val repoRef = GitRepoRef.of(parts[0], parts[1])
                                                    val active = provider
                                                    if (repoRef == null || active == null) {
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                Strings.studioConnectFailed
                                                            )
                                                        }
                                                        return@WtaButton
                                                    }
                                                    scope.launch {
                                                        when (
                                                            val result = active.mergeChangeRequest(
                                                                repoRef,
                                                                pr.number
                                                            )
                                                        ) {
                                                            is GitResult.Success ->
                                                                snackbarHostState.showSnackbar(
                                                                    Strings.studioMerged(pr.headRef)
                                                                )
                                                            is GitResult.Failure ->
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
                                }
                            )
                            if (expandedKey == key) {
                                if (detailLoading) {
                                    StudioBusy(Strings.loading)
                                } else {
                                    detail?.let { loaded ->
                                        PrDetailSection(
                                            capabilities = capabilities,
                                            detail = loaded,
                                            reviewText = reviewText,
                                            onReviewTextChange = { reviewText = it },
                                            requestChanges = requestChanges,
                                            onRequestChangesChange = { requestChanges = it },
                                            reviewBusy = reviewBusy,
                                            inlinePath = inlinePath,
                                            inlineLine = inlineLine,
                                            inlineBody = inlineBody,
                                            inlineBusy = inlineBusy,
                                            onInlinePathChange = { inlinePath = it },
                                            onInlineLineChange = { inlineLine = it },
                                            onInlineBodyChange = { inlineBody = it },
                                            onOpenOnGitHub = { url ->
                                                url.takeIf { it.startsWith("https://") }
                                                    ?.let { uriHandler.openUri(it) }
                                            },
                                            onSubmitInlineComment = { path ->
                                                val headSha = loaded.details.headSha
                                                val lineNumber = inlineLine.toIntOrNull() ?: 0
                                                if (parts.size != 2) {
                                                    Unit
                                                } else if (headSha.isBlank()) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            Strings.studioConnectFailed
                                                        )
                                                    }
                                                } else if (lineNumber <= 0) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            Strings.studioPrLineInvalid
                                                        )
                                                    }
                                                } else {
                                                    scope.launch {
                                                        inlineBusy = true
                                                        val repoRef = GitRepoRef.of(parts[0], parts[1])
                                                        val active = provider
                                                        val result = if (repoRef == null || active == null) {
                                                            GitResult.Failure(
                                                                com.sherwood.wta.core.studio.git
                                                                    .GitFailureKind.UNSUPPORTED,
                                                                message = Strings.studioReadOnlyAction
                                                            )
                                                        } else {
                                                            active.addReviewComment(
                                                                ref = repoRef,
                                                                number = pr.number,
                                                                commitId = headSha,
                                                                path = path,
                                                                line = lineNumber,
                                                                body = inlineBody
                                                            )
                                                        }
                                                        when (result) {
                                                            is GitResult.Success -> {
                                                                inlineBody = ""
                                                                inlineLine = ""
                                                                inlinePath = null
                                                                snackbarHostState.showSnackbar(
                                                                    Strings.studioPrInlineCommentSent
                                                                )
                                                            }
                                                            is GitResult.Failure ->
                                                                snackbarHostState.showSnackbar(
                                                                    result.message
                                                                        ?: Strings.studioConnectFailed
                                                                )
                                                        }
                                                        inlineBusy = false
                                                    }
                                                }
                                            },
                                            onSubmitReview = {
                                                if (parts.size != 2) return@PrDetailSection
                                                scope.launch {
                                                    reviewBusy = true
                                                    val repoRef = GitRepoRef.of(parts[0], parts[1])
                                                    val active = provider
                                                    val result = if (repoRef == null || active == null) {
                                                        GitResult.Failure(
                                                            com.sherwood.wta.core.studio.git
                                                                .GitFailureKind.UNSUPPORTED,
                                                            message = Strings.studioReadOnlyAction
                                                        )
                                                    } else {
                                                        active.submitReview(
                                                            ref = repoRef,
                                                            number = pr.number,
                                                            body = reviewText,
                                                            requestChanges = requestChanges
                                                        )
                                                    }
                                                    when (result) {
                                                        is GitResult.Success -> {
                                                            reviewText = ""
                                                            snackbarHostState.showSnackbar(
                                                                Strings.studioPrReviewSent
                                                            )
                                                        }
                                                        is GitResult.Failure ->
                                                            snackbarHostState.showSnackbar(
                                                                result.message
                                                                    ?: Strings.studioConnectFailed
                                                            )
                                                    }
                                                    reviewBusy = false
                                                }
                                            }
                                        )
                                    }
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
                                WtaSettingRow(
                                    title = branch.name,
                                    subtitle = branch.sha.take(10),
                                    onClick = {
                                        if (parts.size == 2) {
                                            uriHandler.openUri(
                                                "${studio.baseUrl}/${parts[0]}/${parts[1]}/tree/${branch.name}"
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

/**
 * Expanded pull request: identity, mergeability, changed files, CI state and a
 * review-comment box. The merge button lives on the collapsed row; nothing here
 * approves or merges automatically.
 */
@Composable
private fun PrDetailSection(
    capabilities: com.sherwood.wta.core.studio.git.GitCapabilities?,
    detail: PrDetail,
    reviewText: String,
    onReviewTextChange: (String) -> Unit,
    requestChanges: Boolean,
    onRequestChangesChange: (Boolean) -> Unit,
    reviewBusy: Boolean,
    inlinePath: String?,
    inlineLine: String,
    inlineBody: String,
    inlineBusy: Boolean,
    onInlinePathChange: (String?) -> Unit,
    onInlineLineChange: (String) -> Unit,
    onInlineBodyChange: (String) -> Unit,
    onOpenOnGitHub: (String) -> Unit,
    onSubmitReview: () -> Unit,
    onSubmitInlineComment: (String) -> Unit
) {
    val pull = detail.details.pull
    WtaSectionDivider()
    WtaSettingRow(
        title = Strings.studioPrFiles(detail.details.changedFiles),
        subtitle = "+${detail.details.additions} / -${detail.details.deletions}"
    )
    WtaSectionDivider()
    WtaSettingRow(
        title = Strings.studioPrChecks,
        subtitle = checksSummary(detail.checks),
        tone = when (detail.checks.state) {
            ChecksState.FAILING -> WtaRowTone.Danger
            else -> WtaRowTone.Normal
        },
        onClick = { pull.htmlUrl.takeIf { it.isNotBlank() }?.let(onOpenOnGitHub) }
    )
    detail.checks.checks.take(6).forEach { check ->
        WtaSettingRow(
            title = check.name,
            subtitle = buildString {
                append(check.status)
                check.conclusion?.let { append(" · ").append(it) }
            },
            titleMaxLines = 1,
            onClick = { check.detailsUrl?.takeIf { it.startsWith("https://") }?.let(onOpenOnGitHub) }
        )
    }
    val mergeable = detail.details.mergeable
    WtaSectionDivider()
    WtaSettingRow(
        title = if (mergeable == false) Strings.studioPrConflicting else Strings.studioPrMergeable,
        subtitle = detail.details.mergeableState,
        tone = if (mergeable == false) WtaRowTone.Danger else WtaRowTone.Normal
    )
    detail.files.take(20).forEach { file ->
        WtaSettingRow(
            title = file.filename,
            subtitle = "${file.status} · +${file.additions} / -${file.deletions}",
            titleMaxLines = 1,
            active = inlinePath == file.filename,
            onClick = { pull.htmlUrl.takeIf { it.isNotBlank() }?.let(onOpenOnGitHub) },
            trailing = {
                if (capabilities?.inlineComments == true) {
                    WtaButton(
                        text = Strings.studioPrInlineComment,
                        size = WtaButtonSize.Small,
                        variant = WtaButtonVariant.Outlined,
                        onClick = {
                            onInlinePathChange(
                                if (inlinePath == file.filename) null else file.filename
                            )
                            onInlineLineChange("")
                            onInlineBodyChange("")
                        }
                    )
                }
            }
        )
        if (inlinePath == file.filename) {
            InlineCommentComposer(
                fileName = file.filename,
                patch = file.patch,
                line = inlineLine,
                onLineChange = onInlineLineChange,
                body = inlineBody,
                onBodyChange = onInlineBodyChange,
                busy = inlineBusy,
                onSubmit = { onSubmitInlineComment(file.filename) },
                onClose = {
                    onInlinePathChange(null)
                    onInlineLineChange("")
                    onInlineBodyChange("")
                }
            )
        }
    }
    pull.body?.takeIf { it.isNotBlank() }?.let { bodyText ->
        WtaSectionDivider()
        WtaSettingRow(title = Strings.studioPrBody, subtitle = bodyText)
    }
    if (capabilities?.reviews != true) {
        WtaSectionDivider()
        WtaSettingRow(
            title = Strings.studioPrReview,
            subtitle = Strings.studioReadOnlyAction
        )
        return
    }
    WtaSectionDivider()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WtaSpacing.RowHorizontal, vertical = WtaSpacing.ContentGap),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PremiumTextField(
            value = reviewText,
            onValueChange = onReviewTextChange,
            label = { Text(Strings.studioPrReview) },
            singleLine = false
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WtaButton(
                text = if (requestChanges) Strings.studioPrRequestChanges
                else Strings.studioPrReview,
                size = WtaButtonSize.Small,
                variant = if (requestChanges) WtaButtonVariant.Tonal
                else WtaButtonVariant.Outlined,
                onClick = { onRequestChangesChange(!requestChanges) }
            )
            TextButton(
                enabled = !reviewBusy && reviewText.isNotBlank(),
                onClick = onSubmitReview
            ) {
                Text(if (reviewBusy) Strings.loading else Strings.studioPrReview)
            }
        }
    }
}

/**
 * Composer for one review comment on a single line of a changed file. The line number is
 * the *new side* line, which is what GitHub's review-comment endpoint expects.
 */
@Composable
private fun InlineCommentComposer(
    fileName: String,
    patch: String?,
    line: String,
    onLineChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    busy: Boolean,
    onSubmit: () -> Unit,
    onClose: () -> Unit
) {
    // GitHub only accepts comments on lines that exist on the new side, so the
    // suggestion list comes from the diff itself. When the patch is missing (binary
    // or oversized file) we keep the number field and say nothing about validity.
    val suggestable = remember(patch) {
        GitDiffLines.commentableNewLines(patch, limit = 18)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WtaSpacing.RowHorizontal, vertical = WtaSpacing.ContentGap),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        PremiumTextField(
            value = line,
            onValueChange = { input -> onLineChange(input.filter { it.isDigit() }) },
            label = { Text(Strings.studioPrLineNumber) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        if (suggestable.isNotEmpty()) {
            Text(
                text = Strings.studioPrCommentableLines,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            suggestable.chunked(6).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.forEach { candidate ->
                        TextButton(onClick = { onLineChange(candidate.toString()) }) {
                            Text(candidate.toString())
                        }
                    }
                }
            }
        }
        PremiumTextField(
            value = body,
            onValueChange = onBodyChange,
            label = { Text(Strings.studioPrInlineComment) },
            singleLine = false
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !busy && body.isNotBlank() && line.isNotBlank(),
                onClick = onSubmit
            ) {
                Text(if (busy) Strings.loading else Strings.studioPrInlineComment)
            }
            TextButton(enabled = !busy, onClick = onClose) {
                Text(Strings.cancel)
            }
        }
    }
}

/** States exactly what GitHub reported — an absent status is never shown as success. */
private fun checksSummary(checks: GitCheckSummary): String = when (checks.state) {
    ChecksState.PASSING -> Strings.studioPrChecksPassed
    ChecksState.PENDING ->
        if (checks.pending > 0) Strings.studioPrChecksPending(checks.pending)
        else Strings.studioPrChecksPending(checks.total)
    ChecksState.FAILING -> Strings.studioPrChecksFailed(checks.failing.coerceAtLeast(1))
    ChecksState.UNKNOWN -> Strings.studioPrChecksUnknown
}
