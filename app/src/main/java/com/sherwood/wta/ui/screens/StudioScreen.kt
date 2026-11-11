package com.sherwood.wta.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.studio.EndpointConfig
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.core.studio.ThirdPartyNotices
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaScreen
import com.sherwood.wta.ui.design.WtaSection
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import com.sherwood.wta.ui.design.WtaTab
import com.sherwood.wta.ui.design.WtaTabRow
import kotlinx.coroutines.launch

/**
 * Sherwood Studio: GitHub repositories, workspace inbox, GitHub Actions builds and
 * the local APK/AAB vault, all in the existing single app.
 */
@Composable
fun StudioScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        StudioSettingsScreen(
            onBack = { showSettings = false },
            snackbarHostState = snackbarHostState
        )
        return
    }

    val tabs = listOf(
        Strings.studioTabRepos,
        Strings.studioTabInbox,
        Strings.studioTabBuilds,
        Strings.studioTabVault
    )
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    WtaScreen(
        title = Strings.studioTitle,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Outlined.Settings, contentDescription = Strings.studioSettings)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            WtaTabRow(
                tabs = tabs.map { WtaTab(it) },
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(
                    horizontal = WtaSpacing.ScreenHorizontal,
                    vertical = WtaSpacing.ContentGap
                )
            )
            when (selectedTab) {
                0 -> StudioReposTab(context, snackbarHostState)
                1 -> StudioInboxTab(context, snackbarHostState)
                2 -> StudioBuildsTab(context, snackbarHostState)
                else -> StudioVaultTab(context, snackbarHostState)
            }
        }
    }
}

