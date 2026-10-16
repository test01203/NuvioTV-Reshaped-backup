package app.livechannels.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.livechannels.Channel
import app.livechannels.Sessions
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private sealed interface Screen {
    data object Channels : Screen
    data class Player(val list: List<Channel>, val index: Int) : Screen
    data object Settings : Screen
}

/** Where a crash is written, for the viewer to send. */
private val logFile: File by lazy {
    val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"))
    File(base, "LiveChannels").apply { mkdirs() }.resolve("crash.log")
}

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { logFile.appendText("${LocalDateTime.now()} [${thread.name}]\n${error.stackTraceToString()}\n\n") }
    }
    val model = AppModel()
    Strings.language = model.prefs.language
    // Sessions a killed run left open are closed first.
    val left = model.prefs.openSessions
    model.prefs.openSessions = emptySet()
    Sessions.appScope.launch { Sessions.stopAll(left) }
    val player = runCatching { VlcPlayer() }
        .onFailure { error -> runCatching { logFile.appendText("${LocalDateTime.now()} VLC\n${error.stackTraceToString()}\n\n") } }
        .getOrNull()
    player?.volume = model.prefs.volume

    application {
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 800.dp))
        fun close() {
            player?.stop()
            runBlocking { withTimeoutOrNull(3_000) { Sessions.stopAll() } }
            player?.release()
            exitApplication()
        }
        Window(
            onCloseRequest = ::close,
            state = windowState,
            title = Strings["app_name"],
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
                    windowState.placement =
                        if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Maximized else WindowPlacement.Fullscreen
                    true
                } else {
                    // The screen on show gets every key first (see Keys).
                    Keys.handler?.invoke(event) ?: false
                }
            },
        ) {
            AppTheme {
                App(
                    model = model,
                    player = player,
                    fullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    onToggleFullscreen = {
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Maximized else WindowPlacement.Fullscreen
                    },
                    onLeavePlayer = {
                        if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = WindowPlacement.Maximized
                    },
                )
            }
        }
    }
}

@Composable
private fun App(
    model: AppModel,
    player: VlcPlayer?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onLeavePlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Channels) }
    LaunchedEffect(Unit) { model.load() }
    // The open sessions are recorded as they change, so a killed app still closes them next time.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            model.prefs.openSessions = Sessions.openCommands()
        }
    }

    when (val current = screen) {
        Screen.Channels -> ChannelsScreen(
            model = model,
            onPlay = { list, index -> screen = Screen.Player(list, index) },
            onSettings = { screen = Screen.Settings },
            onReload = { scope.launch { model.load() } },
        )
        is Screen.Player -> if (player == null) {
            SideEffect {
                Keys.handler = { event ->
                    (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) screen = Screen.Channels }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Strings["vlc_missing"], style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary,
                    modifier = Modifier.padding(24.dp))
                PillButton(Strings["back"], { screen = Screen.Channels }, Modifier.align(Alignment.BottomCenter).padding(40.dp))
            }
        } else {
            PlayerScreen(
                model = model,
                player = player,
                list = current.list,
                startIndex = current.index,
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                onExit = {
                    onLeavePlayer()
                    screen = Screen.Channels
                },
            )
        }
        Screen.Settings -> SettingsScreen(
            model = model,
            onBack = { screen = Screen.Channels },
            onReload = { scope.launch { model.load() } },
        )
    }
}
