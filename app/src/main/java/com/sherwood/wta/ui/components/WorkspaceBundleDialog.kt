package com.sherwood.wta.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.workspace.WorkspaceAllowlist
import com.sherwood.wta.core.workspace.WorkspaceBundleException
import com.sherwood.wta.core.workspace.WorkspaceBundleManager
import com.sherwood.wta.core.workspace.WorkspaceText
import com.sherwood.wta.ui.design.WtaAlertDialog
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaMotion
import com.sherwood.wta.ui.design.WtaStatusBanner
import com.sherwood.wta.ui.design.WtaStatusTone
import com.sherwood.wta.ui.theme.LocalShowDescriptions
import com.sherwood.wta.ui.theme.ifDescriptionsShown
import kotlinx.coroutines.launch

/**
 * Export one session (with its project files) as a `.sherwoodworkspace`, or import such
 * an archive as a new session.
 *
 * The dialog reports the two things a transfer tool has to be honest about: how much it
 * actually carried, and what the safety rules left behind. A silent partial export would
 * look identical to a complete one until the other device opened the archive.
 */
@Composable
fun WorkspaceBundleDialog(onDismiss: () -> Unit) {
    WtaAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Outlined.SwapHoriz,
        title = WorkspaceText.dialogTitle,
        confirmButton = {
            WtaButton(
                onClick = onDismiss,
                text = Strings.close,
                variant = WtaButtonVariant.Text,
                size = WtaButtonSize.Medium
            )
        },
        content = {
            WorkspaceText.dialogDesc.ifDescriptionsShown()?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            WorkspaceBundleControls()
        }
    )
}

@Composable
private fun ColumnScope.WorkspaceBundleControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { WorkspaceBundleManager(context) }

    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf("") }
    var errorThrowable by remember { mutableStateOf<Throwable?>(null) }
    var errorScope by remember { mutableStateOf("") }
    val isBusy = isExporting || isImporting

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(WorkspaceAllowlist.MIME_TYPE)
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isExporting = true
                val result = manager.exportWorkspace(
                    outputUri = uri,
                    onProgress = { _, _, message -> progressMessage = message }
                )
                isExporting = false
                progressMessage = ""
                result.onSuccess { exportResult ->
                    Toast.makeText(
                        context,
                        WorkspaceText.exportSummary(
                            sessionTitle = exportResult.title.ifBlank { WorkspaceText.dialogTitle },
                            files = exportResult.fileCount,
                            omitted = exportResult.omittedCount
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { e ->
                    if (e is WorkspaceBundleException.NoCurrentSession) {
                        // Not an error to debug — there is simply nothing to export yet.
                        Toast.makeText(context, WorkspaceText.noCurrentSession, Toast.LENGTH_LONG).show()
                    } else {
                        errorScope = "Workspace export"
                        errorThrowable = e
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                val result = manager.importWorkspace(
                    inputUri = uri,
                    onProgress = { _, _, message -> progressMessage = message }
                )
                isImporting = false
                progressMessage = ""
                result.onSuccess { imported ->
                    Toast.makeText(
                        context,
                        WorkspaceText.importSummary(
                            sessionTitle = imported.title.ifBlank { WorkspaceText.dialogTitle },
                            files = imported.fileCount,
                            messages = imported.messageCount
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { e ->
                    errorScope = "Workspace import"
                    errorThrowable = e
                }
            }
        }
    }

    errorThrowable?.let { err ->
        WtaErrorDialog(
            scope = errorScope,
            message = err.message,
            throwable = err,
            onDismiss = { errorThrowable = null }
        )
    }

    AnimatedVisibility(
        visible = isBusy,
        enter = fadeIn(WtaMotion.enterTween()),
        exit = fadeOut(WtaMotion.exitTween())
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
            if (progressMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    progressMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WtaButton(
            onClick = {
                // The name depends on the open session, which is a suspend read; the
                // SAF dialog is launched from the main dispatcher once it resolves.
                scope.launch { exportLauncher.launch(manager.suggestedFileName()) }
            },
            text = WorkspaceText.exportCurrent,
            leadingIcon = Icons.Outlined.Download,
            enabled = !isBusy,
            modifier = Modifier.weight(1f)
        )
        WtaButton(
            onClick = { importLauncher.launch(arrayOf(WorkspaceAllowlist.MIME_TYPE, "application/octet-stream")) },
            text = WorkspaceText.importBundle,
            leadingIcon = Icons.Outlined.Upload,
            enabled = !isBusy,
            modifier = Modifier.weight(1f)
        )
    }

    AnimatedVisibility(
        visible = !isBusy && LocalShowDescriptions.current,
        enter = fadeIn(WtaMotion.enterTween()),
        exit = fadeOut(WtaMotion.exitTween())
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(12.dp))
            WtaStatusBanner(
                message = WorkspaceText.keptOutNote,
                tone = WtaStatusTone.Info
            )
        }
    }
}
