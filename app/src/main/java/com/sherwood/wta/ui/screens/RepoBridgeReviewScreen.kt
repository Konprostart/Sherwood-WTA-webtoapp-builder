package com.sherwood.wta.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sherwood.wta.core.frontend.ReviewFilter
import com.sherwood.wta.core.frontend.ReviewItem
import com.sherwood.wta.core.frontend.ReviewStatus
import com.sherwood.wta.core.frontend.RepoBridgeReview
import com.sherwood.wta.core.frontend.filtered
import com.sherwood.wta.core.frontend.includedItems
import com.sherwood.wta.core.frontend.isolatedPath
import com.sherwood.wta.core.i18n.Strings

/**
 * Fullscreen review of everything a push would touch.
 *
 * Each row is one path with its status and secret findings. The include checkbox controls
 * whether the path is part of the push; a warned file also needs its own acknowledgement,
 * and a blocked file cannot be pushed at all. Exclusions and acknowledgements are written
 * into [excluded] and [acceptedWarnings], which the commit dialog owns, so they survive
 * closing and reopening this screen.
 */
@Composable
fun RepoBridgeReviewScreen(
    review: RepoBridgeReview,
    excluded: SnapshotStateList<String>,
    acceptedWarnings: SnapshotStateList<String>,
    onDone: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(ReviewFilter.ALL) }
    val excludedSet = excluded.toSet()
    val acceptedSet = acceptedWarnings.toSet()
    val visible = review.items.filtered(filter)
    val includedCount = review.includedItems(excludedSet).size

    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Strings.repoBridgeReviewTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDone) {
                        Text(Strings.repoBridgeReviewDone)
                    }
                }
                Text(
                    Strings.repoBridgeReviewIncludedSummary(includedCount, excludedSet.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    items(ReviewFilter.values().toList()) { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = {
                                Text("${filterLabel(option)} ${review.items.filtered(option).size}")
                            }
                        )
                    }
                }

                if (visible.isEmpty()) {
                    Text(
                        Strings.repoBridgeReviewEmpty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(visible, key = { it.path }) { item ->
                        ReviewRow(
                            item = item,
                            included = item.path !in excludedSet,
                            accepted = item.path in acceptedSet,
                            onIncludedChange = { include ->
                                if (include) {
                                    excluded.remove(item.path)
                                } else if (item.path !in excluded) {
                                    excluded.add(item.path)
                                }
                            },
                            onAcceptedChange = { ok ->
                                if (ok) {
                                    if (item.path !in acceptedWarnings) acceptedWarnings.add(item.path)
                                } else {
                                    acceptedWarnings.remove(item.path)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(
    item: ReviewItem,
    included: Boolean,
    accepted: Boolean,
    onIncludedChange: (Boolean) -> Unit,
    onAcceptedChange: (Boolean) -> Unit
) {
    val fileName = item.path.substringAfterLast('/')
    val directory = item.path.substringBeforeLast('/', "")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Checkbox has a 48 dp touch target of its own.
        Checkbox(checked = included, onCheckedChange = onIncludedChange)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp, end = 12.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon(item.status),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = muted
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    statusLabel(item.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted
                )
            }
            Text(
                isolatedPath(fileName),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (directory.isNotEmpty()) {
                Text(
                    isolatedPath(directory),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.findings.isNotEmpty()) {
                val labels = item.findings.map { ruleLabel(it.ruleId) }.distinct().joinToString(" · ")
                Text(
                    labels,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (item.hasBlocking) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    if (item.hasBlocking) {
                        Strings.repoBridgeReviewBlockedHint
                    } else {
                        Strings.repoBridgeReviewWarnHint
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
            }
            if (item.hasWarning && !item.hasBlocking && included) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable { onAcceptedChange(!accepted) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = accepted, onCheckedChange = null)
                    Text(
                        Strings.repoBridgeReviewAcceptWarning,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun filterLabel(filter: ReviewFilter): String = when (filter) {
    ReviewFilter.ALL -> Strings.repoBridgeReviewFilterAll
    ReviewFilter.ADDED -> Strings.repoBridgeReviewFilterAdded
    ReviewFilter.MODIFIED -> Strings.repoBridgeReviewFilterModified
    ReviewFilter.DELETED -> Strings.repoBridgeReviewFilterDeleted
    ReviewFilter.FLAGGED -> Strings.repoBridgeReviewFilterFlagged
}

private fun statusLabel(status: ReviewStatus): String = when (status) {
    ReviewStatus.ADDED -> Strings.repoBridgeReviewFilterAdded
    ReviewStatus.MODIFIED -> Strings.repoBridgeReviewFilterModified
    ReviewStatus.DELETED -> Strings.repoBridgeReviewFilterDeleted
}

private fun statusIcon(status: ReviewStatus) = when (status) {
    ReviewStatus.ADDED -> Icons.Outlined.Add
    ReviewStatus.MODIFIED -> Icons.Outlined.Edit
    ReviewStatus.DELETED -> Icons.Outlined.Delete
}

/** Rule ids map to four user-facing categories; the exact rule is not shown. */
private fun ruleLabel(ruleId: String): String = when (ruleId) {
    "private_key" -> Strings.repoBridgeRulePrivateKey
    "github_token", "aws_access_key", "google_api_key", "slack_token", "stripe_live_key" ->
        Strings.repoBridgeRuleAccessToken
    "generic_assignment" -> Strings.repoBridgeRuleSecretValue
    else -> Strings.repoBridgeRuleSensitiveFile
}
