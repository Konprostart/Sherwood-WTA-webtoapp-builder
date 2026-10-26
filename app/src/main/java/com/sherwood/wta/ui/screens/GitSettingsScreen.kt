package com.sherwood.wta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.frontend.GitHubTokenFormat
import com.sherwood.wta.core.frontend.GitHubTokenStore
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.ui.design.WtaAlertDialog
import com.sherwood.wta.ui.design.WtaButton
import com.sherwood.wta.ui.design.WtaScreen
import com.sherwood.wta.ui.design.WtaSection
import com.sherwood.wta.ui.design.WtaSectionDivider
import com.sherwood.wta.ui.design.WtaSettingCard
import com.sherwood.wta.ui.design.WtaSettingRow
import com.sherwood.wta.ui.design.WtaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GITHUB_TOKEN_URL = "https://github.com/settings/tokens"

@Composable
fun GitSettingsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { GitHubTokenStore(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    var hasToken by remember { mutableStateOf(false) }
    var savedMasked by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun refreshTokenState() {
        scope.launch {
            val token = withContext(Dispatchers.IO) { store.currentToken() }
            hasToken = token != null
            savedMasked = token?.let { GitHubTokenFormat.masked(it) }
        }
    }

    LaunchedEffect(Unit) { refreshTokenState() }

    fun saveToken() {
        val candidate = GitHubTokenFormat.normalize(input)
        if (!GitHubTokenFormat.isValid(candidate)) {
            invalid = true
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.saveToken(candidate) }
            busy = false
            if (result.isSuccess) {
                input = ""
                invalid = false
                refreshTokenState()
                snackbarHostState.showSnackbar(Strings.gitTokenSaved)
            } else {
                snackbarHostState.showSnackbar(Strings.gitTokenSaveFailed)
            }
        }
    }

    fun removeToken() {
        scope.launch {
            withContext(Dispatchers.IO) { store.clearToken() }
            hasToken = false
            savedMasked = null
            input = ""
            invalid = false
            snackbarHostState.showSnackbar(Strings.gitTokenRemoved)
        }
    }

    WtaScreen(
        title = Strings.gitSettingsTitle,
        snackbarHostState = snackbarHostState,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = WtaSpacing.ScreenHorizontal,
                    vertical = WtaSpacing.ScreenVertical
                ),
            verticalArrangement = Arrangement.spacedBy(WtaSpacing.SectionGap)
        ) {
            WtaSection(
                title = Strings.gitTokenTitle,
                description = Strings.gitSettingsDesc
            ) {
                WtaSettingCard {
                    WtaSettingRow(
                        title = if (hasToken) {
                            Strings.gitTokenConfigured
                        } else {
                            Strings.gitTokenNotConfigured
                        },
                        subtitle = if (hasToken) savedMasked else Strings.gitTokenHint,
                        icon = Icons.Outlined.Key,
                        active = hasToken
                    ) {
                        if (hasToken) {
                            TextButton(
                                onClick = { confirmRemove = true },
                                enabled = !busy
                            ) {
                                Text(
                                    text = Strings.remove,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    WtaSectionDivider()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = WtaSpacing.RowHorizontal,
                                vertical = WtaSpacing.RowVertical
                            ),
                        verticalArrangement = Arrangement.spacedBy(WtaSpacing.Small)
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = {
                                input = it
                                invalid = false
                            },
                            label = { Text(Strings.gitTokenLabel) },
                            placeholder = { Text(Strings.gitTokenPlaceholder) },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 4,
                            isError = invalid,
                            visualTransformation = if (showToken) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(
                                        imageVector = if (showToken) {
                                            Icons.Outlined.VisibilityOff
                                        } else {
                                            Icons.Outlined.Visibility
                                        },
                                        contentDescription = if (showToken) {
                                            Strings.gitTokenHide
                                        } else {
                                            Strings.gitTokenShow
                                        }
                                    )
                                }
                            },
                            supportingText = {
                                Text(
                                    if (invalid) {
                                        Strings.gitTokenInvalid
                                    } else {
                                        Strings.gitTokenHint
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        WtaButton(
                            onClick = { saveToken() },
                            text = Strings.save,
                            enabled = input.isNotBlank() && !busy,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { uriHandler.openUri(GITHUB_TOKEN_URL) },
                                contentPadding = PaddingValues(
                                    horizontal = 0.dp,
                                    vertical = 4.dp
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(Strings.gitTokenCreate)
                            }
                        }

                        Text(
                            text = Strings.gitTokenSecurityNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        WtaAlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = Strings.remove,
            text = Strings.gitTokenRemoveConfirm,
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        removeToken()
                    }
                ) {
                    Text(
                        text = Strings.remove,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
}
