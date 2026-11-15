package com.sherwood.wta.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.studio.ArtifactExport
import com.sherwood.wta.core.studio.ArtifactInstaller
import com.sherwood.wta.core.studio.ArtifactKind
import com.sherwood.wta.core.studio.ArtifactVault
import com.sherwood.wta.core.studio.ProjectSigningIdentity
import com.sherwood.wta.core.studio.SigningStudio
import com.sherwood.wta.core.studio.VaultArtifact
import com.sherwood.wta.core.studio.VaultVerification
import com.sherwood.wta.ui.components.PremiumTextField
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaRowTone
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import com.sherwood.wta.ui.theme.LocalShowDescriptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun StudioVaultTab(context: Context, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val vault = remember { ArtifactVault.getInstance(context) }
    val signing = remember { SigningStudio(context) }
    var artifacts by remember { mutableStateOf<List<VaultArtifact>>(emptyList()) }
    var verifications by remember { mutableStateOf<Map<String, VaultVerification>>(emptyMap()) }
    var deleting by remember { mutableStateOf<VaultArtifact?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingSign by remember { mutableStateOf<PendingSign?>(null) }
    var importingFor by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<VaultArtifact?>(null) }
    var savingArtifact by remember { mutableStateOf<VaultArtifact?>(null) }

    fun reload() {
        // The index is read from disk; reading it on the main thread stalls the first
        // frame of the tab on a device with a large vault or slow storage.
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { vault.list() }
            artifacts = loaded
        }
    }

    /**
     * Copies the pending artifact into the document the user picked. [savingArtifact]
     * is set immediately before the picker is launched, because the launcher callback
     * runs after the dialog closed and the selection is no longer in state.
     */
    fun finishExport(uri: Uri?) {
        val artifact = savingArtifact
        savingArtifact = null
        if (artifact == null || uri == null) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ArtifactExport.copyTo(context, vault.fileFor(artifact), uri)
            }
            result
                .onSuccess {
                    snackbarHostState.showSnackbar(
                        Strings.studioExportSaved(ArtifactExport.exportName(artifact))
                    )
                }
                .onFailure {
                    snackbarHostState.showSnackbar(Strings.studioExportFailed)
                }
        }
    }

    val saveApk = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ArtifactExport.APK_MIME)
    ) { uri -> finishExport(uri) }
    val saveBundle = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ArtifactExport.BUNDLE_MIME)
    ) { uri -> finishExport(uri) }

    fun launchSave(artifact: VaultArtifact) {
        savingArtifact = artifact
        val name = ArtifactExport.exportName(artifact)
        if (artifact.kindEnum == ArtifactKind.APK) saveApk.launch(name) else saveBundle.launch(name)
    }

    LaunchedEffect(Unit) { reload() }

    fun performSign(pending: PendingSign) {
        scope.launch {
            busy = true
            signing.sign(
                pending.artifact,
                allowCreateIdentity = pending.createIdentity,
                allowIdentityChange = pending.changeIdentity
            )
                .onSuccess {
                    snackbarHostState.showSnackbar(Strings.studioSigned)
                    reload()
                }
                .onFailure {
                    snackbarHostState.showSnackbar(it.message ?: Strings.studioConnectFailed)
                }
            busy = false
        }
    }

    fun requestSign(artifact: VaultArtifact) {
        scope.launch {
            busy = true
            val plan = withContext(Dispatchers.IO) { signing.plan(artifact) }
            busy = false
            when (plan.blockReason) {
                SigningStudio.BlockReason.UNKNOWN_PACKAGE ->
                    snackbarHostState.showSnackbar(Strings.studioPackageUnknown)
                SigningStudio.BlockReason.MISSING_FILE ->
                    snackbarHostState.showSnackbar(Strings.studioMismatch)
                SigningStudio.BlockReason.UNSUPPORTED_KIND -> Unit
                SigningStudio.BlockReason.NO_IDENTITY -> pendingSign = PendingSign(
                    artifact = artifact,
                    plan = plan,
                    createIdentity = true,
                    changeIdentity = false
                )
                SigningStudio.BlockReason.IDENTITY_CONFLICT -> pendingSign = PendingSign(
                    artifact = artifact,
                    plan = plan,
                    createIdentity = false,
                    changeIdentity = true
                )
                null -> pendingSign = PendingSign(
                    artifact = artifact,
                    plan = plan,
                    createIdentity = false,
                    changeIdentity = false
                )
            }
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
                        append(" · ").append(Strings.studioFileSizeKb(artifact.sizeBytes / 1024))
                    }
                )
                WtaSectionDivider()
                WtaSettingRow(
                    title = "SHA-256",
                    subtitle = artifact.sha256.take(32) + "…",
                    titleMaxLines = 1
                )
                artifact.packageName?.let { packageName ->
                    WtaSectionDivider()
                    WtaSettingRow(title = Strings.studioPackageName, subtitle = packageName)
                }
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
                        subtitle = artifact.runNumber?.let { Strings.studioRunNumber(it) }
                    )
                }
                artifact.releaseTag?.let { tag ->
                    WtaSectionDivider()
                    WtaSettingRow(title = Strings.studioReleaseTag(tag))
                }
                artifact.signedFromSha256?.let { from ->
                    WtaSectionDivider()
                    WtaSettingRow(title = Strings.studioSignedFrom(from.take(12)))
                }
                if (artifact.signatureVerified && artifact.signerSha256 != null) {
                    WtaSectionDivider()
                    WtaSettingRow(
                        title = Strings.studioSigner,
                        subtitle = "${artifact.signatureScheme ?: ""} · ${artifact.signerSha256.take(16)}"
                    )
                }
                WtaSectionDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = WtaSpacing.RowHorizontal, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WtaButton(
                        text = Strings.studioVerify,
                        size = WtaButtonSize.Small,
                        onClick = {
                            scope.launch {
                                val verification = withContext(Dispatchers.IO) { vault.verify(artifact) }
                                verifications = verifications + (artifact.id to verification)
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
                            onClick = { requestSign(artifact) }
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
                        text = Strings.studioExport,
                        size = WtaButtonSize.Small,
                        variant = WtaButtonVariant.Outlined,
                        onClick = { exporting = artifact }
                    )
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
                                if (signature.verified) append(" ✓") else append(" ✗")
                            }
                        },
                        tone = if (verification.sha256Matches) WtaRowTone.Normal else WtaRowTone.Danger
                    )
                }
            }
        }
        item { Spacer(Modifier.fillMaxWidth().height(8.dp)) }
    }

    val pending = pendingSign
    if (pending != null) {
        SigningPlanDialog(
            pending = pending,
            onDismiss = { pendingSign = null },
            onImportKeystore = {
                pendingSign = null
                importingFor = pending.plan.packageName
            },
            onConfirm = {
                pendingSign = null
                performSign(pending)
            }
        )
    }

    val importTarget = importingFor
    if (importTarget != null) {
        StudioKeystoreImportDialog(
            packageName = importTarget,
            onDismiss = { importingFor = null },
            onImported = { summary ->
                importingFor = null
                scope.launch {
                    snackbarHostState.showSnackbar("${Strings.studioIdentityImported}: ${summary.alias}")
                }
                reload()
            },
            onError = { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        )
    }

    val exportTarget = exporting
    if (exportTarget != null) {
        val file = vault.fileFor(exportTarget)
        AlertDialog(
            onDismissRequest = { exporting = null },
            title = { Text(Strings.studioExport) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = ArtifactExport.exportName(exportTarget),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = Strings.studioFileSizeKb(exportTarget.sizeBytes / 1024),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (LocalShowDescriptions.current) {
                        Text(
                            text = Strings.studioExportHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exporting = null
                        launchSave(exportTarget)
                    }
                ) { Text(Strings.studioExportSave) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            exporting = null
                            ArtifactExport.share(context, exportTarget, file)
                                .onFailure {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            Strings.studioExportShareFailed
                                        )
                                    }
                                }
                        }
                    ) { Text(Strings.studioExportShare) }
                    TextButton(onClick = { exporting = null }) { Text(Strings.cancel) }
                }
            }
        )
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
                        scope.launch {
                            withContext(Dispatchers.IO) { vault.delete(artifact) }
                            reload()
                        }
                    }
                }) { Text(Strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(Strings.cancel) }
            }
        )
    }
}

