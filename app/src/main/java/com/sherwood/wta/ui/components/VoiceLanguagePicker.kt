package com.sherwood.wta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sidekick.input.SidekickVoiceLanguage
import com.sherwood.wta.ui.design.WtaAlertDialog

/**
 * The translated name of a dictation language.
 *
 * One function for every surface that shows the list: the settings screen and the agent
 * workspace both call it, so a language can never be labelled one way in settings and
 * another in the composer.
 */
fun voiceLanguageLabel(language: SidekickVoiceLanguage): String = when (language) {
    SidekickVoiceLanguage.AUTO -> Strings.sidekickVoiceLanguageAuto
    SidekickVoiceLanguage.SERBIAN -> Strings.sidekickVoiceLanguageSerbian
    SidekickVoiceLanguage.CROATIAN -> Strings.sidekickVoiceLanguageCroatian
    SidekickVoiceLanguage.BOSNIAN -> Strings.sidekickVoiceLanguageBosnian
    SidekickVoiceLanguage.ENGLISH -> Strings.sidekickVoiceLanguageEnglish
    SidekickVoiceLanguage.GERMAN -> Strings.sidekickVoiceLanguageGerman
    SidekickVoiceLanguage.FRENCH -> Strings.sidekickVoiceLanguageFrench
    SidekickVoiceLanguage.SPANISH -> Strings.sidekickVoiceLanguageSpanish
    SidekickVoiceLanguage.PORTUGUESE -> Strings.sidekickVoiceLanguagePortuguese
    SidekickVoiceLanguage.ITALIAN -> Strings.sidekickVoiceLanguageItalian
    SidekickVoiceLanguage.DUTCH -> Strings.sidekickVoiceLanguageDutch
    SidekickVoiceLanguage.RUSSIAN -> Strings.sidekickVoiceLanguageRussian
    SidekickVoiceLanguage.UKRAINIAN -> Strings.sidekickVoiceLanguageUkrainian
    SidekickVoiceLanguage.POLISH -> Strings.sidekickVoiceLanguagePolish
    SidekickVoiceLanguage.CZECH -> Strings.sidekickVoiceLanguageCzech
    SidekickVoiceLanguage.GREEK -> Strings.sidekickVoiceLanguageGreek
    SidekickVoiceLanguage.TURKISH -> Strings.sidekickVoiceLanguageTurkish
    SidekickVoiceLanguage.ARABIC -> Strings.sidekickVoiceLanguageArabic
    SidekickVoiceLanguage.HINDI -> Strings.sidekickVoiceLanguageHindi
    SidekickVoiceLanguage.CHINESE -> Strings.sidekickVoiceLanguageChinese
    SidekickVoiceLanguage.JAPANESE -> Strings.sidekickVoiceLanguageJapanese
    SidekickVoiceLanguage.KOREAN -> Strings.sidekickVoiceLanguageKorean
    SidekickVoiceLanguage.SWEDISH -> Strings.sidekickVoiceLanguageSwedish
}

/**
 * Picks the dictation language.
 *
 * A fixed list, not the device's supported-language set: a device query would silently
 * hide Serbian on the phones where it matters most, and a missing language has to be
 * reported by the recognizer, not guessed at here. The list scrolls because it now covers
 * far more than the three languages the panel started with.
 */
@Composable
fun VoiceLanguageDialog(
    selected: SidekickVoiceLanguage,
    onSelect: (SidekickVoiceLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    WtaAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Outlined.Mic,
        title = Strings.sidekickVoiceLanguage,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SidekickVoiceLanguage.entries.forEach { language ->
                    VoiceLanguageLabel(
                        label = voiceLanguageLabel(language),
                        selected = selected == language,
                        onClick = { onSelect(language) }
                    )
                }
            }
        }
    )
}

@Composable
private fun VoiceLanguageLabel(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
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
