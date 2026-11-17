package com.sherwood.wta.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sidekick.input.SidekickEndpointError
import com.sherwood.wta.core.sidekick.input.SidekickSpeechEndpoint
import com.sherwood.wta.core.sidekick.input.SidekickSpeechEndpointPolicy
import com.sherwood.wta.core.sidekick.input.SidekickSpeechEndpointStore
import com.sherwood.wta.core.sidekick.input.SidekickSpeechEngine
import com.sherwood.wta.core.sidekick.input.SidekickSpeechFailure
import com.sherwood.wta.core.sidekick.input.SidekickSpeechProtocol
import com.sherwood.wta.core.sidekick.input.SidekickWhisperTranscriber
import com.sherwood.wta.core.sidekick.input.toSpeechFailure
import com.sherwood.wta.ui.components.PremiumTextField
import com.sherwood.wta.ui.design.WtaAlertDialog
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaButtonSize
import com.sherwood.wta.ui.design.WtaButtonVariant
import com.sherwood.wta.ui.design.WtaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks the dictation engine.
 *
 * Two options only, because these are the two that exist: the platform speech service
 * (no setup) and a Whisper-compatible server (the only reliable path for Serbian and
 * Swedish, and the one that can keep audio on the local network).
 */
@Composable
fun SidekickSpeechEngineDialog(
    selected: SidekickSpeechEngine,
    onSelect: (SidekickSpeechEngine) -> Unit,
    onDismiss: () -> Unit
) {
    WtaAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Outlined.Mic,
        title = Strings.sidekickSpeechEngine,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SpeechEngineOption(
                    label = Strings.sidekickSpeechEngineSystem,
                    selected = selected == SidekickSpeechEngine.SYSTEM,
                    onClick = { onSelect(SidekickSpeechEngine.SYSTEM) }
                )
                SpeechEngineOption(
                    label = Strings.sidekickSpeechEngineWhisper,
                    selected = selected == SidekickSpeechEngine.WHISPER,
                    onClick = { onSelect(SidekickSpeechEngine.WHISPER) }
                )
            }
        }
    )
}

@Composable
private fun SpeechEngineOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Configures the Whisper-compatible server.
 *
 * The URL is validated before it is stored, and "Test connection" posts one second of
 * silence to the real transcription endpoint: a reachable server with the wrong protocol
 * or path is exactly the failure this is meant to catch, and a `/models` probe would not
 * catch it.
 *
 * The API key is written through [SidekickSpeechEndpointStore] (AES-GCM); this dialog
 * never keeps it beyond the composition and never logs it.
 */