@Composable
internal fun StudioEmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(WtaSpacing.ScreenHorizontal),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun StudioBusy(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WtaSpacing.RowHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private val ENDPOINT_FIELDS = listOf(
    "githubApiBaseUrl" to "GitHub API",
    "githubWebBaseUrl" to "GitHub web",
    "githubRawBaseUrl" to "GitHub raw",
    "githubDeviceCodeUrl" to "Device code URL",
    "githubAccessTokenUrl" to "Access token URL",
    "projectOwner" to "Project owner",
    "projectRepo" to "Project repository",
    "projectBranch" to "Project branch",
    "docsBaseUrl" to "Documentation",
    "modulesBaseUrl" to "Modules",
    "modulesMirrorBaseUrl" to "Modules mirror",
    "sampleBundlesBaseUrl" to "Sample packs",
    "sampleBundlesMirrorBaseUrl" to "Sample packs mirror"
)

@Composable
private fun StudioSettingsScreen(
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val store = remember { EndpointSettingsStore.getInstance(context) }
    val studioSettings = remember { StudioSettingsStore.getInstance(context) }
    val scope = rememberCoroutineScope()
    val saved = remember { EndpointSettingsStore.current(context) }

    var values by remember {
        mutableStateOf(
            linkedMapOf(
                "githubApiBaseUrl" to saved.githubApiBaseUrl,
                "githubWebBaseUrl" to saved.githubWebBaseUrl,
                "githubRawBaseUrl" to saved.githubRawBaseUrl,
                "githubDeviceCodeUrl" to saved.githubDeviceCodeUrl,
                "githubAccessTokenUrl" to saved.githubAccessTokenUrl,
                "projectOwner" to saved.projectOwner,
                "projectRepo" to saved.projectRepo,
                "projectBranch" to saved.projectBranch,
                "docsBaseUrl" to saved.docsBaseUrl,
                "modulesBaseUrl" to saved.modulesBaseUrl,
                "modulesMirrorBaseUrl" to saved.modulesMirrorBaseUrl,
                "sampleBundlesBaseUrl" to saved.sampleBundlesBaseUrl,
                "sampleBundlesMirrorBaseUrl" to saved.sampleBundlesMirrorBaseUrl
            )
        )
    }
    var oauthClientId by remember { mutableStateOf(studioSettings.oauthClientId) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    var showLicenses by remember { mutableStateOf(false) }

    if (showLicenses) {
        StudioLicensesScreen(onBack = { showLicenses = false })
        return
    }

    fun buildConfig(): EndpointConfig = EndpointConfig.defaults().let { defaults ->
        defaults.copy(
            githubApiBaseUrl = values["githubApiBaseUrl"] ?: defaults.githubApiBaseUrl,
            githubWebBaseUrl = values["githubWebBaseUrl"] ?: defaults.githubWebBaseUrl,
            githubRawBaseUrl = values["githubRawBaseUrl"] ?: defaults.githubRawBaseUrl,
            githubDeviceCodeUrl = values["githubDeviceCodeUrl"] ?: defaults.githubDeviceCodeUrl,
            githubAccessTokenUrl = values["githubAccessTokenUrl"] ?: defaults.githubAccessTokenUrl,
            projectOwner = values["projectOwner"] ?: defaults.projectOwner,
            projectRepo = values["projectRepo"] ?: defaults.projectRepo,
            projectBranch = values["projectBranch"] ?: defaults.projectBranch,
            docsBaseUrl = values["docsBaseUrl"] ?: defaults.docsBaseUrl,
            modulesBaseUrl = values["modulesBaseUrl"] ?: defaults.modulesBaseUrl,
            modulesMirrorBaseUrl = values["modulesMirrorBaseUrl"] ?: defaults.modulesMirrorBaseUrl,
            sampleBundlesBaseUrl = values["sampleBundlesBaseUrl"] ?: defaults.sampleBundlesBaseUrl,
            sampleBundlesMirrorBaseUrl = values["sampleBundlesMirrorBaseUrl"]
                ?: defaults.sampleBundlesMirrorBaseUrl
        )
    }

    fun save() {
        val config = buildConfig()
        val validation = config.validationErrors()
        errors = validation
        if (validation.isNotEmpty()) {
            scope.launch { snackbarHostState.showSnackbar(Strings.studioInvalidEndpoint) }
            return
        }
        val result = store.save(config)
        if (result.isSuccess) {
            studioSettings.oauthClientId = oauthClientId
            scope.launch { snackbarHostState.showSnackbar(Strings.studioSaved) }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    result.exceptionOrNull()?.message ?: Strings.studioInvalidEndpoint
                )
            }
        }
    }

    WtaScreen(
        title = Strings.studioSettings,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            WtaButton(
                onClick = { save() },
                text = Strings.save,
                variant = WtaButtonVariant.Primary
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = WtaSpacing.ScreenHorizontal, vertical = WtaSpacing.ScreenVertical),
            verticalArrangement = Arrangement.spacedBy(WtaSpacing.SectionGap)
        ) {
            WtaSection(title = Strings.studioClientId) {
                WtaSettingCard {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        OutlinedTextField(
                            value = oauthClientId,
                            onValueChange = { oauthClientId = it.trim() },
                            label = { Text(Strings.studioClientId) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            Strings.studioClientIdHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            WtaSection(title = Strings.studioEndpoints, description = Strings.studioEndpointsDesc) {
                WtaSettingCard {
                    ENDPOINT_FIELDS.forEachIndexed { index, (key, label) ->
                        if (index > 0) WtaSectionDivider()
                        val fieldError = errors[key]
                        Column(
                            modifier = Modifier.padding(
                                horizontal = WtaSpacing.RowHorizontal,
                                vertical = 8.dp
                            )
                        ) {
                            OutlinedTextField(
                                value = values[key].orEmpty(),
                                onValueChange = { newValue ->
                                    values = LinkedHashMap(values).apply { put(key, newValue.trim()) }
                                    if (errors.containsKey(key)) {
                                        errors = errors - key
                                    }
                                },
                                label = { Text(label) },
                                singleLine = true,
                                isError = fieldError != null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (fieldError != null) {
                                Text(
                                    fieldError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    WtaSectionDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = WtaSpacing.RowHorizontal, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        WtaButton(
                            onClick = {
                                store.reset()
                                val defaults = EndpointConfig.defaults()
                                values = linkedMapOf(
                                    "githubApiBaseUrl" to defaults.githubApiBaseUrl,
                                    "githubWebBaseUrl" to defaults.githubWebBaseUrl,
                                    "githubRawBaseUrl" to defaults.githubRawBaseUrl,
                                    "githubDeviceCodeUrl" to defaults.githubDeviceCodeUrl,
                                    "githubAccessTokenUrl" to defaults.githubAccessTokenUrl,
                                    "projectOwner" to defaults.projectOwner,
                                    "projectRepo" to defaults.projectRepo,
                                    "projectBranch" to defaults.projectBranch,
                                    "docsBaseUrl" to defaults.docsBaseUrl,
                                    "modulesBaseUrl" to defaults.modulesBaseUrl,
                                    "modulesMirrorBaseUrl" to defaults.modulesMirrorBaseUrl,
                                    "sampleBundlesBaseUrl" to defaults.sampleBundlesBaseUrl,
                                    "sampleBundlesMirrorBaseUrl" to defaults.sampleBundlesMirrorBaseUrl
                                )
                                errors = emptyMap()
                            },
                            text = Strings.studioEndpointsReset,
                            variant = WtaButtonVariant.Outlined
                        )
                    }
                }
            }

            WtaSection(title = Strings.studioLicenses) {
                WtaSettingCard {
                    WtaSettingRow(
                        title = Strings.studioLicenses,
                        subtitle = Strings.studioLicenseNote,
                        onClick = { showLicenses = true }
                    )
                }
            }

            WtaSection(title = Strings.studioWorkspace, description = Strings.studioWorkspaceDesc) {
                WtaSettingCard {
                    val instructions = remember {
                        com.sherwood.wta.core.studio.WorkspaceBridgeService(context)
                            .instructions("${saved.projectOwner}/${saved.projectRepo}")
                    }
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        Text(instructions, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        WtaButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText(Strings.studioWorkspace, instructions)
                                )
                                scope.launch { snackbarHostState.showSnackbar(Strings.copied) }
                            },
                            text = Strings.copy,
                            variant = WtaButtonVariant.Outlined
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun StudioLicensesScreen(onBack: () -> Unit) {
    WtaScreen(title = Strings.studioLicenses, onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = WtaSpacing.ScreenHorizontal, vertical = WtaSpacing.ScreenVertical),
            verticalArrangement = Arrangement.spacedBy(WtaSpacing.SectionGap)
        ) {
            WtaSection(title = Strings.studioTitle) {
                WtaSettingCard {
                    WtaSettingRow(
                        title = Strings.studioTitle,
                        subtitle = ThirdPartyNotices.PROJECT_LICENCE
                    )
                    WtaSectionDivider()
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        Text(
                            Strings.studioLicenseNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            WtaSection(title = Strings.studioLicenses) {
                WtaSettingCard {
                    ThirdPartyNotices.components.forEachIndexed { index, notice ->
                        if (index > 0) WtaSectionDivider()
                        WtaSettingRow(
                            title = notice.name,
                            subtitle = notice.note ?: notice.licence
                        )
                    }
                }
            }
            WtaSection(title = Strings.studioLicenses) {
                WtaSettingCard {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        Text(Strings.studioLicenseJgit, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
