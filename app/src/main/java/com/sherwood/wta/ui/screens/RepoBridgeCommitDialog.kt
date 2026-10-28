package com.sherwood.wta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.frontend.RepoBridgeCommitFailureKind
import com.sherwood.wta.core.frontend.RepoBridgeCommitResult
import com.sherwood.wta.core.frontend.RepoBridgeChangeSet
import com.sherwood.wta.core.frontend.RepoBridgeCommitUseCase
import com.sherwood.wta.core.frontend.ReviewDecision
import com.sherwood.wta.core.frontend.ReviewItem
import com.sherwood.wta.core.frontend.ReviewStatus
import com.sherwood.wta.core.frontend.blockingIncluded
import com.sherwood.wta.core.frontend.includedItems
import com.sherwood.wta.core.frontend.isMassDeletion
import com.sherwood.wta.core.frontend.unreviewedWarnings
import com.sherwood.wta.core.frontend.RepoBridgePrepareResult
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.ui.design.WtaAlertDialog
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Commit & push" dialog for an imported repository working copy.
 *
 * Opening it first asks the use case for a diff against the imported commit, so
 * the user sees exactly what would be uploaded. The send button stays disabled
 * until there is at least one change and a commit message. Every failure is shown
 * inline with a localized description, and the dialog never closes itself while
 * a push is running.
 */
