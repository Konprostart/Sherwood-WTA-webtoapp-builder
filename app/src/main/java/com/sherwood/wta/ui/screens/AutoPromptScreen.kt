package com.sherwood.wta.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sherwood.wta.core.agent.autoprompt.AutoPrompt
import com.sherwood.wta.core.agent.autoprompt.AutoPromptCategory
import com.sherwood.wta.core.agent.autoprompt.AutoPromptRunPlan
import com.sherwood.wta.core.agent.autoprompt.AutoPromptRunner
import com.sherwood.wta.core.agent.autoprompt.AutoPromptSearch
import com.sherwood.wta.core.agent.autoprompt.AutoPromptState
import com.sherwood.wta.core.agent.autoprompt.AutoPromptStore
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.profile.AgentProfile
import com.sherwood.wta.core.agent.profile.AgentProfileStore
import com.sherwood.wta.core.agent.session.SessionStore
import com.sherwood.wta.core.ai.AiConfigManager
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.data.model.AiFeature
import com.sherwood.wta.data.model.SavedModel
import com.sherwood.wta.ui.components.PremiumTextField
import com.sherwood.wta.ui.design.WtaAlertDialog
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaCard
import com.sherwood.wta.ui.design.WtaFullEmptyState
import com.sherwood.wta.ui.design.WtaRadius
import com.sherwood.wta.ui.design.WtaScreen
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSpacing
import com.sherwood.wta.ui.design.WtaStatusBanner
import com.sherwood.wta.ui.design.WtaStatusTone
import com.sherwood.wta.ui.design.WtaToggleRow
import com.sherwood.wta.ui.theme.ifDescriptionsShown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * AUTO PROMPT — the host's window for managing saved instructions for the agent.
 *
 * The master switch gates *running*, never the saved data: turning it off rewrites one
 * boolean and every prompt stays. Turning it back on brings the same list back. Nothing
 * on this screen starts a prompt by itself — the only trigger is the Run button, and a run
 * creates a fresh agent session through [AutoPromptRunner].
 */