/** A signing request that still needs the user to confirm how the identity is obtained. */
private data class PendingSign(
    val artifact: VaultArtifact,
    val plan: SigningStudio.SigningPlan,
    val createIdentity: Boolean,
    val changeIdentity: Boolean
)

/**
 * Shows the identity that will sign the file **before** anything is signed, and
 * spells out what a missing or different key means. This dialog is the only place
 * that sets `allowCreateIdentity` / `allowIdentityChange`.
 */
@Composable
private fun SigningPlanDialog(
    pending: PendingSign,
    onDismiss: () -> Unit,
    onImportKeystore: () -> Unit,
    onConfirm: () -> Unit
) {
    val plan = pending.plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.studioSigningIdentity) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = plan.packageName ?: Strings.studioPackageUnknown,
                    style = MaterialTheme.typography.titleSmall
                )
                val identity = plan.identity
                if (identity != null) {
                    Text(
                        text = pending.identityLine(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "${Strings.studioSigner}: ${identity.shortFingerprint}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = Strings.studioNoSigningIdentity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = Strings.studioCreateIdentityWarning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (pending.changeIdentity) {
                    Text(
                        text = Strings.studioSignerMismatch,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    plan.artifactSignerSha256?.let { sha ->
                        Text(
                            text = "${Strings.studioSigner}: ${sha.take(16)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = Strings.studioSignerMismatchWarning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    if (pending.createIdentity) Strings.studioCreateIdentity else Strings.confirm
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onImportKeystore) {
                    Text(Strings.studioImportKeystore)
                }
                TextButton(onClick = onDismiss) { Text(Strings.cancel) }
            }
        }
    )
}

private fun PendingSign.identityLine(): String {
    val identity = plan.identity ?: return ""
    val origin = when (identity.origin) {
        ProjectSigningIdentity.Summary.Origin.IMPORTED -> Strings.studioIdentityImported
        ProjectSigningIdentity.Summary.Origin.GENERATED -> Strings.studioIdentityGenerated
    }
    return "$origin · ${identity.alias} · ${identity.notAfterDisplay}"
}

/**
 * Imports a project keystore: picks the file with the system picker, asks for the
 * passwords, validates by opening the store, and only then stores it.
 *
 * When an identity is already imported for the package the dialog says so before the
 * user can confirm, and only that confirmation replaces it. Without this a single
 * wrong import would make the package permanently un-signable from the Studio,
 * because [ProjectSigningIdentity] resolves an existing import first.
 */
@Composable
private fun StudioKeystoreImportDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onImported: (ProjectSigningIdentity.Summary) -> Unit,
    onError: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var storePassword by remember { mutableStateOf("") }
    var keyAlias by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Read once, when the dialog opens: replacing an imported identity is only
    // allowed after the user has seen the warning below and confirmed.
    val replacing = remember(packageName) { ProjectSigningIdentity.hasImported(context, packageName) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedName = uri.lastPathSegment?.substringAfterLast('/')
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.studioImportKeystore) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(packageName, style = MaterialTheme.typography.titleSmall)
                if (replacing) {
                    Text(
                        text = Strings.studioKeystoreReplaceWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                WtaButton(
                    text = pickedName ?: Strings.studioImportKeystore,
                    size = WtaButtonSize.Small,
                    variant = WtaButtonVariant.Outlined,
                    onClick = { picker.launch(arrayOf("*/*")) }
                )
                PremiumTextField(
                    value = storePassword,
                    onValueChange = { storePassword = it },
                    label = { Text(Strings.studioKeystorePassword) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    )
                )
                PremiumTextField(
                    value = keyAlias,
                    onValueChange = { keyAlias = it },
                    label = { Text(Strings.studioKeyAlias) },
                    singleLine = true
                )
                PremiumTextField(
                    value = keyPassword,
                    onValueChange = { keyPassword = it },
                    label = { Text(Strings.studioKeyPassword) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && pickedUri != null && storePassword.isNotEmpty(),
                onClick = {
                    val uri = pickedUri ?: return@TextButton
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            importKeystoreFromUri(
                                context = context,
                                uri = uri,
                                packageName = packageName,
                                storePassword = storePassword.toCharArray(),
                                alias = keyAlias.takeIf { it.isNotBlank() },
                                keyPassword = keyPassword.takeIf { it.isNotBlank() }?.toCharArray(),
                                replace = replacing
                            )
                        }
                        busy = false
                        result
                            .onSuccess { onImported(it) }
                            .onFailure { onError(it.message ?: Strings.studioConnectFailed) }
                    }
                }
            ) { Text(if (busy) Strings.loading else Strings.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.cancel) }
        }
    )
}

/** Copies the picked document into the app cache, then hands it to [ProjectSigningIdentity]. */
private fun importKeystoreFromUri(
    context: Context,
    uri: Uri,
    packageName: String,
    storePassword: CharArray,
    alias: String?,
    keyPassword: CharArray?,
    replace: Boolean
): Result<ProjectSigningIdentity.Summary> {
    val temp = File(context.cacheDir, "keystore-import-${System.nanoTime()}.tmp")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: return Result.failure(IllegalStateException("Could not read the selected file"))
        ProjectSigningIdentity.importKeystore(
            context = context,
            packageName = packageName,
            keystore = temp,
            storePassword = storePassword,
            alias = alias,
            keyPassword = keyPassword,
            replace = replace
        )
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        temp.delete()
        storePassword.fill('\u0000')
        keyPassword?.fill('\u0000')
    }
}