@Composable
fun RepoBridgeCommitDialog(
    projectDir: File,
    onDismiss: () -> Unit,
    onCommitted: (String) -> Unit = {},
    /** Fetches a fresh copy of the branch into a new project folder. Null hides the action. */
    onReimport: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useCase = remember(projectDir.absolutePath) {
        RepoBridgeCommitUseCase(context.applicationContext, projectDir)
    }

    var attempt by remember { mutableIntStateOf(0) }
    var preparing by remember { mutableStateOf(true) }
    var preparation by remember { mutableStateOf<RepoBridgePrepareResult?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var committing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RepoBridgeCommitResult?>(null) }
    var confirmDeletion by rememberSaveable { mutableStateOf(false) }
    // Exclusions and acknowledgements are the user's decision for one review. They are kept
    // across rotation; a new review (retry) clears them, so nothing carries over silently.
    val excluded = rememberSaveable { mutableStateListOf<String>() }
    val acceptedWarnings = rememberSaveable { mutableStateListOf<String>() }
    var showReview by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(useCase, attempt) {
        preparing = true
        confirmDeletion = false
        preparation = useCase.prepare()
        preparing = false
    }

    val ready = preparation as? RepoBridgePrepareResult.Ready
    val success = result as? RepoBridgeCommitResult.Success
    val failure = result as? RepoBridgeCommitResult.Failure
    val excludedSet = excluded.toSet()
    val acceptedSet = acceptedWarnings.toSet()
    val included = ready?.review?.includedItems(excludedSet).orEmpty()
    val appliedChanges = included.toChangeSet()
    val blocking = ready?.review?.blockingIncluded(excludedSet).orEmpty()
    val unreviewed = ready?.review?.unreviewedWarnings(excludedSet, acceptedSet).orEmpty()
    val massDeletion = ready?.let { appliedChanges.isMassDeletion(it.remoteFileCount) } == true
    val canSend = ready != null &&
        included.isNotEmpty() &&
        blocking.isEmpty() &&
        unreviewed.isEmpty() &&
        message.isNotBlank() &&
        !committing &&
        success == null &&
        (!massDeletion || confirmDeletion)

    fun send() {
        if (!canSend || ready == null) return
        committing = true
        result = null
        // The use case refuses a mass deletion larger than what the user confirmed here,
        // and refuses the push if the working copy no longer matches this review.
        val confirmed = if (confirmDeletion) appliedChanges.deleted.size else 0
        val decision = ReviewDecision(
            fingerprint = ready.review.fingerprint,
            excluded = excludedSet,
            acceptedWarnings = acceptedSet
        )
        scope.launch {
            result = useCase.commit(message, confirmedDeletions = confirmed, decision = decision)
            committing = false
        }
    }

    WtaAlertDialog(
        onDismissRequest = { if (!committing) onDismiss() },
        icon = Icons.Outlined.CloudUpload,
        title = Strings.repoBridgeCommitTitle,
        confirmButton = {
            if (success != null) {
                TextButton(
                    onClick = {
                        onCommitted(success.commitSha)
                        onDismiss()
                    }
                ) {
                    Text(Strings.close)
                }
            } else {
                WtaButton(
                    onClick = { send() },
                    text = if (committing) {
                        Strings.repoBridgeCommitWorking
                    } else {
                        Strings.repoBridgeCommitTitle
                    },
                    size = WtaButtonSize.Small,
                    enabled = canSend
                )
            }
        },
        dismissButton = if (success == null) {
            {
                TextButton(onClick = { if (!committing) onDismiss() }) {
                    Text(Strings.cancel)
                }
            }
        } else {
            null
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (preparing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            Strings.repoBridgeCommitChecking,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    when (val prep = preparation) {
                        is RepoBridgePrepareResult.Failure -> {
                            ErrorBlock(prep.reason, prep.detail)
                            if (prep.reason.isRetryable()) {
                                TextButton(onClick = { attempt++ }) {
                                    Text(Strings.btnRetry)
                                }
                            }
                        }
                        is RepoBridgePrepareResult.Ready -> {
                            Text(
                                "${prep.owner}/${prep.repo} · ${prep.branch}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (prep.changes.isEmpty) {
                                    Strings.repoBridgeCommitNoChanges
                                } else {
                                    Strings.repoBridgeCommitSummary(
                                        appliedChanges.added.size,
                                        appliedChanges.modified.size,
                                        appliedChanges.deleted.size
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!prep.changes.isEmpty) {
                                Text(
                                    Strings.repoBridgeReviewIncludedSummary(
                                        included.size,
                                        excludedSet.size
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { showReview = true }) {
                                    Text(Strings.repoBridgeReviewTitle)
                                }
                            }
                            if (blocking.isNotEmpty()) {
                                Text(
                                    Strings.repoBridgeCommitErrorSecretBlocked,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (unreviewed.isNotEmpty()) {
                                Text(
                                    Strings.repoBridgeCommitErrorSecretWarnings,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (massDeletion) {
                                Text(
                                    Strings.repoBridgeMassDeletionWarning(
                                        appliedChanges.deleted.size,
                                        prep.remoteFileCount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = confirmDeletion,
                                        onCheckedChange = { confirmDeletion = it },
                                        enabled = !committing
                                    )
                                    Text(
                                        Strings.repoBridgeMassDeletionConfirm,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            if (!prep.changes.isEmpty) {
                                OutlinedTextField(
                                    value = message,
                                    onValueChange = { message = it },
                                    label = { Text(Strings.repoBridgeCommitMessageLabel) },
                                    placeholder = { Text(Strings.repoBridgeCommitMessagePlaceholder) },
                                    minLines = 3,
                                    maxLines = 6,
                                    enabled = !committing,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        null -> Unit
                    }
                }

                if (committing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            Strings.repoBridgeCommitWorking,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                failure?.let { ErrorBlock(it.reason, it.detail) }
                if (failure?.reason == RepoBridgeCommitFailureKind.REVIEW_STALE && !committing) {
                    TextButton(
                        onClick = {
                            result = null
                            excluded.clear()
                            acceptedWarnings.clear()
                            confirmDeletion = false
                            attempt++
                        }
                    ) {
                        Text(Strings.btnRetry)
                    }
                }

                val blockingReason = failure?.reason
                    ?: (preparation as? RepoBridgePrepareResult.Failure)?.reason
                if (onReimport != null && !committing && blockingReason?.needsReimport() == true) {
                    Text(
                        Strings.repoBridgeReimportNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onReimport) {
                        Text(Strings.repoBridgeReimport)
                    }
                }

                success?.let {
                    Text(
                        Strings.repoBridgeCommitSuccess(it.commitSha.take(7)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!it.metadataSaved) {
                        Text(
                            Strings.repoBridgeCommitMetadataWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
    if (showReview && ready != null) {
        RepoBridgeReviewScreen(
            review = ready.review,
            excluded = excluded,
            acceptedWarnings = acceptedWarnings,
            onDone = { showReview = false }
        )
    }
}

@Composable
private fun ErrorBlock(
    reason: RepoBridgeCommitFailureKind,
    detail: String?
) {
    Text(
        reason.description(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
    if (!detail.isNullOrBlank()) {
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun RepoBridgeCommitFailureKind.description(): String = when (this) {
    RepoBridgeCommitFailureKind.MASS_DELETION_UNCONFIRMED ->
        Strings.repoBridgeCommitErrorMassDeletion
    RepoBridgeCommitFailureKind.REVIEW_STALE -> Strings.repoBridgeCommitErrorStale
    RepoBridgeCommitFailureKind.SECRET_BLOCKED -> Strings.repoBridgeCommitErrorSecretBlocked
    RepoBridgeCommitFailureKind.SECRET_WARNINGS_UNACCEPTED -> Strings.repoBridgeCommitErrorSecretWarnings
    RepoBridgeCommitFailureKind.FILE_TOO_LARGE -> Strings.repoBridgeCommitErrorTooLarge
    RepoBridgeCommitFailureKind.NO_METADATA,
    RepoBridgeCommitFailureKind.TOO_MANY_FILES,
    RepoBridgeCommitFailureKind.IO_ERROR,
    RepoBridgeCommitFailureKind.NO_COMMIT_MESSAGE ->
        Strings.repoBridgeCommitErrorLocal
    RepoBridgeCommitFailureKind.NO_BASE -> Strings.repoBridgeCommitErrorNoBase
    RepoBridgeCommitFailureKind.NO_TOKEN -> Strings.repoBridgeCommitErrorNoToken
    RepoBridgeCommitFailureKind.UNAUTHORIZED -> Strings.repoBridgeCommitErrorAuth
    RepoBridgeCommitFailureKind.FORBIDDEN -> Strings.repoBridgeCommitErrorForbidden
    RepoBridgeCommitFailureKind.NOT_FOUND -> Strings.repoBridgeCommitErrorNotFound
    RepoBridgeCommitFailureKind.RATE_LIMITED -> Strings.repoBridgeCommitErrorRateLimit
    RepoBridgeCommitFailureKind.CONFLICT -> Strings.repoBridgeCommitErrorConflict
    RepoBridgeCommitFailureKind.NETWORK -> Strings.repoBridgeCommitErrorNetwork
    RepoBridgeCommitFailureKind.REMOTE_REJECTED,
    RepoBridgeCommitFailureKind.UNEXPECTED ->
        Strings.repoBridgeCommitErrorRemote
    RepoBridgeCommitFailureKind.NO_CHANGES -> Strings.repoBridgeCommitNoChanges
}

/** The changed files of [this] set, as a change set for the mass-deletion check. */
private fun List<ReviewItem>.toChangeSet(): RepoBridgeChangeSet = RepoBridgeChangeSet(
    added = filter { it.status == ReviewStatus.ADDED }.map { it.path },
    modified = filter { it.status == ReviewStatus.MODIFIED }.map { it.path },
    deleted = filter { it.status == ReviewStatus.DELETED }.map { it.path }
)

/** Failures where the local copy can no longer be pushed and a fresh import is the fix. */
private fun RepoBridgeCommitFailureKind.needsReimport(): Boolean =
    this == RepoBridgeCommitFailureKind.CONFLICT || this == RepoBridgeCommitFailureKind.NO_BASE

private fun RepoBridgeCommitFailureKind.isRetryable(): Boolean = when (this) {
    RepoBridgeCommitFailureKind.NETWORK,
    RepoBridgeCommitFailureKind.RATE_LIMITED,
    RepoBridgeCommitFailureKind.UNAUTHORIZED,
    RepoBridgeCommitFailureKind.FORBIDDEN,
    RepoBridgeCommitFailureKind.REMOTE_REJECTED,
    RepoBridgeCommitFailureKind.UNEXPECTED -> true
    else -> false
}
