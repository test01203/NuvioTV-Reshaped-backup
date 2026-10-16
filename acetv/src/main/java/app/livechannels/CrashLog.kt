package app.livechannels

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.livechannels.ui.Palette
import app.livechannels.ui.PillButton

/**
 * Keeps the last crash so the next start can show it: on a TV there is no other way to see why
 * the app closed without a computer attached.
 */
object CrashLog {
    private const val PREFS = "live_channels_crash"
    private const val KEY = "last"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTraceToString().lines().take(30).joinToString("\n")
                val text = "Live Channels ${BuildInfo.version(app)} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n$trace"
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, once: it is cleared as it is read. */
    fun take(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, null) ?: return null
        prefs.edit().remove(KEY).apply()
        return text
    }
}

private object BuildInfo {
    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
}

@Composable
fun CrashScreen(text: String, onContinue: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("The app closed unexpectedly last time. A photo of this screen helps fix it.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        PillButton("OK", onContinue, Modifier.focusRequester(focus))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
    }
}
