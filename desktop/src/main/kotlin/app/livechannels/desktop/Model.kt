package app.livechannels.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.livechannels.AceException
import app.livechannels.Catalog
import app.livechannels.Channel
import app.livechannels.DEFAULT_ENGINE
import app.livechannels.Health
import app.livechannels.Http
import app.livechannels.Playlist
import app.livechannels.SubtitleSettings
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The viewer's settings, kept in the Windows registry under this user (Java preferences). */
class Prefs : SubtitleSettings {
    private val node = Preferences.userRoot().node("app/livechannels")

    var engineUrl: String
        get() = node.get("engine", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENGINE
        set(value) {
            val clean = value.trim().trimEnd('/').ifBlank { DEFAULT_ENGINE }
            node.put("engine", if (clean.contains("://")) clean else "http://$clean")
        }

    var hideOffline: Boolean
        get() = node.getBoolean("hide_offline", true)
        set(value) = node.putBoolean("hide_offline", value)

    var favorites: Set<String>
        get() = node.get("favorites", "").split('\n').filter { it.isNotBlank() }.toSet()
        set(value) = node.put("favorites", value.joinToString("\n"))

    override var tmdbKey: String
        get() = node.get("tmdb_key", "")
        set(value) = node.put("tmdb_key", value.trim())

    override var autoSubtitles: Boolean
        get() = node.getBoolean("auto_subtitles", false)
        set(value) = node.putBoolean("auto_subtitles", value)

    var playlistUrl: String
        get() = node.get("playlist_url", "")
        set(value) = node.put("playlist_url", value.trim())

    var lastCategory: String?
        get() = node.get("last_category", null)
        set(value) = if (value == null) node.remove("last_category") else node.put("last_category", value)

    var language: String
        get() = node.get("language", "he")
        set(value) = node.put("language", value)

    var volume: Int
        get() = node.getInt("volume", 100)
        set(value) = node.putInt("volume", value)

    /** Engine sessions open when the app last ran, closed on the next start if it was killed. */
    var openSessions: Set<String>
        get() = node.get("open_sessions", "").split('\n').filter { it.isNotBlank() }.toSet()
        set(value) = node.put("open_sessions", value.joinToString("\n"))
}

/**
 * The Ace Stream engine on this computer: Ace Stream for Windows installs `ace_engine.exe`,
 * which answers on port 6878 once running. The app starts it when it is not running yet.
 */
object WindowsEngine {
    const val DOWNLOAD_PAGE = "https://www.acestream.org/"

    fun isLocal(engineUrl: String): Boolean {
        val host = engineUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase(Locale.ROOT)
        return host == "127.0.0.1" || host == "localhost"
    }

    private suspend fun answers(engineUrl: String): Boolean = try {
        Http.text("${engineUrl.trimEnd('/')}/webui/api/service?method=get_version", readTimeoutMs = 3_000)
        true
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        false
    }

    /** Where Ace Stream put its engine: the path it records in the registry, or its usual place. */
    fun enginePath(): File? {
        val fromRegistry = runCatching {
            val process = ProcessBuilder("reg", "query", "HKCU\\Software\\AceStream", "/v", "EnginePath").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            Regex("""EnginePath\s+REG_\w+\s+(.+)""").find(output)?.groupValues?.get(1)?.trim()
        }.getOrNull()
        val appData = System.getenv("APPDATA")
        return listOfNotNull(
            fromRegistry,
            appData?.let { "$it\\ACEStream\\engine\\ace_engine.exe" },
            System.getenv("LOCALAPPDATA")?.let { "$it\\ACEStream\\engine\\ace_engine.exe" },
        ).map(::File).firstOrNull { it.isFile }
    }

    suspend fun ensureRunning(engineUrl: String) {
        if (answers(engineUrl)) return
        if (!isLocal(engineUrl)) throw AceException(AceException.Reason.EngineFailed)
        val path = enginePath() ?: throw AceException(AceException.Reason.NotInstalled)
        runCatching { ProcessBuilder(path.absolutePath).directory(path.parentFile).start() }
            .onFailure { throw AceException(AceException.Reason.EngineFailed) }
        repeat(80) {
            delay(500)
            if (answers(engineUrl)) return
        }
        throw AceException(AceException.Reason.EngineFailed)
    }
}

sealed interface LoadState {
    data object Loading : LoadState
    data object Ready : LoadState
    data class Failed(val reason: AceException.Reason) : LoadState
}

/** What the screens share: the catalogue, its loading state and the viewer's choices. */
class AppModel {
    val prefs = Prefs()
    var loadState by mutableStateOf<LoadState>(LoadState.Loading)
    var channels by mutableStateOf<List<Channel>>(emptyList())
    var favorites by mutableStateOf(prefs.favorites)
    var hideOffline by mutableStateOf(prefs.hideOffline)
    /** How the viewer's own list last loaded: channel count, or why it failed. */
    var playlistStatus by mutableStateOf<Result<Int>?>(null)
    /** The channel last watched: the list comes back to it. */
    var lastChannelKey: String? = null

    val shownChannels: List<Channel>
        get() = if (hideOffline) channels.filter { it.health != Health.Offline } else channels

    suspend fun load() {
        loadState = LoadState.Loading
        val address = Playlist.address(prefs.playlistUrl)
        val listed = if (address == null) {
            playlistStatus = null
            emptyList()
        } else {
            try {
                Playlist.load(address).also { playlistStatus = Result.success(it.size) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                playlistStatus = Result.failure(error)
                emptyList()
            }
        }
        loadState = try {
            WindowsEngine.ensureRunning(prefs.engineUrl)
            val catalogue = Catalog.load(prefs.engineUrl)
            channels = (Playlist.enrich(listed, catalogue) + catalogue).distinctBy { it.key }
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
