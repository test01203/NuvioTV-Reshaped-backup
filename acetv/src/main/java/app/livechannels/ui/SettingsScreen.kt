package app.livechannels.ui

import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.stringResource
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
    var tmdbKey by remember { mutableStateOf(model.prefs.tmdbKey) }
    var playlistUrl by remember { mutableStateOf(model.prefs.playlistUrl) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(ScreenPadding).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        SettingField(stringResource(R.string.setting_engine), engine, InputType.TYPE_TEXT_VARIATION_URI) {
            engine = it
            if (engine.trim().trimEnd('/') != model.prefs.engineUrl) {
                model.prefs.engineUrl = engine
                onEngineChanged()
            }
        }
        Text(stringResource(R.string.setting_tmdb), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary,
            modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.setting_tmdb_desc), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        SettingField(stringResource(R.string.setting_tmdb), tmdbKey, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
            tmdbKey = it
            model.prefs.tmdbKey = tmdbKey
        }
        Text(stringResource(R.string.setting_playlist), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary,
            modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.setting_playlist_desc), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        SettingField(stringResource(R.string.setting_playlist), playlistUrl, InputType.TYPE_TEXT_VARIATION_URI) {
            playlistUrl = it
            if (playlistUrl.trim() != model.prefs.playlistUrl) {
                model.prefs.playlistUrl = playlistUrl
                onEngineChanged()
            }
        }
        model.playlistStatus?.let { status ->
            val count = status.getOrNull()
            Text(
                when {
                    count == null -> stringResource(R.string.playlist_failed, status.exceptionOrNull()?.message ?: "?")
                    count == 0 -> stringResource(R.string.playlist_empty)
                    else -> stringResource(R.string.playlist_loaded, count)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (count != null && count > 0) Palette.Green else Palette.TextSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            PillButton(stringResource(R.string.back), onBack)
        }
    }
}

/**
 * A setting typed in a system dialog: on a TV, a text field inside the screen often never brings
 * up the keyboard, while a dialog's text box always does (and takes a pasted address too).
 */
@Composable
private fun SettingField(title: String, value: String, inputType: Int, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val saveText = stringResource(R.string.save)
    val cancelText = stringResource(R.string.cancel)
    FocusCard(
        onClick = { askText(context, title, value, inputType, saveText, cancelText, onDone) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            value.ifBlank { stringResource(R.string.field_empty) },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
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

/**
 * Asks for a line of text in a system dialog, which brings up the TV's keyboard (a text field
 * inside the screen often never does).
 */
fun askText(
    context: android.content.Context,
    title: String,
    value: String,
    inputType: Int,
    saveText: String,
    cancelText: String,
    onDone: (String) -> Unit,
) {
    val input = EditText(context).apply {
        setText(value)
        setSelection(text.length)
        isSingleLine = true
        this.inputType = InputType.TYPE_CLASS_TEXT or inputType
        imeOptions = EditorInfo.IME_ACTION_DONE
    }
    val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(title)
        .setView(FrameLayout(context).apply {
            val side = (20 * resources.displayMetrics.density).toInt()
            setPadding(side, side / 2, side, 0)
            addView(input)
        })
        .setPositiveButton(saveText) { _, _ -> onDone(input.text.toString().trim()) }
        .setNegativeButton(cancelText, null)
        .create()
    input.setOnEditorActionListener { _, action, _ ->
        if (action == EditorInfo.IME_ACTION_DONE) {
            onDone(input.text.toString().trim())
            dialog.dismiss()
            true
        } else false
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
    input.requestFocus()
}
