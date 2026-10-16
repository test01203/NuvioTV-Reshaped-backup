package app.livechannels

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// The engine's catalogue, playback sessions and the models: shared by the TV app and the PC app.

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
        var address = url
        // Redirects followed by hand, so short links that move between http and https work too.
        repeat(5) {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LiveChannels")
            try {
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    address = URL(URL(address), location).toString()
                    return@repeat
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(120)}")
                return@withContext body
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

internal fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

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
    fun streamUrl(engineUrl: String, stream: String): String {
        // A player id of its own for every opening, so the engine never mixes this session up
        // with the previous channel's while that one is still closing.
        val pid = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val base = engineUrl.trimEnd('/')
        return when {
            stream.startsWith("url:") -> stream.removePrefix("url:")
            stream.startsWith("id:") -> "$base/ace/getstream?id=${stream.removePrefix("id:")}&pid=$pid"
            else -> "$base/ace/getstream?infohash=$stream&pid=$pid"
        }
    }

    /** Whether [link] is served by the engine (and so opens as an engine session). */
    fun isEngineLink(link: String): Boolean = "/ace/getstream" in link

    /** Every session this app opened and has not closed yet. */
    private val active = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** Work that must finish even after the screen that started it is gone (closing sessions). */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Closes every session still open (this run's, and [left] ones a previous run recorded). */
    suspend fun stopAll(left: Collection<String> = emptyList()) {
        val commands = synchronized(active) { active.toList().also { active.clear() } }.toMutableSet()
        commands += left
        commands.forEach { stopCommand(it) }
    }

    /** The sessions open now, for recording them in case the app is killed. */
    fun openCommands(): Set<String> = synchronized(active) { active.toSet() }

    private suspend fun stopCommand(command: String) {
        runCatching { Http.text(command + (if ('?' in command) "&" else "?") + "method=stop", readTimeoutMs = 3_000) }
            .onFailure { if (it is CancellationException) throw it }
    }

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
            // Without a command link, the documented one is derived from the playback link
            // (…/ace/r/<hash>/<session> → …/ace/cmd/<hash>/<session>), so the session can still be closed.
            val command = response.str("command_url")
                ?: Regex("""/ace/(r|m|l)/""").find(playback)?.let { playback.replaceRange(it.range, "/ace/cmd/").substringBefore('?').removeSuffix(".m3u8") }
            Session(playback, stat, command).also { session -> session.commandUrl?.let(active::add) }
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
        active.remove(command)
        stopCommand(command)
    }
}

// endregion

