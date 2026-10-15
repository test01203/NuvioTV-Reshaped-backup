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
import java.net.URLEncoder
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
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "LiveChannels"

const val DEFAULT_ENGINE = "http://127.0.0.1:6878"

// region Models

enum class Health { Active, Weak, Offline, Unknown }

data class Programme(val title: String, val startMs: Long, val stopMs: Long)

/** A channel of the catalogue: its streams (infohashes) best first, and what the catalogue knows of it. */
data class Channel(
    val key: String,
    val name: String,
    val logo: String?,
    val category: String,
    val infohashes: List<String>,
    val health: Health,
    val availability: Float,
    val programmes: List<Programme>,
) {
    fun programmeAt(nowMs: Long): Programme? = programmes.firstOrNull { nowMs in it.startMs until it.stopMs }
    fun programmeAfter(nowMs: Long): Programme? = programmes.firstOrNull { it.startMs > nowMs }
}

class AceException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NotInstalled, EngineFailed, CatalogFailed }
}

// endregion

// region HTTP

internal object Http {
    suspend fun text(url: String, readTimeoutMs: Int = 20_000): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = readTimeoutMs
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(120)}")
            body
        } finally {
            connection.disconnect()
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

private fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

// endregion

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
    suspend fun openInAcePlayer(context: Context, engineUrl: String, infohash: String): Boolean {
        val pkg = installedPackage(context) ?: return false
        val intents = buildList {
            Sessions.open(Sessions.streamUrl(engineUrl, infohash))?.let { session ->
                add(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(session.playbackUrl), "video/*").setPackage(pkg))
            }
            add(
                Intent("org.acestream.action.start_content")
                    .setData(Uri.parse("acestream:?infohash=$infohash"))
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

// region Catalogue

/** The engine's channel search (the catalogue search-ace.stream shows), read whole and grouped by channel. */
object Catalog {
    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 50
    private const val STALE_SECONDS = 7L * 24 * 60 * 60
    private const val FRESH_SECONDS = 6L * 60 * 60

    suspend fun load(engineUrl: String): List<Channel> = withContext(Dispatchers.Default) {
        val channels = ArrayList<Channel>()
        val seen = HashSet<String>()
        for (page in 0 until MAX_PAGES) {
            val url = "${engineUrl.trimEnd('/')}/search?page=$page&page_size=$PAGE_SIZE&query=" +
                "&group_by_channels=1&show_epg=1"
            val body = try {
                Http.text(url, readTimeoutMs = 30_000)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "catalogue page $page failed", error)
                if (page == 0) throw AceException(AceException.Reason.CatalogFailed) else break
            }
            val results = JSONObject(body).optJSONObject("result")?.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val channel = results.optJSONObject(i)?.toChannel() ?: continue
                if (seen.add(channel.infohashes.first())) channels += channel
            }
            if (results.length() < PAGE_SIZE) break
        }
        channels.sortedBy { it.health.ordinal }
    }

    private fun JSONObject.toChannel(): Channel? {
        val now = System.currentTimeMillis() / 1000
        val items = optJSONArray("items")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } } ?: listOf(this)
        val ranked = items
            .filter { it.str("infohash") != null }
            .filter { val seen = it.optLong("availability_updated_at", 0L); seen <= 0L || now - seen < STALE_SECONDS }
            .sortedWith(
                compareByDescending<JSONObject> { it.optInt("status", 0) == 2 }
                    .thenByDescending { it.optDouble("availability", 0.0) }
                    .thenByDescending { it.optLong("availability_updated_at", 0L) },
            )
            .distinctBy { it.str("infohash")!!.lowercase(Locale.ROOT) }
        val best = ranked.firstOrNull() ?: return null
        val name = str("name") ?: best.str("name") ?: return null
        val category = (best.optJSONArray("categories") ?: optJSONArray("categories"))?.optString(0)?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return Channel(
            key = best.str("infohash")!!.lowercase(Locale.ROOT),
            name = name,
            logo = logoOf(this),
            category = category,
            infohashes = ranked.map { it.str("infohash")!!.lowercase(Locale.ROOT) },
            health = healthOf(best, now),
            availability = best.optDouble("availability", -1.0).toFloat(),
            programmes = programmesOf(this),
        )
    }

    private fun logoOf(group: JSONObject): String? {
        val icons = group.optJSONArray("icons")
        if (icons != null) {
            val byType = (0 until icons.length()).mapNotNull { icons.optJSONObject(it) }
                .mapNotNull { icon -> icon.str("url")?.takeIf { it.startsWith("http") }?.let { icon.optInt("type", 0) to it } }
            for (type in intArrayOf(1, 0, 3, 2)) byType.firstOrNull { it.first == type }?.let { return it.second }
        }
        return (group.str("icon") ?: group.str("logo"))?.takeIf { it.startsWith("http") }
    }

    private fun healthOf(item: JSONObject, now: Long): Health {
        val status = item.optInt("status", 0)
        val availability = item.optDouble("availability", -1.0)
        val updatedAt = item.optLong("availability_updated_at", 0L)
        val fresh = updatedAt <= 0L || now - updatedAt < FRESH_SECONDS
        return when {
            !fresh -> Health.Offline
            status == 2 && availability != 0.0 -> if (availability in 0.0..0.5) Health.Weak else Health.Active
            availability > 0.5 -> Health.Active
            availability > 0.0 -> Health.Weak
            availability == 0.0 || status != 0 -> Health.Offline
            else -> Health.Unknown
        }
    }

    private fun programmesOf(group: JSONObject): List<Programme> {
        val epg = group.optJSONArray("epg") ?: return emptyList()
        return (0 until epg.length()).mapNotNull { epg.optJSONObject(it) }.mapNotNull { entry ->
            val start = entry.optLong("start", 0L)
            val stop = entry.optLong("stop", 0L)
            val title = entry.str("name") ?: return@mapNotNull null
            if (start <= 0 || stop <= start) null else Programme(title, start * 1000, stop * 1000)
        }.sortedBy { it.startMs }
    }
}