@Composable
fun SidekickSpeechServerDialog(
    store: SidekickSpeechEndpointStore,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val transcriber = remember { SidekickWhisperTranscriber() }

    var protocol by remember { mutableStateOf(store.protocol) }
    var url by remember { mutableStateOf(store.baseUrl) }
    var model by remember { mutableStateOf(store.model) }
    var token by remember { mutableStateOf("") }
    var hasStoredToken by remember { mutableStateOf(store.hasToken()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    var saving by remember { mutableStateOf(false) }

    fun tokenPresent(): Boolean = token.isNotBlank() || hasStoredToken

    fun save() {
        if (saving) return
        val validation = SidekickSpeechEndpointPolicy.validate(url, tokenPresent())
        if (validation is SidekickSpeechEndpointPolicy.Validation.Invalid) {
            errorText = urlErrorText(validation.reason)
            return
        }
        saving = true
        errorText = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                when (val saved = store.saveServer(protocol, url, model)) {
                    is SidekickSpeechEndpointStore.SaveResult.Invalid ->
                        urlErrorText(saved.reason)

                    SidekickSpeechEndpointStore.SaveResult.StorageFailed ->
                        Strings.sidekickSpeechFailed

                    SidekickSpeechEndpointStore.SaveResult.Saved -> {
                        if (token.isNotBlank()) {
                            val result = store.saveToken(token)
                            if (result.isFailure) Strings.sidekickSpeechFailed else null
                        } else {
                            null
                        }
                    }
                }
            }
            saving = false
            if (failure == null) {
                onSaved()
            } else {
                errorText = failure
            }
        }
    }

    fun testConnection() {
        if (testState is TestState.Running) return
        val validation = SidekickSpeechEndpointPolicy.validate(url, tokenPresent())
        if (validation is SidekickSpeechEndpointPolicy.Validation.Invalid) {
            errorText = urlErrorText(validation.reason)
            return
        }
        val base = (validation as SidekickSpeechEndpointPolicy.Validation.Ok).value.baseUrl
        errorText = null
        testState = TestState.Running
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                // The key being tested is the one in the field, not necessarily the
                // stored one: "does this key work" is the question, and saving first
                // would leave a bad key behind if the answer is no.
                val effectiveToken = token.ifBlank { if (hasStoredToken) store.token() else null }
                val endpoint = SidekickSpeechEndpoint(
                    protocol = protocol,
                    baseUrl = base,
                    model = model.ifBlank { SidekickSpeechEndpointStore.DEFAULT_OPENAI_MODEL },
                    token = effectiveToken
                )
                transcriber.probe(endpoint).fold(
                    onSuccess = { TestState.Ok },
                    onFailure = { TestState.Failed(testFailureText(it.toSpeechFailure())) }
                )
            }
            testState = outcome
        }
    }

    WtaAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Outlined.Dns,
        title = Strings.sidekickSpeechServerDialogTitle,
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !saving) {
                Text(Strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        },
        // Named: WtaAlertDialog declares `content` before `properties`.
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = Strings.sidekickSpeechProtocol,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                ProtocolOption(
                    label = Strings.sidekickSpeechProtocolOpenAi,
                    selected = protocol == SidekickSpeechProtocol.OPENAI_COMPATIBLE,
                    onClick = { protocol = SidekickSpeechProtocol.OPENAI_COMPATIBLE }
                )
                ProtocolOption(
                    label = Strings.sidekickSpeechProtocolWhisperCpp,
                    selected = protocol == SidekickSpeechProtocol.WHISPER_CPP,
                    onClick = { protocol = SidekickSpeechProtocol.WHISPER_CPP }
                )

                Spacer(modifier = Modifier.height(WtaSpacing.Small))
                PremiumTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        errorText = null
                    },
                    label = { Text(Strings.sidekickSpeechUrl) },
                    placeholder = { Text(Strings.sidekickSpeechUrlHint) },
                    singleLine = true,
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth()
                )
                errorText?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (protocol == SidekickSpeechProtocol.OPENAI_COMPATIBLE) {
                    Spacer(modifier = Modifier.height(WtaSpacing.Small))
                    PremiumTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(Strings.sidekickSpeechModel) },
                        placeholder = { Text(Strings.sidekickSpeechModelHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(WtaSpacing.Small))
                PremiumTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        errorText = null
                    },
                    label = { Text(Strings.sidekickSpeechToken) },
                    placeholder = {
                        Text(
                            if (hasStoredToken) Strings.sidekickSpeechTokenStored
                            else Strings.sidekickSpeechTokenHint
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (hasStoredToken) {
                    Text(
                        text = Strings.sidekickSpeechTokenStored,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.clearToken() }
                                hasStoredToken = false
                                token = ""
                            }
                        }
                    ) {
                        Text(Strings.remove)
                    }
                }

                Spacer(modifier = Modifier.height(WtaSpacing.Small))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WtaButton(
                        onClick = { testConnection() },
                        text = Strings.sidekickSpeechTest,
                        variant = WtaButtonVariant.Outlined,
                        size = WtaButtonSize.Small,
                        enabled = testState !is TestState.Running
                    )
                    Spacer(modifier = Modifier.width(WtaSpacing.Small))
                    when (val state = testState) {
                        TestState.Idle -> Unit
                        TestState.Running -> Text(
                            text = Strings.sidekickSpeechTesting,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        TestState.Ok -> Text(
                            text = Strings.sidekickSpeechTestOk,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        is TestState.Failed -> Text(
                            text = state.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun ProtocolOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Ok : TestState
    data class Failed(val message: String) : TestState
}

/** User-facing text for a refused URL. */
private fun urlErrorText(reason: SidekickEndpointError): String = when (reason) {
    SidekickEndpointError.EMPTY -> Strings.sidekickSpeechUrlEmpty
    SidekickEndpointError.MALFORMED -> Strings.sidekickSpeechUrlMalformed
    SidekickEndpointError.UNSUPPORTED_SCHEME -> Strings.sidekickSpeechUrlScheme
    SidekickEndpointError.PUBLIC_CLEARTEXT -> Strings.sidekickSpeechUrlPublicHttp
    SidekickEndpointError.CLEARTEXT_WITH_TOKEN -> Strings.sidekickSpeechUrlTokenHttp
    SidekickEndpointError.CREDENTIALS_IN_URL -> Strings.sidekickSpeechUrlCredentials
    SidekickEndpointError.QUERY_OR_FRAGMENT -> Strings.sidekickSpeechUrlQuery
}

/** User-facing text for a failed probe. */
private fun testFailureText(failure: SidekickSpeechFailure): String = when (failure) {
    SidekickSpeechFailure.NETWORK -> Strings.sidekickSpeechNetwork
    SidekickSpeechFailure.AUTH -> Strings.sidekickSpeechAuth
    SidekickSpeechFailure.ENDPOINT -> Strings.sidekickSpeechEndpoint
    SidekickSpeechFailure.THROTTLED -> Strings.sidekickSpeechThrottled
    SidekickSpeechFailure.AUDIO -> Strings.sidekickSpeechAudio
    SidekickSpeechFailure.NOT_CONFIGURED -> Strings.sidekickSpeechNotConfigured
    SidekickSpeechFailure.NO_MATCH -> Strings.sidekickSpeechTestOk
    SidekickSpeechFailure.NO_SERVICE -> Strings.sidekickSpeechUnavailable
    SidekickSpeechFailure.PERMISSION -> Strings.sidekickSpeechPermission
    SidekickSpeechFailure.BUSY -> Strings.sidekickSpeechBusy
    SidekickSpeechFailure.LANGUAGE_UNSUPPORTED -> Strings.sidekickSpeechLanguage
    SidekickSpeechFailure.UNKNOWN -> Strings.sidekickSpeechFailed
}
