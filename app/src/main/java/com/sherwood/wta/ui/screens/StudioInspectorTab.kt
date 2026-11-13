package com.sherwood.wta.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHostState
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
import com.sherwood.wta.core.studio.ArtifactInspector
import com.sherwood.wta.core.studio.ArtifactVault
import com.sherwood.wta.core.studio.VaultArtifact
import com.sherwood.wta.ui.design.WtaRowTone
import com.sherwood.wta.ui.design.WtaSection
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Studio Inspector: everything known about one stored artifact, recomputed from
 * the file itself.
 *
 * The four kinds of claim are shown separately and never merged into one
 * "trusted" badge:
 *
 *  - **Integrity** — the SHA-256 recomputed now vs the one recorded at import.
 *  - **Signature** — whether the APK/AAB signature actually verified.
 *  - **Package identity** — package name, version and SDK levels from the manifest.
 *  - **Provenance** — which repository/commit/workflow run produced the file.
 *
 * A matching hash says nothing about who signed the file, and a verified
 * signature says nothing about where the file came from, so the screen keeps
 * them apart and never claims more than the verification returned.
 */
@Composable
internal fun StudioInspectorTab(context: Context, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val vault = remember { ArtifactVault.getInstance(context) }
    val inspector = remember { ArtifactInspector(context, vault) }
    var artifacts by remember { mutableStateOf<List<VaultArtifact>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ArtifactInspector.Report?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { artifacts = vault.list() }

    fun inspect(artifact: VaultArtifact) {
        selectedId = artifact.id
        report = null
        scope.launch {
            loading = true
            report = withContext(Dispatchers.IO) { inspector.inspect(artifact) }
            loading = false
        }
    }

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
        item {
            WtaSection(title = Strings.studioInspector) {
                WtaSettingCard {
                    if (artifacts.isEmpty()) {
                        WtaSettingRow(title = Strings.studioSelectArtifact)
                    }
                    artifacts.forEachIndexed { index, artifact ->
                        if (index > 0) WtaSectionDivider()
                        WtaSettingRow(
                            title = artifact.fileName,
                            subtitle = buildString {
                                append(artifact.kind)
                                artifact.versionName?.let { append(" · ").append(it) }
                            },
                            active = artifact.id == selectedId,
                            onClick = { inspect(artifact) }
                        )
                    }
                }
            }
        }
        if (loading) {
            item { StudioBusy(Strings.loading) }
        }
        val current = report
        if (current != null) {
            item {
                WtaSection(title = Strings.studioIntegrity) {
                    WtaSettingCard {
                        WtaSettingRow(
                            title = if (current.sha256Matches) Strings.studioVerified
                            else Strings.studioMismatch,
                            subtitle = current.sha256,
                            tone = if (current.sha256Matches) WtaRowTone.Normal else WtaRowTone.Danger
                        )
                        WtaSectionDivider()
                        WtaSettingRow(
                            title = signatureTitle(current),
                            subtitle = buildString {
                                if (current.signerSha256 != null) {
                                    append(current.signerSha256)
                                } else {
                                    append(Strings.studioUnsigned)
                                }
                                current.signatureScheme?.let { append(" · ").append(it) }
                                current.signatureErrors.firstOrNull()?.let { append(" · ").append(it) }
                            },
                            tone = if (current.signatureVerified) WtaRowTone.Normal else WtaRowTone.Danger
                        )
                    }
                }
            }
            item {
                WtaSection(title = Strings.studioPackageName) {
                    WtaSettingCard {
                        WtaSettingRow(title = current.packageName ?: Strings.studioPackageUnknown)
                        WtaSectionDivider()
                        WtaSettingRow(
                            title = Strings.studioVersionLabel,
                            subtitle = buildString {
                                append(current.versionName ?: "—")
                                current.versionCode?.let { append(" (").append(it).append(")") }
                            }
                        )
                        WtaSectionDivider()
                        WtaSettingRow(
                            title = Strings.studioSdkLevels,
                            subtitle = "${current.minSdk ?: "—"} / ${current.targetSdk ?: "—"}"
                        )
                        WtaSectionDivider()
                        WtaSettingRow(
                            title = current.fileName,
                            subtitle = "${Strings.studioFileSizeKb(current.sizeBytes / 1024)} · ${current.kind}"
                        )
                    }
                }
            }
            item {
                WtaSection(title = Strings.studioProvenance) {
                    WtaSettingCard {
                        WtaSettingRow(title = current.sourceDisplay, subtitle = current.workflowName)
                        current.commitSha?.let { sha ->
                            WtaSectionDivider()
                            WtaSettingRow(
                                title = Strings.studioCommitLabel(sha.take(10)),
                                subtitle = current.runNumber?.let { Strings.studioRunNumber(it) }
                            )
                        }
                        current.signedFromSha256?.let { from ->
                            WtaSectionDivider()
                            WtaSettingRow(title = Strings.studioSignedFrom(from.take(12)))
                        }
                        current.warnings.forEach { warning ->
                            WtaSectionDivider()
                            WtaSettingRow(title = warning, tone = WtaRowTone.Danger)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.fillMaxWidth().height(8.dp)) }
    }
}

/** States exactly what the verification produced — never a blanket "trusted". */
private fun signatureTitle(report: ArtifactInspector.Report): String = when {
    report.signatureVerified -> Strings.studioSignatureOk
    report.signerSha256 == null && report.signatureErrors.isEmpty() -> Strings.studioUnsigned
    else -> Strings.studioSignatureBad
}
