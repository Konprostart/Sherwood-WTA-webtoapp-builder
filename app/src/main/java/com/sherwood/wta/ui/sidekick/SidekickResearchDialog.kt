package com.sherwood.wta.ui.sidekick

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sidekick.SidekickText
import com.sherwood.wta.ui.design.WtaAlertDialog
import com.sherwood.wta.ui.design.WtaSpacing
import com.sherwood.wta.ui.design.WtaTextField

/**
 * Web-research settings for the floating assistant.
 *
 * It lives in the panel rather than on the settings screen because the whole overlay is
 * meant to stay self-contained: turning research on is a decision made while looking at a
 * question, not while scrolling a preferences list.
 *
 * The endpoint is validated before it is stored, with the same policy the fetcher applies at
 * request time. Saving a URL that would be refused on every use is worse than refusing it
 * here, where the reason can be shown next to the field.
 */
@Composable
internal fun SidekickResearchDialog(
    enabled: Boolean,
    endpoint: String?,
    onEnabledChange: (Boolean) -> Unit,
    onSaveEndpoint: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var endpointText by remember { mutableStateOf(endpoint.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    WtaAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Outlined.TravelExplore,
        title = SidekickText.webResearch,
        confirmButton = {
            TextButton(onClick = {
                if (onSaveEndpoint(endpointText)) {
                    onDismiss()
                } else {
                    error = SidekickText.webResearchInvalidServer
                }
            }) {
                Text(Strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        },
        // Named: WtaAlertDialog declares `content` before `properties`, so a trailing
        // lambda would bind to the wrong parameter.
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = SidekickText.webResearchDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(WtaSpacing.Small))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Spacer(Modifier.height(WtaSpacing.ContentGap))
                Text(
                    text = SidekickText.webResearchServer,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(WtaSpacing.Tiny))
                WtaTextField(
                    value = endpointText,
                    onValueChange = {
                        endpointText = it
                        error = null
                    },
                    placeholder = SidekickText.webResearchServerHint,
                    singleLine = true,
                    isError = error != null,
                    supportingText = error ?: SidekickText.webResearchServerHint,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}
