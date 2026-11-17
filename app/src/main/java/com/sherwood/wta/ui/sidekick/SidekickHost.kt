package com.sherwood.wta.ui.sidekick

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sidekick.SidekickTurn
import com.sherwood.wta.core.sidekick.input.SidekickAttachment
import com.sherwood.wta.core.sidekick.input.SidekickImageAttachment
import com.sherwood.wta.core.sidekick.input.SidekickTextAttachment
import com.sherwood.wta.ui.agent.components.MarkdownText
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaChip
import com.sherwood.wta.ui.design.WtaSpacing
import com.sherwood.wta.ui.design.WtaTextField
import com.sherwood.wta.util.BoundedBitmaps
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The floating Sidekick: a draggable button plus a non-modal panel that floats above
 * whatever screen the user is on.
 *
 * Two design decisions worth keeping:
 *
 * 1. **No scrim.** The panel is an assistant, not a modal. A scrim would block the screen
 *    underneath and make "watch the agent work while you ask a question" impossible. Only
 *    the panel's own bounds consume touches.
 * 2. **The button is draggable and remembered.** The overlay covers every screen, so any
 *    fixed position eventually sits on top of a control the user needs.
 */
@Composable
fun SidekickHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: SidekickViewModel = viewModel(factory = SidekickViewModel.factory(app))
    val state by vm.ui.collectAsStateWithLifecycle()

    // The switch lives in Settings; re-read it whenever the app comes back into the
    // foreground so the overlay appears/disappears without a restart.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshFromSettings()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A panel left open when the feature is switched off must not reappear later.
    LaunchedEffect(state.enabled) {
        if (!state.enabled && state.open) vm.close()
    }

    if (!state.enabled) return

    SidekickOverlay(state = state, vm = vm, modifier = modifier)
}

