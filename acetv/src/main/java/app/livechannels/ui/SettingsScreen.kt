package app.livechannels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.livechannels.AppModel
import app.livechannels.R

@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit, onEngineChanged: () -> Unit) {
    var engine by remember { mutableStateOf(model.prefs.engineUrl) }
    var acePlayer by remember { mutableStateOf(model.prefs.acePlayer) }
    var hideOffline by remember { mutableStateOf(model.hideOffline) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().then(ScreenPadding).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)

        ToggleRow(
            title = stringResource(R.string.setting_ace_player),
            description = stringResource(R.string.setting_ace_player_desc),
            checked = acePlayer,
            onToggle = {
                acePlayer = !acePlayer
                model.prefs.acePlayer = acePlayer
            },
            modifier = Modifier.focusRequester(firstFocus),
        )
        ToggleRow(
            title = stringResource(R.string.setting_hide_offline),
            description = stringResource(R.string.setting_hide_offline_desc),
            checked = hideOffline,
            onToggle = {
                hideOffline = !hideOffline
                model.prefs.hideOffline = hideOffline
                model.hideOffline = hideOffline
            },
        )

        Text(stringResource(R.string.setting_engine), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary,
            modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.setting_engine_desc), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        var fieldFocused by remember { mutableStateOf(false) }
        BasicTextField(
            value = engine,
            onValueChange = { engine = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary),
            cursorBrush = SolidColor(Palette.TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                model.prefs.engineUrl = engine
                onEngineChanged()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fieldFocused = it.isFocused }
                .background(Palette.Elevated, RoundedCornerShape(12.dp))
                .border(2.dp, if (fieldFocused) Palette.TextPrimary else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            PillButton(stringResource(R.string.save), {
                if (engine.trim().trimEnd('/') != model.prefs.engineUrl) {
                    model.prefs.engineUrl = engine
                    onEngineChanged()
                }
                onBack()
            })
            PillButton(stringResource(R.string.back), onBack)
        }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    FocusCard(onClick = onToggle, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (focused) Color.Black.copy(alpha = 0.6f) else Palette.TextSecondary)
            }
            Text(
                stringResource(if (checked) R.string.on else R.string.off),
                style = MaterialTheme.typography.labelMedium,
                color = if (checked) Palette.Green else if (focused) Color.Black.copy(alpha = 0.6f) else Palette.TextTertiary,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
