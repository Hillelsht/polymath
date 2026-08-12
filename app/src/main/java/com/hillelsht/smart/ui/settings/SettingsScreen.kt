package com.hillelsht.smart.ui.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hillelsht.smart.R
import com.hillelsht.smart.domain.model.Language
import com.hillelsht.smart.ui.components.SmartCard
import com.hillelsht.smart.util.LocalePrefs

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(LocalePrefs.get(context)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.settings_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.settings_language_heading),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.settings_language_blurb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Language.entries.forEach { language ->
                    LanguageRow(
                        language = language,
                        selected = language == selected,
                        onClick = {
                            if (language != selected) {
                                selected = language
                                LocalePrefs.set(context, language)
                                // Reapplies attachBaseContext with the new locale — the
                                // simplest way to make every stringResource() call in the
                                // tree re-resolve, since this app has no AppCompatDelegate.
                                (context as? Activity)?.recreate()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(language: Language, selected: Boolean, onClick: () -> Unit) {
    SmartCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The language's own name, in its own script — never translated, the same way a
            // language picker never asks you to read "English" in Russian to find English.
            Text(
                language.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
