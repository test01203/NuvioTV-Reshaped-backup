package app.livechannels

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.livechannels.ui.AppTheme
import app.livechannels.ui.ChannelsScreen
import app.livechannels.ui.PlayerScreen
import app.livechannels.ui.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        Sessions.persist(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        CrashLog.install(this)
        // Sessions a previous run left open are closed first.
        Sessions.appScope.launch { Sessions.stopAll(applicationContext) }
        val model = AppModel(this)
        val lastCrash = CrashLog.take(this)
        setContent {
            AppTheme {
                var crash by remember { mutableStateOf(lastCrash) }
                val shown = crash
                if (shown != null) CrashScreen(shown) { crash = null } else App(model)
            }
        }
    }
}

sealed interface Screen {
    data object Channels : Screen
    data class Player(val list: List<Channel>, val index: Int) : Screen
    data object Settings : Screen
}

sealed interface LoadState {
    data object Loading : LoadState
    data object Ready : LoadState
    data class Failed(val reason: AceException.Reason) : LoadState
}

/** What the screens share: the catalogue, its loading state and the viewer's choices. */
class AppModel(context: Context) {
    val prefs = Prefs(context)
    var loadState by mutableStateOf<LoadState>(LoadState.Loading)
    var channels by mutableStateOf<List<Channel>>(emptyList())
    var favorites by mutableStateOf(prefs.favorites)
    var hideOffline by mutableStateOf(prefs.hideOffline)
    /** The channel last watched (or zapped to): the list comes back focused on it. */
    var lastChannelKey: String? = null

    val shownChannels: List<Channel>
        get() = if (hideOffline) channels.filter { it.health != Health.Offline } else channels

    suspend fun load(context: Context) {
        loadState = LoadState.Loading
        // The viewer's own list, when set, is listed whether or not the catalogue loads.
        val listed = prefs.playlistUrl.takeIf { it.startsWith("http") || it.startsWith("acestream://", ignoreCase = true) }?.let { url ->
            runCatching { Playlist.load(url) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
        }.orEmpty()
        loadState = try {
            Engine.ensureRunning(context, prefs.engineUrl)
            channels = Catalog.load(prefs.engineUrl) + listed
            LoadState.Ready
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (listed.isNotEmpty()) {
                channels = listed
                LoadState.Ready
            } else {
                LoadState.Failed((error as? AceException)?.reason ?: AceException.Reason.CatalogFailed)
            }
        }
    }

    fun toggleFavorite(channel: Channel) {
        favorites = if (channel.key in favorites) favorites - channel.key else favorites + channel.key
        prefs.favorites = favorites
    }
}

@Composable
private fun App(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Channels) }
    LaunchedEffect(Unit) { model.load(context) }

    when (val current = screen) {
        Screen.Channels -> ChannelsScreen(
            model = model,
            onPlay = { list, index ->
                val channel = list[index]
                if (model.prefs.acePlayer) {
                    scope.launch { Engine.openInAcePlayer(context, model.prefs.engineUrl, channel.infohashes.first()) }
                } else {
                    screen = Screen.Player(list, index)
                }
            },
            onSettings = { screen = Screen.Settings },
            onReload = { scope.launch { model.load(context) } },
        )
        is Screen.Player -> {
            BackHandler { screen = Screen.Channels }
            PlayerScreen(
                model = model,
                list = current.list,
                startIndex = current.index,
                onExit = { screen = Screen.Channels },
            )
        }
        Screen.Settings -> {
            BackHandler { screen = Screen.Channels }
            SettingsScreen(
                model = model,
                onBack = { screen = Screen.Channels },
                onEngineChanged = { scope.launch { model.load(context) } },
            )
        }
    }
}
