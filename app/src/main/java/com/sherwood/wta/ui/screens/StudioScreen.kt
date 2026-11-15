package com.sherwood.wta.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.studio.EndpointConfig
import com.sherwood.wta.core.studio.EndpointSettingsStore
import com.sherwood.wta.core.studio.StudioSettingsStore
import com.sherwood.wta.core.studio.ThirdPartyNotices
import com.sherwood.wta.core.studio.git.GitCredentialKey
import com.sherwood.wta.core.studio.git.GitCredentialStore
import com.sherwood.wta.core.studio.git.GitProviderId
import com.sherwood.wta.core.studio.git.GitProviderLookup
import com.sherwood.wta.core.studio.git.GitProviderRegistry
import com.sherwood.wta.core.studio.git.GitProviderSettings
import com.sherwood.wta.core.studio.git.GitProviderUnavailableReason
import com.sherwood.wta.core.studio.git.GitStudio
import com.sherwood.wta.core.telegram.TelegramApiClient
import com.sherwood.wta.core.telegram.TelegramBotService
import com.sherwood.wta.core.telegram.TelegramBotTokenStore
import com.sherwood.wta.core.telegram.TelegramSettings
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaScreen
import com.sherwood.wta.ui.design.WtaSection
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaToggleRow
import com.sherwood.wta.ui.design.WtaSpacing
import com.sherwood.wta.ui.design.WtaTab
import com.sherwood.wta.ui.design.WtaTabRow
import com.sherwood.wta.ui.theme.LocalShowDescriptions
import com.sherwood.wta.ui.theme.ifDescriptionsShown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sherwood Studio: GitHub repositories, workspace inbox, GitHub Actions builds and
 * the local APK/AAB vault, all in the existing single app.
 */
