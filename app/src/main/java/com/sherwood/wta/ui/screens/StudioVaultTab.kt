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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.studio.ArtifactInstaller
import com.sherwood.wta.core.studio.ArtifactKind
import com.sherwood.wta.core.studio.ArtifactVault
import com.sherwood.wta.core.studio.SigningStudio
import com.sherwood.wta.core.studio.VaultArtifact
import com.sherwood.wta.core.studio.VaultVerification
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import kotlinx.coroutines.launch

@Composable
internal fun StudioVaultTab(context: Context, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val vault = remember { ArtifactVault.getInstance(context) }
    val signing = remember { SigningStudio(context) }
    var artifacts by remember { mutableStateOf<List<VaultArtifact>>(emptyList()) }
    var verifications by remember { mutableStateOf<Map<String, VaultVerification>>(emptyMap()) }
    var deleting by remember { mutableStateOf<VaultArtifact?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        artifacts = vault.list()
    }

    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = WtaSpacing.ScreenHorizontal,
            vertical = WtaSpacing.ScreenVertical
        ),
        verticalArrangement = Arrangement.spacedBy(WtaSpacing.SectionGap)
    ) {
        if (artifacts.isEmpty()) {
            item { StudioEmptyState(Strings.studioVaultEmpty) }
        }
        if (busy) {
            item { StudioBusy(Strings.loading) }
        }
        items(artifacts, key = { it.id }) { artifact ->
            WtaSettingCard {
                WtaSettingRow(
                    title = artifact.fileName,
                    subtitle = buildString {
                        append(artifact.kind)
                        artifact.versionName?.let { append(" · ").append(it) }
                        append(" · ").append(artifact.sizeBytes / 1024).append(" KB")
                    }
                )
                WtaSectionDivider()
                WtaSettingRow(
                    title = "SHA-256",
                    subtitle = artifact.sha256.take(32) + "…",
                    titleMaxLines = 1
                )
                val source = buildString {
                    artifact.sourceOwner?.let { append(it).append("/") }
                    artifact.sourceRepo?.let { append(it) }
                    artifact.branch?.let { append(" · ").append(it) }
                }.trim()
                if (source.isNotEmpty()) {
                    WtaSectionDivider()
                    WtaSettingRow(title = source, subtitle = artifact.workflowName)
                }
                artifact.commitSha?.let { sha ->
                    WtaSectionDivider()
                    WtaSettingRow(
                        title = "commit ${sha.take(10)}",
                        subtitle = artifact.runNumber?.let { "run #$it" }
                    )
                }
                artifact.signedFromSha256?.let { from ->
                    WtaSectionDivider()
                    WtaSettingRow(title = Strings.studioSignedFrom(from.take(12)))
                }
                WtaSectionDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WtaSpacing.RowHorizontal, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WtaButton(
                        text = Strings.studioVerify,
                        size = WtaButtonSize.Small,
                        onClick = {
                            val verification = vault.verify(artifact)
                            verifications = verifications + (artifact.id to verification)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (verification.sha256Matches) Strings.studioVerified
                                    else Strings.studioMismatch
                                )
                            }
                        }
                    )
                    if (artifact.kindEnum != ArtifactKind.UNKNOWN) {
                        WtaButton(
                            text = Strings.studioSign,
                            size = WtaButtonSize.Small,
                            variant = WtaButtonVariant.Outlined,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    signing.sign(artifact)
                                        .onSuccess {
                                            snackbarHostState.showSnackbar(Strings.studioSigned)
                                            reload()
                                        }
                                        .onFailure {
                                            snackbarHostState.showSnackbar(
                                                it.message ?: Strings.studioConnectFailed
                                            )
                                        }
                                    busy = false
                                }
                            }
                        )
                    }
                    if (artifact.kindEnum == ArtifactKind.APK) {
                        WtaButton(
                            text = Strings.install,
                            size = WtaButtonSize.Small,
                            variant = WtaButtonVariant.Outlined,
                            onClick = {
                                ArtifactInstaller.install(context, vault.fileFor(artifact))
                                    .onFailure {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                it.message ?: Strings.studioConnectFailed
                                            )
                                        }
                                    }
                            }
                        )
                    }
                    WtaButton(
                        text = Strings.delete,
                        size = WtaButtonSize.Small,
                        variant = WtaButtonVariant.Text,
                        onClick = { deleting = artifact }
                    )
                }
                val verification = verifications[artifact.id]
                if (verification != null) {
                    WtaSectionDivider()
                    WtaSettingRow(
                        title = if (verification.sha256Matches) Strings.studioVerified
                        else Strings.studioMismatch,
                        subtitle = verification.signature?.let { signature ->
                            buildString {
                                append(Strings.studioSigner).append(": ")
                                append(signature.scheme ?: "—")
                                if (signature.verified) append(" ✓")
                            }
                        }
                    )
                } else if (artifact.signatureVerified) {
                    WtaSectionDivider()
                    WtaSettingRow(
                        title = Strings.studioSigner,
                        subtitle = "${artifact.signatureScheme ?: ""} · ${artifact.signerSha256?.take(16) ?: ""}"
                    )
                }
            }
        }
        item { Spacer(Modifier.fillMaxWidth().height(8.dp)) }
    }

    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(Strings.delete) },
            text = { Text(deleting?.fileName.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    val artifact = deleting
                    deleting = null
                    if (artifact != null) {
                        vault.delete(artifact)
                        reload()
                    }
                }) { Text(Strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(Strings.cancel) }
            }
        )
    }
}
