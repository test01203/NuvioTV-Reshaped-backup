package app.livechannels.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit, onReload: () -> Unit) {
    var engine by remember { mutableStateOf(model.prefs.engineUrl) }
    var hideOffline by remember { mutableStateOf(model.hideOffline) }
    var tmdbKey by remember { mutableStateOf(model.prefs.tmdbKey) }
    var playlistUrl by remember { mutableStateOf(model.prefs.playlistUrl) }

    fun save() {
        model.prefs.tmdbKey = tmdbKey
        var reload = false
        if (playlistUrl.trim() != model.prefs.playlistUrl) {
            model.prefs.playlistUrl = playlistUrl
            reload = true
        }
        if (engine.trim().trimEnd('/') != model.prefs.engineUrl) {
            model.prefs.engineUrl = engine
            reload = true
        }
        if (reload) onReload()
    }

    // Esc goes back, Enter saves; every other key types in the field.
    val keys: (KeyEvent) -> Boolean = keys@{ event ->
        if (event.type != KeyEventType.KeyDown) return@keys false
        when (event.key) {
            Key.Escape -> { onBack(); true }
            Key.Enter, Key.NumPadEnter -> { save(); onBack(); true }
            else -> false
        }
    }
    SideEffect { Keys.handler = keys }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(ScreenPadding).widthIn(max = 820.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Strings["settings_title"], style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)

        // Language
        Text(Strings["language"], style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((code, label) in listOf("he" to "עברית", "en" to "English")) {
                FocusCard(
                    onClick = {
                        Strings.language = code
                        model.prefs.language = code
                    },
                    selected = Strings.language == code,
                    shape = RoundedCornerShape(50),
                ) {
                    Text((if (Strings.language == code) "✓ " else "") + label, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }

        ToggleRow(
            title = Strings["setting_hide_offline"],
            description = Strings["setting_hide_offline_desc"],
            checked = hideOffline,
            onToggle = {
                hideOffline = !hideOffline
                model.prefs.hideOffline = hideOffline
                model.hideOffline = hideOffline
            },
        )

        SettingField(Strings["setting_playlist"], Strings["setting_playlist_desc"], playlistUrl, { playlistUrl = it }, ::save)
        model.playlistStatus?.let { status ->
            val count = status.getOrNull()
            Text(
                when {
                    count == null -> Strings["playlist_failed", status.exceptionOrNull()?.message ?: "?"]
                    count == 0 -> Strings["playlist_empty"]
                    else -> Strings["playlist_loaded", count]
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (count != null && count > 0) Palette.Green else Palette.TextSecondary,
            )
        }
        SettingField(Strings["setting_tmdb"], Strings["setting_tmdb_desc"], tmdbKey, { tmdbKey = it }, ::save)
        SettingField(Strings["setting_engine"], Strings["setting_engine_desc"], engine, { engine = it }, ::save)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            PillButton(Strings["save"], {
                save()
                onBack()
            })
            PillButton(Strings["back"], onBack)
        }
    }
}

@Composable
private fun SettingField(title: String, description: String, value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.padding(top = 8.dp))
    Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.TextPrimary,
            unfocusedTextColor = Palette.TextPrimary,
            focusedContainerColor = Palette.Elevated,
            unfocusedContainerColor = Palette.Elevated,
            focusedBorderColor = Palette.TextPrimary,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = Palette.TextPrimary,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onToggle: () -> Unit) {
    FocusCard(onClick = onToggle, modifier = Modifier.fillMaxWidth()) { highlighted ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (highlighted) Color.Black.copy(alpha = 0.6f) else Palette.TextSecondary)
            }
            Text(
                Strings[if (checked) "on" else "off"],
                style = MaterialTheme.typography.labelMedium,
                color = if (checked) Palette.Green else if (highlighted) Color.Black.copy(alpha = 0.6f) else Palette.TextTertiary,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
