package com.sherwood.wta.ui.sidekick

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sidekick.SidekickTurn
import com.sherwood.wta.ui.agent.components.MarkdownText
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaChip
import com.sherwood.wta.ui.design.WtaSpacing
import com.sherwood.wta.ui.design.WtaTextField

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
            SidekickComposer(state = state, vm = vm)
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
                Text(
                    text = turn.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(
                        horizontal = WtaSpacing.Medium,
                        vertical = WtaSpacing.Small
                    )
                )
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

@Composable
private fun SidekickComposer(state: SidekickUiState, vm: SidekickViewModel) {
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
        Row(verticalAlignment = Alignment.Bottom) {
            WtaTextField(
                value = state.draft,
                onValueChange = { vm.setDraft(it) },
                placeholder = Strings.sidekickInputHint,
                maxLines = 4,
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
    }
}