// endregion

// region Playback sessions

/**
 * Engine playback sessions (`format=json`): the link to play, a statistics link for the loading
 * meter and a command link to stop it.
 */
object Sessions {
    class Session(val playbackUrl: String, val statUrl: String, val commandUrl: String?)

    class Stats(val status: String, val peers: Int, val speedDownKbps: Int, val progress: Float?)

    /** The engine's direct MPEG-TS stream, as Ace Stream's own players use. */
    fun streamUrl(engineUrl: String, infohash: String): String = "${engineUrl.trimEnd('/')}/ace/getstream?infohash=$infohash"

    /** Last answer of the engine, for the small line under the loading meter. */
    @Volatile
    var lastAnswer: String? = null
        private set

    suspend fun open(link: String): Session? = try {
        val json = JSONObject(Http.text(link + (if ('?' in link) "&" else "?") + "format=json"))
        val response = json.optJSONObject("response")
        val playback = response?.str("playback_url")
        val stat = response?.str("stat_url")
        if (playback == null || stat == null) {
            lastAnswer = json.str("error") ?: "no session"
            null
        } else {
            lastAnswer = null
            Session(playback, stat, response.str("command_url"))
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        lastAnswer = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(100)}"
        null
    }

    suspend fun stats(session: Session): Stats? = try {
        val json = JSONObject(Http.text(session.statUrl, readTimeoutMs = 5_000))
        val response = json.optJSONObject("response")
        if (response == null) {
            lastAnswer = json.str("error")
            null
        } else {
            val progress = listOf("progress", "prebuf_progress", "total_progress")
                .firstOrNull { response.has(it) && !response.isNull(it) }
                ?.let { response.optDouble(it).toFloat() }
                ?.let { if (it > 1f) it / 100f else it }
                ?.coerceIn(0f, 1f)
            Stats(response.optString("status").ifBlank { "starting" }, response.optInt("peers", 0), response.optInt("speed_down", 0), progress)
                .also { lastAnswer = "status=${it.status} peers=${it.peers}" }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    suspend fun stop(session: Session?) {
        val command = session?.commandUrl ?: return
        runCatching { Http.text(command + (if ('?' in command) "&" else "?") + "method=stop", readTimeoutMs = 3_000) }
            .onFailure { if (it is CancellationException) throw it }
    }
}

// endregion

// region Preferences

class Prefs(context: Context) {
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

    var lastCategory: String?
        get() = prefs.getString("last_category", null)
        set(value) = prefs.edit().putString("last_category", value).apply()
}

// endregion