@Composable
private fun SidekickOverlay(
    state: SidekickUiState,
    vm: SidekickViewModel,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // safeDrawing folds system bars, display cutout and the keyboard into one
            // inset, so the button never lands under a gesture bar and the panel never
            // hides its own input behind the IME.
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val fabSize = 56.dp
        val margin = 12.dp
        val travel = (maxHeight - fabSize).coerceAtLeast(0.dp)

        // Anchored by fraction: a rotation or a split-screen resize keeps the button at
        // the same relative height instead of pushing it off-screen.
        var anchor by remember(state.fabAnchor) { mutableStateOf(state.fabAnchor) }

        val panelWidth = if (maxWidth < 480.dp) maxWidth - margin * 2 else 420.dp
        val panelHeight = (maxHeight * 0.66f).coerceAtMost(560.dp)

        if (!state.open) {
            FloatingActionButton(
                onClick = { vm.open() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = travel * anchor)
                    .padding(end = margin)
                    .size(fabSize)
                    .draggableAnchor(
                        travel = travel,
                        anchor = anchor,
                        onAnchorChange = { anchor = it },
                        onAnchorSettled = { vm.setFabAnchor(it) }
                    )
                    .semantics { contentDescription = Strings.sidekickOpen },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        val pickers = rememberSidekickPickers(vm)

        AnimatedVisibility(
            visible = state.open,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(durationMillis = 220)
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = slideOutVertically(
                targetOffsetY = { it / 3 },
                animationSpec = tween(durationMillis = 180)
            ) + fadeOut(animationSpec = tween(durationMillis = 140))
        ) {
            SidekickPanel(
                state = state,
                vm = vm,
                pickers = pickers,
                width = panelWidth,
                height = panelHeight,
                modifier = Modifier.padding(margin)
            )
        }
    }
}

/**
 * Vertical drag on the floating button, expressed as a fraction of its travel so the
 * position survives a resize. The anchor is read through [rememberUpdatedState] because
 * `pointerInput` does not restart when it changes — without that, every drag after the
 * first would begin from the position the button had when the gesture handler was built.
 */
@Composable
private fun Modifier.draggableAnchor(
    travel: Dp,
    anchor: Float,
    onAnchorChange: (Float) -> Unit,
    onAnchorSettled: (Float) -> Unit
): Modifier {
    if (travel <= 0.dp) return this
    val latestAnchor = rememberUpdatedState(anchor)
    return this.pointerInput(travel) {
        val travelPx = travel.toPx().coerceAtLeast(1f)
        var current = latestAnchor.value
        var dragging = false
        detectDragGestures(
            onDragStart = {
                current = latestAnchor.value
                dragging = true
            },
            onDragEnd = { if (dragging) onAnchorSettled(current) },
            onDragCancel = { if (dragging) onAnchorSettled(current) },
            onDrag = { change, dragAmount ->
                change.consume()
                current = (current + dragAmount.y / travelPx).coerceIn(0f, 1f)
                onAnchorChange(current)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SidekickPanel(
    state: SidekickUiState,
    vm: SidekickViewModel,
    pickers: SidekickPickers,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(width)
            .height(height),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SidekickHeader(state = state, vm = vm)
            SidekickBody(state = state, vm = vm, modifier = Modifier.weight(1f))
            state.error?.let { message ->
                SidekickError(message = message, onDismiss = { vm.dismissError() })
            }
            SidekickComposer(state = state, vm = vm, pickers = pickers)
        }
    }
}

@Composable
private fun SidekickHeader(state: SidekickUiState, vm: SidekickViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = WtaSpacing.Large, end = WtaSpacing.Small, top = WtaSpacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(WtaSpacing.Small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = Strings.sidekickTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val model = state.bindingLabel
            val contextTitle = state.contextTitle
            val subtitle = when {
                // The specific reason ("no model" vs "API key missing") is what the header
                // shows; a bare "No model" sent users to the wrong settings screen.
                state.bindingError != null -> Strings.sidekickNoModel
                model != null && state.includeContext && contextTitle != null ->
                    Strings.sidekickModelWithContext(model, contextTitle)
                else -> model
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (state.turns.isNotEmpty() && !state.streaming) {
            IconButton(onClick = { vm.clear() }) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = Strings.sidekickClear,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = { vm.close() }) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = Strings.sidekickClose,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SidekickBody(
    state: SidekickUiState,
    vm: SidekickViewModel,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val lastTurn = state.turns.lastOrNull()

    // Follow the stream without animating on every token.
    LaunchedEffect(state.turns.size, lastTurn?.text?.length) {
        if (state.turns.isNotEmpty()) {
            listState.scrollToItem(state.turns.lastIndex)
        }
    }

    if (state.turns.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = WtaSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(WtaSpacing.Small)
        ) {
            Text(
                text = Strings.sidekickIntro,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(WtaSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(WtaSpacing.Small)
            ) {
                WtaChip(
                    selected = false,
                    onClick = { vm.fillDraft(Strings.sidekickQuickExplain) },
                    label = Strings.sidekickQuickExplainLabel,
                    showSelectedCheck = false
                )
                WtaChip(
                    selected = false,
                    onClick = { vm.fillDraft(Strings.sidekickQuickError) },
                    label = Strings.sidekickQuickErrorLabel,
                    showSelectedCheck = false
                )
                WtaChip(
                    selected = false,
                    onClick = { vm.fillDraft(Strings.sidekickQuickPrompt) },
                    label = Strings.sidekickQuickPromptLabel,
                    showSelectedCheck = false
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = WtaSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(WtaSpacing.Small)
    ) {
        itemsIndexed(state.turns) { _, turn ->
            SidekickBubble(
                turn = turn,
                streaming = state.streaming && turn === lastTurn
            )
        }
    }
}

@Composable
private fun SidekickBubble(turn: SidekickTurn, streaming: Boolean) {
    if (turn.role == SidekickTurn.Role.USER) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = WtaSpacing.Medium,
                        vertical = WtaSpacing.Small
                    ),
                    verticalArrangement = Arrangement.spacedBy(WtaSpacing.Tiny)
                ) {
                    if (turn.attachments.isNotEmpty()) {
                        AttachmentStrip(
                            attachments = turn.attachments,
                            onRemove = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (turn.text.isNotBlank()) {
                        Text(
                            text = turn.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    } else {
        val text = if (streaming && turn.text.isBlank()) Strings.sidekickThinking else turn.text
        MarkdownText(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SidekickError(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WtaSpacing.Medium, vertical = WtaSpacing.Tiny)
    ) {
        Row(
            modifier = Modifier.padding(
                start = WtaSpacing.Medium,
                top = WtaSpacing.Small,
                end = WtaSpacing.Tiny,
                bottom = WtaSpacing.Small
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = Strings.sidekickClose,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** The pickers the composer needs, created once above the panel's animation. */
private class SidekickPickers(
    val pickImage: () -> Unit,
    val takePhoto: () -> Unit,
    val pickFiles: () -> Unit,
    val dictate: () -> Unit
)

/**
 * Creates the document pickers for the composer.
 *
 * They are registered here, above the panel, rather than inside it: a launcher created
 * inside a disappearing panel can lose its result when the panel closes in the same frame
 * the picker returns, which would drop the file the user just chose.
 *
 * No storage permission is requested anywhere — the photo picker and the document picker
 * both hand back a read-only `content://` grant.
 */
@Composable
private fun rememberSidekickPickers(vm: SidekickViewModel): SidekickPickers {
    val context = LocalContext.current
    var pendingCapture by remember { mutableStateOf<File?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.addAttachments(listOf(uri)) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addAttachments(uris) }

    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCapture
        pendingCapture = null
        if (success && file != null) vm.addCameraPhoto(file) else file?.delete()
    }

    val capture = {
        val directory = File(context.cacheDir, "sidekick").apply { mkdirs() }
        val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")
        pendingCapture = file
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            camera.launch(uri)
        } catch (t: Throwable) {
            // The provider is declared in the manifest; a failure here means the ROM or the
            // installer mangled it, which is worth reporting rather than swallowing.
            pendingCapture = null
            runCatching { file.delete() }
            vm.reportError(Strings.sidekickPhotoFailed)
        }
        Unit
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) capture() else vm.reportError(Strings.sidekickCameraPermission)
    }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startVoiceInput() else vm.reportError(Strings.sidekickSpeechPermission)
    }

    return SidekickPickers(
        pickImage = {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        takePhoto = {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) capture() else cameraPermission.launch(Manifest.permission.CAMERA)
        },
        // `application/octet-stream` is included because file managers report `.log`, `.kt`
        // and friends that way. The reader still decides by extension and refuses binaries,
        // so a broader picker cannot smuggle a PDF into the prompt.
        pickFiles = { filePicker.launch(FILE_MIME_TYPES) },
        dictate = {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.startVoiceInput() else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    )
}

@Composable
private fun SidekickComposer(
    state: SidekickUiState,
    vm: SidekickViewModel,
    pickers: SidekickPickers
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = WtaSpacing.Medium,
                end = WtaSpacing.Medium,
                top = WtaSpacing.Tiny,
                bottom = WtaSpacing.Medium
            )
    ) {
        state.bindingError?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(WtaSpacing.Tiny))
        }
        if (state.pendingAttachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = state.pendingAttachments,
                onRemove = vm::removeAttachment,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(WtaSpacing.Tiny))
        }
        if (state.speechListening) {
            ListeningRow(
                onDone = { vm.stopVoiceInput() },
                onCancel = { vm.cancelVoiceInput() }
            )
            Spacer(Modifier.height(WtaSpacing.Tiny))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            WtaTextField(
                value = state.draft,
                onValueChange = { vm.setDraft(it) },
                placeholder = if (state.speechListening) {
                    Strings.sidekickListening
                } else {
                    Strings.sidekickInputHint
                },
                maxLines = 4,
                enabled = !state.speechListening,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(WtaSpacing.Small))
            if (state.streaming) {
                WtaButton(
                    onClick = { vm.stop() },
                    text = Strings.sidekickStop,
                    variant = WtaButtonVariant.Tonal,
                    size = WtaButtonSize.Small,
                    leadingIcon = Icons.Outlined.Stop
                )
            } else {
                WtaButton(
                    onClick = { vm.send() },
                    text = Strings.sidekickSend,
                    size = WtaButtonSize.Small,
                    enabled = state.canSend,
                    leadingIcon = Icons.AutoMirrored.Outlined.Send
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AttachMenuButton(
                state = state,
                pickers = pickers
            )
            IconButton(
                onClick = { pickers.dictate() },
                enabled = !state.streaming && !state.speechListening && state.speechAvailable
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = Strings.sidekickDictate,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        // A disabled image action without a reason reads as a broken button, so the panel
        // always says why images are unavailable.
        if (!state.visionSupported) {
            Text(
                text = Strings.sidekickVisionUnsupported,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AttachMenuButton(state: SidekickUiState, pickers: SidekickPickers) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = !state.streaming && !state.speechListening
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = Strings.sidekickAttach,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(Strings.sidekickAttachImage) },
                leadingIcon = {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                },
                enabled = state.visionSupported,
                onClick = {
                    expanded = false
                    if (state.visionSupported) pickers.pickImage() else Unit
                }
            )
            DropdownMenuItem(
                text = { Text(Strings.sidekickTakePhoto) },
                leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                enabled = state.visionSupported,
                onClick = {
                    expanded = false
                    if (state.visionSupported) pickers.takePhoto() else Unit
                }
            )
            DropdownMenuItem(
                text = { Text(Strings.sidekickFromFiles) },
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                onClick = {
                    expanded = false
                    pickers.pickFiles()
                }
            )
        }
    }
}

/**
 * Shows the staged attachments above the composer.
 *
 * Images render as a thumbnail so the user can confirm they picked the right screenshot;
 * text files render as a chip with the character count, which is also how the truncation
 * becomes visible instead of surprising the model.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentStrip(
    attachments: List<SidekickAttachment>,
    onRemove: ((Int) -> Unit)?,
    tint: androidx.compose.ui.graphics.Color
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(WtaSpacing.Tiny),
        verticalArrangement = Arrangement.spacedBy(WtaSpacing.Tiny)
    ) {
        attachments.forEachIndexed { index, attachment ->
            when (attachment) {
                is SidekickImageAttachment -> ImageChip(
                    attachment = attachment,
                    onRemove = onRemove?.let { remove -> { remove(index) } }
                )
                is SidekickTextAttachment -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(start = WtaSpacing.Small, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = attachment.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                        if (onRemove != null) {
                            IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = Strings.sidekickRemoveAttachment,
                                    tint = tint,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else {
                            Spacer(Modifier.width(WtaSpacing.Small))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageChip(attachment: SidekickImageAttachment, onRemove: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
            initialValue = null,
            key1 = attachment
        ) {
            // Decoding happens off the main thread: two 1568px JPEGs on the main thread is
            // a visible hitch on lower-end devices.
            value = withContext(Dispatchers.IO) {
                BoundedBitmaps.decodeBoundedBitmapBytes(attachment.bytes, 160)?.asImageBitmap()
            }
        }
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = Strings.sidekickRemoveAttachment,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun ListeningRow(onDone: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(WtaSpacing.Small))
        Text(
            text = Strings.sidekickListening,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        // Finishing the dictation keeps the transcript; only the X discards it. Without
        // this, the only way to end a long recording was to wait for the recognizer's
        // silence timeout.
        IconButton(onClick = onDone, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = Strings.done,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = Strings.cancel,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private val FILE_MIME_TYPES = arrayOf(
    "text/*",
    "application/json",
    "application/xml",
    "application/x-yaml",
    "application/javascript",
    "application/octet-stream"
)