@Composable
fun AutoPromptScreen(
    onNavigateBack: () -> Unit,
    onOpenAgent: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AutoPromptStore.getInstance(context) }
    val profileStore = remember { AgentProfileStore.getInstance(context) }
    val configManager = remember { AiConfigManager(context) }
    val sessionStore = remember { SessionStore(context, ProjectFileManager(context)) }
    val runner = remember { AutoPromptRunner(context, sessionStore) }

    val models by configManager.savedModelsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val apiKeys by configManager.apiKeysFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val profiles = remember { profileStore.profiles() }

    var masterEnabled by remember { mutableStateOf(true) }
    var prompts by remember { mutableStateOf(emptyList<AutoPrompt>()) }
    var loaded by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var editorOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AutoPrompt?>(null) }
    var deleteTarget by remember { mutableStateOf<AutoPrompt?>(null) }
    var runningId by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun apply(state: AutoPromptState) {
        masterEnabled = state.masterEnabled
        prompts = state.prompts
    }

    LaunchedEffect(Unit) {
        // Starter prompts follow the app language at first open; afterwards the saved
        // text is the user's, so it is never re-translated behind their back.
        val seeded = store.seedIfFirstRun(defaultAutoPrompts())
        apply(seeded)
        loaded = true
    }

    fun run(prompt: AutoPrompt) {
        when {
            !masterEnabled -> {
                toast(Strings.autoPromptMasterOff)
                return
            }
            !prompt.enabled -> {
                toast(Strings.autoPromptPromptOff)
                return
            }
            runningId != null -> return
        }
        runningId = prompt.id
        scope.launch {
            try {
                when (val result = runner.prepare(prompt)) {
                    is AutoPromptRunner.PrepareResult.Ready -> {
                        toast(Strings.autoPromptStarting(prompt.name))
                        onOpenAgent()
                    }
                    is AutoPromptRunner.PrepareResult.Blocked ->
                        toast(blockedMessage(result.reason))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                toast(Strings.autoPromptRunFailed)
            } finally {
                runningId = null
            }
        }
    }

    val shown = AutoPromptSearch.filter(prompts, searchQuery)

    WtaScreen(
        title = Strings.autoPromptTitle,
        onBack = onNavigateBack
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = WtaSpacing.ScreenHorizontal,
                    vertical = WtaSpacing.ScreenVertical
                ),
                verticalArrangement = Arrangement.spacedBy(WtaSpacing.CardGap)
            ) {
                item(key = "auto_prompt_header") {
                    AutoPromptHeader(
                        masterEnabled = masterEnabled,
                        promptCount = prompts.size,
                        onMasterChanged = { enabled ->
                            masterEnabled = enabled
                            scope.launch {
                                store.setMasterEnabled(enabled)
                                    .onSuccess { apply(it) }
                                    .onFailure {
                                        // Optimistic switch: never leave the UI claiming
                                        // a state the disk rejected.
                                        masterEnabled = !enabled
                                        toast(Strings.autoPromptRunFailed)
                                    }
                            }
                        },
                        onAdd = {
                            editTarget = null
                            editorOpen = true
                        }
                    )
                }

                if (prompts.isNotEmpty()) {
                    item(key = "auto_prompt_search") {
                        PremiumTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(Strings.autoPromptSearchHint) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Outlined.Close, contentDescription = Strings.clear)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(WtaRadius.Button)
                        )
                    }
                }

                when {
                    !loaded -> Unit
                    prompts.isEmpty() -> item(key = "auto_prompt_empty") {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                            WtaFullEmptyState(
                                title = Strings.autoPromptEmptyTitle,
                                message = Strings.autoPromptEmptyMessage,
                                icon = Icons.Outlined.Add,
                                fillMaxSize = false
                            )
                        }
                    }
                    shown.isEmpty() -> item(key = "auto_prompt_no_results") {
                        Text(
                            text = Strings.autoPromptNoResults,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    else -> items(shown, key = { "auto_prompt_" + it.id }) { prompt ->
                        AutoPromptCard(
                            prompt = prompt,
                            masterEnabled = masterEnabled,
                            running = runningId == prompt.id,
                            agentLabel = agentLabel(prompt, profiles),
                            modelLabel = modelLabel(prompt, models),
                            canMoveUp = prompts.indexOfFirst { it.id == prompt.id } > 0,
                            canMoveDown = prompts.indexOfFirst { it.id == prompt.id } in 0 until prompts.lastIndex,
                            onRun = { run(prompt) },
                            onEdit = {
                                editTarget = prompt
                                editorOpen = true
                            },
                            onDelete = { deleteTarget = prompt },
                            onDuplicate = {
                                scope.launch {
                                    val name = Strings.autoPromptCopyName(prompt.name)
                                    store.duplicate(prompt.id, name)
                                        .onSuccess { apply(it) }
                                        .onFailure { toast(Strings.autoPromptRunFailed) }
                                }
                            },
                            onCopy = {
                                copyPromptToClipboard(context, prompt.text)
                                Toast.makeText(
                                    context,
                                    Strings.autoPromptCopied,
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onToggle = { enabled ->
                                scope.launch {
                                    store.setPromptEnabled(prompt.id, enabled)
                                        .onSuccess { apply(it) }
                                        .onFailure { toast(Strings.autoPromptRunFailed) }
                                }
                            },
                            onMoveUp = {
                                scope.launch {
                                    store.moveBy(prompt.id, -1)
                                        .onSuccess { apply(it) }
                                        .onFailure { toast(Strings.autoPromptRunFailed) }
                                }
                            },
                            onMoveDown = {
                                scope.launch {
                                    store.moveBy(prompt.id, 1)
                                        .onSuccess { apply(it) }
                                        .onFailure { toast(Strings.autoPromptRunFailed) }
                                }
                            }
                        )
                    }
                }

                if (prompts.isNotEmpty()) {
                    item(key = "auto_prompt_footer") {
                        Text(
                            text = Strings.autoPromptFooter,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = WtaSpacing.Small)
                        )
                    }
                }
            }
        }
    }

    if (editorOpen) {
        AutoPromptEditorDialog(
            initial = editTarget,
            profiles = profiles,
            models = models,
            onDismiss = { editorOpen = false },
            onSave = { prompt ->
                scope.launch {
                    store.save(prompt)
                        .onSuccess {
                            apply(it)
                            editorOpen = false
                        }
                        .onFailure { toast(Strings.autoPromptRunFailed) }
                }
            }
        )
    }

    deleteTarget?.let { target ->
        WtaAlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = Icons.Outlined.Delete,
            iconTint = MaterialTheme.colorScheme.error,
            title = Strings.autoPromptDeleteTitle,
            confirmButton = {
                WtaButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            store.delete(target.id)
                                .onSuccess { apply(it) }
                                .onFailure { toast(Strings.autoPromptRunFailed) }
                        }
                    },
                    text = Strings.delete,
                    variant = WtaButtonVariant.Destructive,
                    size = WtaButtonSize.Medium
                )
            },
            dismissButton = {
                WtaButton(
                    onClick = { deleteTarget = null },
                    text = Strings.cancel,
                    variant = WtaButtonVariant.Text,
                    size = WtaButtonSize.Medium
                )
            },
            content = {
                Text(
                    text = Strings.autoPromptDeleteMessage(target.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

// ---- header ---------------------------------------------------------------

@Composable
private fun AutoPromptHeader(
    masterEnabled: Boolean,
    promptCount: Int,
    onMasterChanged: (Boolean) -> Unit,
    onAdd: () -> Unit,
) {
    WtaSettingCard {
        WtaToggleRow(
            title = Strings.autoPromptMenuTitle,
            subtitle = Strings.autoPromptMasterDesc.ifDescriptionsShown(),
            checked = masterEnabled,
            onCheckedChange = onMasterChanged
        )
        WtaSectionDivider()
        Column(
            modifier = Modifier.padding(
                horizontal = WtaSpacing.RowHorizontal,
                vertical = WtaSpacing.ContentGap
            ),
            verticalArrangement = Arrangement.spacedBy(WtaSpacing.ContentGap)
        ) {
            Text(
                text = Strings.autoPromptCount(promptCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!masterEnabled) {
                WtaStatusBanner(
                    message = Strings.autoPromptMasterOffHint,
                    tone = WtaStatusTone.Warning
                )
            }
            WtaButton(
                onClick = onAdd,
                text = Strings.autoPromptAdd,
                leadingIcon = Icons.Outlined.Add,
                size = WtaButtonSize.Medium
            )
        }
    }
}

// ---- card -----------------------------------------------------------------

@Composable
private fun AutoPromptCard(
    prompt: AutoPrompt,
    masterEnabled: Boolean,
    running: Boolean,
    agentLabel: String,
    modelLabel: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onCopy: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var menuOpen by remember(prompt.id) { mutableStateOf(false) }
    val enabled = masterEnabled && prompt.enabled

    WtaCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(WtaSpacing.Large)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = prompt.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(WtaSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PromptTag(text = categoryLabel(prompt.category))
                    Text(
                        text = if (prompt.enabled) {
                            Strings.autoPromptStatusOn
                        } else {
                            Strings.autoPromptStatusOff
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (prompt.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Switch(
                checked = prompt.enabled,
                onCheckedChange = onToggle
            )
        }

        if (prompt.description.isNotBlank()) {
            Spacer(modifier = Modifier.size(WtaSpacing.ContentGap))
            Text(
                text = prompt.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.size(WtaSpacing.Small))
        Text(
            text = prompt.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.size(WtaSpacing.Small))
        Row(
            horizontalArrangement = Arrangement.spacedBy(WtaSpacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$agentLabel · $modelLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.size(WtaSpacing.Small))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WtaButton(
                onClick = onRun,
                text = Strings.autoPromptRun,
                leadingIcon = Icons.Filled.PlayArrow,
                size = WtaButtonSize.Small,
                enabled = enabled && !running
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = Strings.edit,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = Strings.delete,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = Strings.autoPromptMore,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(Strings.autoPromptDuplicate) },
                        onClick = {
                            menuOpen = false
                            onDuplicate()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.copy) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onCopy()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.autoPromptMoveUp) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.KeyboardArrowUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        enabled = canMoveUp,
                        onClick = {
                            menuOpen = false
                            onMoveUp()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.autoPromptMoveDown) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        enabled = canMoveDown,
                        onClick = {
                            menuOpen = false
                            onMoveDown()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptTag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 2.dp)
    )
}

// ---- editor ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoPromptEditorDialog(
    initial: AutoPrompt?,
    profiles: List<AgentProfile>,
    models: List<SavedModel>,
    onDismiss: () -> Unit,
    onSave: (AutoPrompt) -> Unit,
) {
    val key = initial?.id ?: "new"
    var name by remember(key) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(key) { mutableStateOf(initial?.description.orEmpty()) }
    var text by remember(key) { mutableStateOf(initial?.text.orEmpty()) }
    var category by remember(key) { mutableStateOf(initial?.category ?: AutoPromptCategory.CUSTOM) }
    var agentProfileId by remember(key) { mutableStateOf(initial?.agentProfileId) }
    var modelId by remember(key) { mutableStateOf(initial?.modelId) }
    var enabled by remember(key) { mutableStateOf(initial?.enabled ?: true) }
    var showNameError by remember(key) { mutableStateOf(false) }
    var showTextError by remember(key) { mutableStateOf(false) }

    val agentModels = models.filter { it.supportsFeature(AiFeature.AGENT) }

    WtaAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Outlined.Edit,
        title = if (initial == null) Strings.autoPromptNewTitle else Strings.autoPromptEditTitle,
        confirmButton = {
            WtaButton(
                onClick = {
                    showNameError = name.isBlank()
                    showTextError = text.isBlank()
                    if (showNameError || showTextError) return@WtaButton
                    onSave(
                        AutoPrompt(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            description = description,
                            text = text,
                            enabled = enabled,
                            category = category,
                            agentProfileId = agentProfileId,
                            modelId = modelId
                        )
                    )
                },
                text = Strings.save,
                size = WtaButtonSize.Medium
            )
        },
        dismissButton = {
            WtaButton(
                onClick = onDismiss,
                text = Strings.cancel,
                variant = WtaButtonVariant.Text,
                size = WtaButtonSize.Medium
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WtaSpacing.Medium)
            ) {
                PremiumTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showNameError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.autoPromptName) },
                    isError = showNameError,
                    supportingText = if (showNameError) {
                        { Text(Strings.autoPromptNameRequired) }
                    } else null,
                    singleLine = true
                )
                PremiumTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.autoPromptDescription) },
                    singleLine = true
                )
                PremiumTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        showTextError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.autoPromptText) },
                    isError = showTextError,
                    supportingText = if (showTextError) {
                        { Text(Strings.autoPromptTextRequired) }
                    } else null,
                    minLines = 4
                )

                EditorDropdown(
                    label = Strings.autoPromptCategory,
                    value = categoryLabel(category),
                    options = AutoPromptCategory.entries.map { it to categoryLabel(it) },
                    onSelect = { category = it }
                )

                EditorDropdown(
                    label = Strings.autoPromptAgent,
                    value = profiles.firstOrNull { it.id == agentProfileId }?.name
                        ?: Strings.autoPromptAgentActive,
                    options = listOf<Pair<String?, String>>(null to Strings.autoPromptAgentActive) +
                        profiles.map { it.id to it.name },
                    onSelect = { agentProfileId = it }
                )

                EditorDropdown(
                    label = Strings.autoPromptModel,
                    value = agentModels.firstOrNull { it.id == modelId }?.let(::modelOptionLabel)
                        ?: Strings.autoPromptModelDefault,
                    options = listOf<Pair<String?, String>>(
                        null to Strings.autoPromptModelDefault
                    ) + agentModels.map { it.id to modelOptionLabel(it) },
                    onSelect = { modelId = it }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Strings.enabled,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EditorDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (option, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ---- labels and defaults --------------------------------------------------

private fun categoryLabel(category: AutoPromptCategory): String = when (category) {
    AutoPromptCategory.RESEARCH -> Strings.autoPromptCategoryResearch
    AutoPromptCategory.CODING -> Strings.autoPromptCategoryCoding
    AutoPromptCategory.TESTING -> Strings.autoPromptCategoryTesting
    AutoPromptCategory.SECURITY -> Strings.autoPromptCategorySecurity
    AutoPromptCategory.MAINTENANCE -> Strings.autoPromptCategoryMaintenance
    AutoPromptCategory.CUSTOM -> Strings.autoPromptCategoryCustom
}

private fun agentLabel(prompt: AutoPrompt, profiles: List<AgentProfile>): String =
    profiles.firstOrNull { it.id == prompt.agentProfileId }?.name ?: Strings.autoPromptAgentActive

private fun modelLabel(prompt: AutoPrompt, models: List<SavedModel>): String =
    prompt.modelId?.let { id -> models.firstOrNull { it.id == id } }
        ?.let(::modelDisplayName)
        ?: Strings.autoPromptModelDefault

private fun modelDisplayName(model: SavedModel): String =
    model.alias?.takeIf { it.isNotBlank() } ?: model.model.name

/** Dropdown label: the provider is what makes two same-named models distinguishable. */
private fun modelOptionLabel(model: SavedModel): String =
    "${modelDisplayName(model)} · ${model.model.provider.displayName}"

private fun blockedMessage(reason: AutoPromptRunPlan.BlockedReason): String = when (reason) {
    AutoPromptRunPlan.BlockedReason.MASTER_DISABLED -> Strings.autoPromptMasterOff
    AutoPromptRunPlan.BlockedReason.PROMPT_DISABLED -> Strings.autoPromptPromptOff
    AutoPromptRunPlan.BlockedReason.NO_MODEL -> Strings.autoPromptNoModel
    AutoPromptRunPlan.BlockedReason.NO_API_KEY -> Strings.autoPromptNoApiKey
}

private fun copyPromptToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Sherwood Studio", text))
}

/**
 * The five starter prompts behind categories A–E. They are ordinary saved prompts: fully
 * editable, disableable and deletable. Prompt bodies stay in English because they address
 * the model; the names and descriptions follow the app language at first open.
 */
private fun defaultAutoPrompts(): List<AutoPrompt> = listOf(
    AutoPrompt(
        name = Strings.autoPromptTplResearchName,
        description = Strings.autoPromptTplResearchDesc,
        category = AutoPromptCategory.RESEARCH,
        text = """
            You are researching a project before any code is written.

            1. Identify what the project is, which platforms it targets and which build tools it uses.
            2. Read the README, licence files, changelog and any docs folder; summarise the licence and the obligations it places on redistribution.
            3. List the main technologies, their versions and where each one is configured.
            4. Point out the entry points and the most important modules, with file paths.
            5. Note anything unclear or contradictory, and ask before guessing.

            Deliver a short brief: purpose, licence, stack, structure, open questions.
        """.trimIndent()
    ),
    AutoPrompt(
        name = Strings.autoPromptTplCodingName,
        description = Strings.autoPromptTplCodingDesc,
        category = AutoPromptCategory.CODING,
        text = """
            Implement one feature end to end.

            1. Restate the goal and the acceptance criteria in two or three sentences.
            2. Read the files you will touch and the neighbouring patterns before editing; follow the project's conventions.
            3. Make the change complete: UI, state, persistence and error handling, not only the happy path.
            4. Leave unrelated behaviour untouched, and do not reformat files you did not need to change.
            5. Build or run the relevant checks and report the exact commands and results.

            If the request is ambiguous, state the assumption you chose instead of stalling.
        """.trimIndent()
    ),
    AutoPrompt(
        name = Strings.autoPromptTplTestingName,
        description = Strings.autoPromptTplTestingDesc,
        category = AutoPromptCategory.TESTING,
        text = """
            Run the project's tests and make them trustworthy.

            1. Find the test command from the build files and run the full suite.
            2. For every failure, reproduce it, find the root cause and fix the cause, not the assertion.
            3. If a test is flaky, explain why and make it deterministic instead of deleting it.
            4. Report: the command, pass/fail counts, and what you changed.
        """.trimIndent()
    ),
    AutoPrompt(
        name = Strings.autoPromptTplSecurityName,
        description = Strings.autoPromptTplSecurityDesc,
        category = AutoPromptCategory.SECURITY,
        text = """
            Perform a focused security review.

            1. Inventory the permissions and capabilities the project requests and flag any it does not need.
            2. Check dependency versions against known vulnerable releases where you can, and list what you could not verify.
            3. Look for unsafe handling of untrusted input: file paths, URLs, HTML/JS injection, command execution and exported components.
            4. Check how secrets and credentials are stored and whether they can leak into logs, backups or generated artifacts.
            5. Report findings by severity with file and line, and propose the smallest correct fix. Do not change code unless I ask.
        """.trimIndent()
    ),
    AutoPrompt(
        name = Strings.autoPromptTplMaintenanceName,
        description = Strings.autoPromptTplMaintenanceDesc,
        category = AutoPromptCategory.MAINTENANCE,
        text = """
            Review the project's dependencies for maintenance work.

            1. List the declared dependencies and their current versions.
            2. Identify outdated or unmaintained ones, and note the breaking changes in the next major versions.
            3. Propose an upgrade order that keeps the project buildable at every step, with the risk of each step.
            4. Apply the safe upgrades if I ask, running the build and tests after each group.
        """.trimIndent()
    )
)
