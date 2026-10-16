package app.livechannels

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.acestream.engine.service.v0.IAceStreamEngine
import org.acestream.engine.service.v0.IAceStreamEngineCallback
import org.acestream.engine.service.v0.IStartEngineResponse

private const val TAG = "LiveChannels"

// region Engine

/**
 * Starts the Ace Stream engine of the installed Ace Stream app through its public service (the
 * interface Ace Stream's own client library binds to), so channels play without opening that app.
 */
object Engine {
    private const val SERVICE_ACTION = "org.acestream.engine.service.v0.IAceStreamEngine"
    private val PACKAGES = listOf("org.acestream.core.atv", "org.acestream.core", "org.acestream.media.atv", "org.acestream.media")

    private val lock = Mutex()
    private var connection: ServiceConnection? = null

    fun isLocal(engineUrl: String): Boolean {
        val host = engineUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase(Locale.ROOT)
        return host == "127.0.0.1" || host == "localhost"
    }

    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        val candidates = LinkedHashSet(PACKAGES)
        runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(Intent(SERVICE_ACTION), PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.serviceInfo?.packageName }
                .forEach(candidates::add)
        }
        return candidates.mapNotNull { pkg -> versionCode(pm, pkg)?.let { pkg to it } }.maxByOrNull { it.second }?.first
    }

    private fun versionCode(pm: PackageManager, pkg: String): Long? = try {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Makes sure the engine answers at [engineUrl], starting the one on this device when needed. */
    suspend fun ensureRunning(context: Context, engineUrl: String) {
        if (answers(engineUrl)) return
        if (!isLocal(engineUrl)) throw AceException(AceException.Reason.EngineFailed)
        lock.withLock {
            if (answers(engineUrl)) return
            val pkg = installedPackage(context) ?: throw AceException(AceException.Reason.NotInstalled)
            val started = withTimeoutOrNull(60_000) { start(context.applicationContext, pkg) } ?: false
            if (!started || !waitUntilAnswers(engineUrl)) throw AceException(AceException.Reason.EngineFailed)
        }
    }

    private suspend fun start(context: Context, pkg: String): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val callback = object : IAceStreamEngineCallback.Stub() {
            override fun onUnpacking() {}
            override fun onStarting() {}
            override fun onReady(listenPort: Int) {
                ready.complete(listenPort != -1)
            }
            override fun onStopped() {
                ready.complete(false)
            }
            override fun onWaitForNetworkConnection() {}
            override fun onPlaylistUpdated() {}
            override fun onEPGUpdated() {}
            override fun onRestartPlayer() {}
            override fun onSettingsUpdated() {}
            override fun onAuthUpdated() {}
        }
        val response = object : IStartEngineResponse.Stub() {
            override fun onResult(success: Boolean) {
                ready.complete(success)
            }
        }
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IAceStreamEngine.Stub.asInterface(binder)
                runCatching {
                    service.registerCallbackExt(callback, true)
                    service.startEngineWithCallback(response)
                }.onFailure {
                    Log.w(TAG, "engine service call failed", it)
                    ready.complete(false)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        connection?.let { old -> runCatching { context.unbindService(old) } }
        connection = null
        val bound = runCatching {
            context.bindService(Intent(SERVICE_ACTION).setPackage(pkg), serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) return false
        connection = serviceConnection
        return ready.await()
    }

    private suspend fun waitUntilAnswers(engineUrl: String): Boolean {
        repeat(20) {
            if (answers(engineUrl)) return true
            delay(500)
        }
        return false
    }

    private suspend fun answers(engineUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val http = URL("${engineUrl.trimEnd('/')}/webui/api/service?method=get_version").openConnection() as HttpURLConnection
            http.connectTimeout = 1500
            http.readTimeout = 3000
            try {
                http.responseCode in 200..499
            } finally {
                http.disconnect()
            }
        }.getOrDefault(false)
    }

    /** Opens [infohash] in Ace Stream's own player (Ace Player). False when no Ace Stream app took it. */
    suspend fun openInAcePlayer(context: Context, engineUrl: String, stream: String): Boolean {
        if (stream.startsWith("url:")) return false
        val infohash = stream.removePrefix("id:")
        val contentParam = if (stream.startsWith("id:")) "content_id" else "infohash"
        val pkg = installedPackage(context) ?: return false
        val intents = buildList {
            Sessions.open(Sessions.streamUrl(engineUrl, stream))?.let { session ->
                add(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(session.playbackUrl), "video/*").setPackage(pkg))
            }
            add(
                Intent("org.acestream.action.start_content")
                    .setData(Uri.parse("acestream:?$contentParam=$infohash"))
                    .putExtra("org.acestream.EXTRA_SELECTED_PLAYER", "{\"type\": 3}"),
            )
        }
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    fun installIntent(): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.acestream.core.atv")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=org.acestream.core.atv")),
    ).onEach { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}

// endregion

// region Sessions on Android

private const val ACTIVE_PREFS = "live_channels_sessions"

/**
 * Closes every session still open, this run's and any a previous run left behind (kept in
 * [context]'s preferences), so the engine is never left streaming a channel nobody watches.
 */
suspend fun Sessions.stopAll(context: Context) {
    val prefs = context.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE)
    val left = prefs.getStringSet("open", emptySet()).orEmpty()
    prefs.edit().remove("open").apply()
    stopAll(left)
}

/** Remembers the open sessions on disk, so a crash or a killed app does not leave them streaming. */
fun Sessions.persist(context: Context) {
    context.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE).edit().putStringSet("open", openCommands()).apply()
}

/**
 * Clears this app's cache after a channel: temporary files go, the logo cache stays (the
 * list would otherwise load every logo again). The Ace Stream engine's own cache belongs to
 * its app, which Android does not let other apps clear.
 */
fun Sessions.clearCache(context: Context) {
    runCatching {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name != "image_cache") file.deleteRecursively()
        }
        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
    }
}

// endregion

// region Preferences

class Prefs(context: Context) : SubtitleSettings {
    private val prefs = context.applicationContext.getSharedPreferences("live_channels", Context.MODE_PRIVATE)

    var engineUrl: String
        get() = prefs.getString("engine", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENGINE
        set(value) {
            val clean = value.trim().trimEnd('/').ifBlank { DEFAULT_ENGINE }
            prefs.edit().putString("engine", if (clean.contains("://")) clean else "http://$clean").apply()
        }

    var acePlayer: Boolean
        get() = prefs.getBoolean("ace_player", false)
        set(value) = prefs.edit().putBoolean("ace_player", value).apply()

    var hideOffline: Boolean
        get() = prefs.getBoolean("hide_offline", true)
        set(value) = prefs.edit().putBoolean("hide_offline", value).apply()

    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("favorites", value).apply()

    /** The viewer's own TMDB key, for matching the guide's titles to films (subtitles). Kept on the device only. */
    override var tmdbKey: String
        get() = prefs.getString("tmdb_key", null).orEmpty()
        set(value) = prefs.edit().putString("tmdb_key", value.trim()).apply()

    /** Subtitles were chosen: the next programmes get them too. */
    override var autoSubtitles: Boolean
        get() = prefs.getBoolean("auto_subtitles", false)
        set(value) = prefs.edit().putBoolean("auto_subtitles", value).apply()

    /** A channel list (M3U) the viewer added by its address; empty for none. */
    var playlistUrl: String
        get() = prefs.getString("playlist_url", null).orEmpty()
        set(value) = prefs.edit().putString("playlist_url", value.trim()).apply()

    var lastCategory: String?
        get() = prefs.getString("last_category", null)
        set(value) = prefs.edit().putString("last_category", value).apply()
}

// endregion