@Composable
fun StudioScreen(onBack: () -> Unit = {}, onOpenGitSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        StudioSettingsScreen(
            onBack = { showSettings = false },
            snackbarHostState = snackbarHostState,
            onOpenGitSettings = onOpenGitSettings
        )
        return
    }

    val tabs = listOf(
        Strings.studioTabRepos,
        Strings.studioTabInbox,
        Strings.studioTabBuilds,
        Strings.studioTabVault,
        Strings.studioInspector
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
                0 -> StudioReposTab(context, snackbarHostState, onOpenGitSettings)
                1 -> StudioInboxTab(context, snackbarHostState)
                2 -> StudioBuildsTab(context, snackbarHostState)
                3 -> StudioVaultTab(context, snackbarHostState)
                else -> StudioInspectorTab(context, snackbarHostState)
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
    snackbarHostState: SnackbarHostState,
    onOpenGitSettings: () -> Unit = {}
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

    // ---- git hosting choice (GitHub / Gitea / Forgejo) ----------------------
    val providerSettings = remember { GitProviderSettings.getInstance(context) }
    val credentials = remember { GitCredentialStore(context) }
    var providerId by remember { mutableStateOf(providerSettings.activeProvider) }
    var serverUrl by remember { mutableStateOf(providerSettings.baseUrl(providerId)) }
    var tokenInput by remember { mutableStateOf("") }
    var tokenMessage by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    // ---- Telegram command centre (Android harness) --------------------------
    val telegramSettings = remember { TelegramSettings.getInstance(context) }
    val telegramTokens = remember { TelegramBotTokenStore(context) }
    var telegramEnabled by remember { mutableStateOf(telegramSettings.enabled) }
    var telegramTokenInput by remember { mutableStateOf("") }
    var telegramMessage by remember { mutableStateOf<String?>(null) }
    var telegramError by remember { mutableStateOf(false) }
    var telegramProtection by remember { mutableStateOf(telegramTokens.protection()) }
    var telegramPaired by remember { mutableStateOf(telegramSettings.pairedChats) }
    var telegramAllowBuilds by remember { mutableStateOf(telegramSettings.allowRemoteBuilds) }
    var telegramNotifyBuilds by remember { mutableStateOf(telegramSettings.notifyBuilds) }
    var telegramPairingCode by remember { mutableStateOf(telegramSettings.pairingCode) }
    var telegramBusy by remember { mutableStateOf(false) }

    // ---- local models (Ollama): offline agent driver -------------------------
    val localModels = remember { com.sherwood.wta.core.agent.local.LocalAgentModels(context) }
    val localClient = remember { com.sherwood.wta.core.agent.local.OllamaLocalClient() }
    var localUrl by remember { mutableStateOf(localModels.lastBaseUrl()) }
    var localBusy by remember { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf(false) }
    var localFound by remember {
        mutableStateOf<List<com.sherwood.wta.core.agent.local.OllamaModel>>(emptyList())
    }
    var localActive by remember { mutableStateOf(localModels.activeModelName()) }

    fun probeLocal() {
        localBusy = true
        // A LAN probe is a network round trip; it never runs on the composition thread.
        scope.launch {
            val result = withContext(Dispatchers.IO) { localClient.probe(localUrl) }
            localBusy = false
            result.onSuccess { server ->
                localFound = server.models
                localError = false
                localMessage = if (server.models.isEmpty()) {
                    Strings.localModelsEmpty
                } else {
                    Strings.localModelsProbeOk(server.version ?: "?", server.models.size)
                }
                localModels.rememberBaseUrl(localUrl)
            }.onFailure {
                localFound = emptyList()
                localError = true
                localMessage = Strings.localModelsProbeFailed
            }
        }
    }

    fun useLocalModel(model: com.sherwood.wta.core.agent.local.OllamaModel) {
        localBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { localModels.bind(localUrl, model) }
            localBusy = false
            result.onSuccess { binding ->
                localActive = binding.modelName
                localError = false
                localMessage = Strings.localModelsAdded(binding.modelName)
            }.onFailure {
                localError = true
                localMessage = Strings.localModelsBindFailed
            }
        }
    }

    fun applyTelegramEnabled(enabled: Boolean) {
        telegramEnabled = enabled
        telegramSettings.enabled = enabled
        if (enabled) TelegramBotService.start(context) else TelegramBotService.stop(context)
    }

    fun saveTelegramToken() {
        if (telegramTokenInput.isBlank()) {
            telegramError = true
            telegramMessage = Strings.telegramTokenMissing
            return
        }
        telegramBusy = true
        // KeyStore encryption plus a synchronous SharedPreferences commit must not run on
        // the composition thread.
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                telegramTokens.saveToken(telegramTokenInput)
            }
            telegramBusy = false
            result.onSuccess {
                telegramTokenInput = ""
                telegramError = false
                telegramMessage = Strings.telegramTokenSaved
                telegramProtection = telegramTokens.protection()
                if (telegramEnabled) TelegramBotService.start(context)
            }.onFailure {
                telegramError = true
                telegramMessage = Strings.telegramTokenInvalid
            }
        }
    }

    fun testTelegram() {
        val token = telegramTokens.currentToken()
        if (token == null) {
            telegramError = true
            telegramMessage = Strings.telegramTokenMissing
            return
        }
        telegramBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                com.sherwood.wta.core.telegram.TelegramApiClient(token).getMe()
            }
            telegramBusy = false
            result.onSuccess { info ->
                telegramSettings.botUsername = info.username
                telegramError = false
                telegramMessage = Strings.telegramConnected(info.username)
            }.onFailure {
                telegramError = true
                telegramMessage = Strings.telegramTestFailed
            }
        }
    }

    val credentialKey = remember(providerId, serverUrl) {
        GitCredentialKey.of(providerId, serverUrl)
    }
    val hasToken = credentialKey?.let { credentials.hasToken(it) } == true
    val serverUrlProblem = GitStudio.baseUrlProblem(providerId, serverUrl)

    fun saveToken() {
        val key = credentialKey
        if (key == null) {
            tokenError = true
            tokenMessage = if (serverUrl.isBlank()) Strings.studioProviderMissingUrl
            else Strings.studioProviderInsecureUrl
            return
        }
        credentials.saveToken(key, tokenInput).onSuccess {
            tokenInput = ""
            tokenError = false
            tokenMessage = Strings.studioProviderTokenSaved
        }.onFailure {
            tokenError = true
            tokenMessage = it.message ?: Strings.studioTestFailed
        }
    }

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
        // A provider with an unusable server URL must never become active: every request
        // after that would fail, and the reason would look like a network problem.
        val providerProblem = GitStudio.baseUrlProblem(providerId, serverUrl)
        if (providerProblem != null) {
            tokenError = true
            tokenMessage = if (providerProblem == GitProviderUnavailableReason.MISSING_BASE_URL) {
                Strings.studioProviderMissingUrl
            } else {
                Strings.studioProviderInsecureUrl
            }
            scope.launch { snackbarHostState.showSnackbar(tokenMessage.orEmpty()) }
            return
        }
        val result = store.save(config)
        if (result.isSuccess) {
            studioSettings.oauthClientId = oauthClientId
            // Base URL first, then the active provider, so a screen that reads the
            // active provider never sees it pointing at a server it cannot resolve.
            providerSettings.setBaseUrl(providerId, serverUrl)
            providerSettings.activeProvider = providerId
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
            WtaSection(title = Strings.studioGitHosting, description = Strings.studioGitHostingDesc) {
                WtaSettingCard {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        Text(
                            Strings.studioProvider,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        GitStudio.selectable().forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        providerId = option
                                        serverUrl = providerSettings.baseUrl(option)
                                        testMessage = null
                                        tokenMessage = null
                                        tokenError = false
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected = providerId == option,
                                    onClick = {
                                        providerId = option
                                        serverUrl = providerSettings.baseUrl(option)
                                        testMessage = null
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    GitStudio.displayName(option),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    if (providerId != GitProviderId.GITHUB) {
                        WtaSectionDivider()
                        Column(
                            modifier = Modifier.padding(
                                horizontal = WtaSpacing.RowHorizontal,
                                vertical = WtaSpacing.ContentGap
                            )
                        ) {
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = {
                                    serverUrl = it.trim()
                                    testMessage = null
                                },
                                label = { Text(Strings.studioServerUrl) },
                                singleLine = true,
                                isError = serverUrl.isNotBlank() &&
                                    serverUrlProblem == GitProviderUnavailableReason.NON_HTTPS_BASE_URL,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth()
                            )
                            // The blocking states are stated; the neutral hint follows the
                            // About "show descriptions" switch.
                            when {
                                serverUrl.isBlank() -> Text(
                                    Strings.studioServerUrlMissing,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                serverUrlProblem == GitProviderUnavailableReason.NON_HTTPS_BASE_URL ->
                                    Text(
                                        Strings.studioServerUrlInsecure,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                LocalShowDescriptions.current -> Text(
                                    Strings.studioServerUrlHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        WtaSectionDivider()
                        Column(
                            modifier = Modifier.padding(
                                horizontal = WtaSpacing.RowHorizontal,
                                vertical = WtaSpacing.ContentGap
                            )
                        ) {
                            if (LocalShowDescriptions.current) {
                                Text(
                                    Strings.studioGiteaConnectHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = {
                                    tokenInput = it
                                    tokenError = false
                                    tokenMessage = null
                                },
                                label = { Text(Strings.studioProviderToken) },
                                singleLine = true,
                                isError = tokenError,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (LocalShowDescriptions.current) {
                                Text(
                                    Strings.studioProviderTokenHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WtaButton(
                                    onClick = { saveToken() },
                                    text = Strings.studioSaveToken,
                                    enabled = tokenInput.isNotBlank(),
                                    variant = WtaButtonVariant.Outlined
                                )
                                WtaButton(
                                    onClick = {
                                        testing = true
                                        testMessage = null
                                        scope.launch {
                                            val lookup = GitProviderRegistry.of(
                                                context,
                                                providerId,
                                                serverUrl
                                            )
                                            val message = when (lookup) {
                                                is GitProviderLookup.Unavailable -> {
                                                    testOk = false
                                                    when (lookup.reason) {
                                                        GitProviderUnavailableReason.MISSING_BASE_URL ->
                                                            Strings.studioProviderMissingUrl
                                                        GitProviderUnavailableReason.NON_HTTPS_BASE_URL ->
                                                            Strings.studioProviderInsecureUrl
                                                        GitProviderUnavailableReason
                                                            .NOT_IMPLEMENTED_IN_THIS_BUILD ->
                                                            Strings.studioProviderNotImplemented
                                                    }
                                                }
                                                is GitProviderLookup.Available -> when (
                                                    val info = lookup.provider.serverInfo()
                                                ) {
                                                    is com.sherwood.wta.core.studio.git
                                                        .GitResult.Success -> {
                                                        testOk = true
                                                        val login = info.value.login.orEmpty()
                                                        val version = info.value.serverVersion
                                                        if (version.isNullOrBlank()) {
                                                            Strings.studioTestOk(login)
                                                        } else {
                                                            Strings.studioTestOkVersion(login, version)
                                                        }
                                                    }
                                                    is com.sherwood.wta.core.studio.git
                                                        .GitResult.Failure -> {
                                                        testOk = false
                                                        info.message ?: Strings.studioTestFailed
                                                    }
                                                }
                                            }
                                            testMessage = message
                                            testing = false
                                        }
                                    },
                                    text = if (testing) Strings.studioTesting
                                    else Strings.studioTestConnection,
                                    enabled = !testing,
                                    variant = WtaButtonVariant.Outlined
                                )
                            }
                            if (tokenMessage != null) {
                                Text(
                                    tokenMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (tokenError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (!hasToken) {
                                Text(
                                    Strings.studioProviderTokenMissing,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            testMessage?.let { message ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (testOk) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            if (providerId == GitProviderId.GITHUB) {
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
                            isError = oauthClientId.isBlank(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        // The empty state is the one that breaks Connect, so name it.
                        // The neutral hint is helper copy and follows the About switch.
                        if (oauthClientId.isBlank()) {
                            Text(
                                Strings.studioClientIdMissing,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (LocalShowDescriptions.current) {
                            Text(
                                Strings.studioClientIdHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                }
            }

            // Token-based access is an equal alternative to the OAuth App, and the
            // screen that owns it lives outside Studio. Link it rather than hiding it.
            // It is GitHub's token screen, so a self-hosted provider must not link to
            // it — its token lives in the hosting section above.
            if (providerId == GitProviderId.GITHUB) {
            WtaSection(title = Strings.gitSettingsTitle) {
                WtaSettingCard {
                    WtaSettingRow(
                        title = Strings.gitTokenTitle,
                        subtitle = Strings.studioTokenAlternate.ifDescriptionsShown(),
                        onClick = onOpenGitSettings
                    )
                    WtaSectionDivider()
                    // State the agent boundary where the connection is configured, so the
                    // chat's GitHub access is never mistaken for a push channel.
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        if (LocalShowDescriptions.current) {
                            Text(
                                Strings.studioAgentGitNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            }

            // The offline path: the agent runs on a model the user hosts, and the
            // active profile is what actually switches it over.
            WtaSection(
                title = Strings.localModelsTitle,
                description = Strings.localModelsDesc.ifDescriptionsShown()
            ) {
                WtaSettingCard {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        OutlinedTextField(
                            value = localUrl,
                            onValueChange = { localUrl = it.trim() },
                            label = { Text(Strings.localModelsUrl) },
                            singleLine = true,
                            isError = localError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (LocalShowDescriptions.current) {
                            Text(
                                Strings.localModelsHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        localMessage?.let { message ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (localError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            WtaButton(
                                onClick = { probeLocal() },
                                text = Strings.localModelsProbe,
                                variant = WtaButtonVariant.Outlined,
                                enabled = !localBusy
                            )
                        }
                    }
                    WtaSectionDivider()
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        val activeModel = localActive
                        if (activeModel == null) {
                            Text(
                                Strings.localModelsNone,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                Strings.localModelsActive(activeModel),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                WtaButton(
                                    onClick = {
                                        localModels.resetToDefault()
                                        localActive = null
                                        localMessage = null
                                    },
                                    text = Strings.localModelsReset,
                                    variant = WtaButtonVariant.Outlined
                                )
                            }
                        }
                    }
                    localFound.forEach { model ->
                        WtaSectionDivider()
                        WtaSettingRow(
                            title = model.name,
                            subtitle = listOfNotNull(
                                model.parameterSize,
                                model.quantization,
                                model.displaySize.takeIf { it.isNotBlank() }
                            ).joinToString(" · ").ifBlank { null },
                            onClick = { useLocalModel(model) }
                        )
                    }
                }
            }

            WtaSection(title = Strings.telegramBot, description = Strings.telegramDesc) {
                WtaSettingCard {
                    WtaToggleRow(
                        title = Strings.telegramEnable,
                        checked = telegramEnabled,
                        subtitle = Strings.telegramEnableDesc.ifDescriptionsShown(),
                        onCheckedChange = { applyTelegramEnabled(it) }
                    )
                    WtaSectionDivider()
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        OutlinedTextField(
                            value = telegramTokenInput,
                            onValueChange = { telegramTokenInput = it.trim() },
                            label = { Text(Strings.telegramToken) },
                            singleLine = true,
                            isError = telegramError,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        if (LocalShowDescriptions.current) {
                            Text(
                                Strings.telegramTokenHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        telegramMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (telegramError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Same protection report as the git token: a degraded keystore is
                        // never silently presented as hardware-backed.
                        Text(
                            "${Strings.gitTokenProtection}: " + when (telegramProtection) {
                                com.sherwood.wta.core.frontend.TokenProtection.ANDROID_KEYSTORE ->
                                    Strings.gitTokenProtectionKeystore
                                com.sherwood.wta.core.frontend.TokenProtection.APP_DERIVED_FALLBACK ->
                                    Strings.gitTokenProtectionFallback
                                com.sherwood.wta.core.frontend.TokenProtection.NONE -> "—"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            WtaButton(
                                onClick = { testTelegram() },
                                text = Strings.telegramTest,
                                variant = WtaButtonVariant.Outlined,
                                enabled = !telegramBusy
                            )
                            Spacer(Modifier.width(8.dp))
                            WtaButton(
                                onClick = { saveTelegramToken() },
                                text = Strings.save,
                                variant = WtaButtonVariant.Primary,
                                enabled = !telegramBusy
                            )
                        }
                    }
                    WtaSectionDivider()
                    Column(
                        modifier = Modifier.padding(
                            horizontal = WtaSpacing.RowHorizontal,
                            vertical = WtaSpacing.ContentGap
                        )
                    ) {
                        Text(
                            Strings.telegramPairing,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (LocalShowDescriptions.current) {
                            Text(
                                Strings.telegramPairingDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val pairing = telegramPairingCode
                        if (pairing != null) {
                            val minutes = ((pairing.expiresAtEpochMs - System.currentTimeMillis()) / 60_000L)
                                .coerceAtLeast(0L)
                            Text(
                                Strings.telegramCodeActive(pairing.value, minutes.toInt()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        val pairedText = if (telegramPaired.isEmpty()) {
                            Strings.telegramNoPairedChats
                        } else {
                            "${Strings.telegramPairedChats}: ${telegramPaired.size}"
                        }
                        Text(
                            pairedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (telegramPaired.isNotEmpty()) {
                                WtaButton(
                                    onClick = {
                                        telegramPaired.forEach { telegramSettings.unpair(it) }
                                        telegramPaired = telegramSettings.pairedChats
                                    },
                                    text = Strings.telegramClearPaired,
                                    variant = WtaButtonVariant.Outlined
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            WtaButton(
                                onClick = { telegramPairingCode = telegramSettings.newPairingCode() },
                                text = Strings.telegramCreateCode,
                                variant = WtaButtonVariant.Primary
                            )
                        }
                    }
                    WtaSectionDivider()
                    WtaToggleRow(
                        title = Strings.telegramAllowBuilds,
                        checked = telegramAllowBuilds,
                        subtitle = Strings.telegramAllowBuildsDesc.ifDescriptionsShown(),
                        onCheckedChange = {
                            telegramAllowBuilds = it
                            telegramSettings.allowRemoteBuilds = it
                        }
                    )
                    WtaSectionDivider()
                    WtaToggleRow(
                        title = Strings.telegramNotifyBuilds,
                        checked = telegramNotifyBuilds,
                        subtitle = Strings.telegramNotifyBuildsDesc.ifDescriptionsShown(),
                        onCheckedChange = {
                            telegramNotifyBuilds = it
                            telegramSettings.notifyBuilds = it
                        }
                    )
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
                        // State the integration boundary before the instructions, so
                        // the contract is never mistaken for a live connection.
                        Text(
                            text = Strings.studioWorkspaceChannel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = Strings.studioWorkspaceAiNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
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
