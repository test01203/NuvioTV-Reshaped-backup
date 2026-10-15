package com.nuvio.tv.reshaped.livetv

import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/*
 * Ace Stream channels. The Ace Stream engine (the Ace Stream Engine / Ace Stream Media app on the
 * same box, or one on the network) indexes the same live channel catalogue search-ace.stream shows,
 * and serves it at /search. Channels are P2P (torrent) streams the engine turns into plain HTTP,
 * so the player opens them like any IPTV link.
 */

/** The engine's default address on the device it runs on. */
const val ACE_STREAM_DEFAULT_ENGINE = "http://127.0.0.1:6878"

internal fun LiveTvAceStreamSettings.normalized(): LiveTvAceStreamSettings {
    val engine = engineUrl.trim().trimEnd('/').substringBefore("/search").substringBefore("/ace/").trimEnd('/')
        .ifBlank { ACE_STREAM_DEFAULT_ENGINE }
    return copy(
        engineUrl = if (engine.contains("://")) engine else "http://$engine",
        query = query.trim(),
        category = category.trim().lowercase(Locale.ROOT),
    )
}

/** An Ace Stream content id or infohash: 40 hex characters. */
private val aceIdRegex = Regex("^[0-9a-fA-F]{40}$")

/**
 * The content id of an Ace Stream link: `acestream://<id>`, a bare id, or an engine link that
 * carries `id=`. Null for anything else.
 */
internal fun String.aceStreamContentId(): String? {
    val value = trim()
    val id = when {
        value.startsWith("acestream://", ignoreCase = true) -> value.substring("acestream://".length)
        value.startsWith("ace://", ignoreCase = true) -> value.substring("ace://".length)
        else -> value
    }.substringBefore('?').substringBefore('#').trim('/').trim()
    return id.takeIf(aceIdRegex::matches)?.lowercase(Locale.ROOT)
}

/** Whether a playlist entry or pasted link is an Ace Stream link rather than an HTTP one. */
internal fun String.isAceStreamLink(): Boolean =
    trim().let { it.startsWith("acestream://", ignoreCase = true) || it.startsWith("ace://", ignoreCase = true) } ||
        aceIdRegex.matches(trim())

/** The engine a channel plays through, when it is an Ace Stream channel. */
internal fun LiveTvChannel.aceEngineUrl(): String? {
    val marker = streamUrl.indexOf("/ace/")
    return if (marker > 0 && (streamUrl.contains("?id=") || streamUrl.contains("?infohash="))) streamUrl.substring(0, marker) else null
}

internal object LiveTvAceStream {
    private const val TAG = "LiveTvAceStream"
    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 50
    /** Other streams of a channel tried, in order, when the best finds no peers. */
    private const val MAX_ALTERNATES = 4
    /** How long a stream gets to find peers before the next one is tried. */
    private const val PEER_WAIT_MS = 12_000L
    /** How long the engine gets to buffer before the player opens the stream anyway. */
    private const val STREAM_WAIT_MS = 25_000L
    /** A stream nobody saw alive for this long counts as off air. */
    private const val HEALTH_FRESH_SECONDS = 6L * 60 * 60

    /** The engine link that plays content [id]; HLS, which the engine segments itself. */
    fun contentUrl(engineUrl: String, id: String): String =
        "${engineUrl.trimEnd('/')}/ace/manifest.m3u8?id=$id"

    private fun infohashUrl(engineUrl: String, infohash: String): String =
        "${engineUrl.trimEnd('/')}/ace/manifest.m3u8?infohash=$infohash"

    /** A pasted Ace Stream link as one channel, played through the engine on this device. */
    fun singleChannel(link: String, engineUrl: String = ACE_STREAM_DEFAULT_ENGINE): LiveTvChannel? {
        val id = link.aceStreamContentId() ?: return null
        return LiveTvChannel(
            id = "ace-$id",
            name = "Ace Stream ${id.take(8)}",
            streamUrl = contentUrl(engineUrl, id),
        )
    }

    /**
     * Every channel the engine's search lists for [settings] (all of them when the query is blank),
     * one per channel with its best-seeded stream.
     */
    suspend fun channels(settings: LiveTvAceStreamSettings): List<LiveTvChannel> {
        val channels = ArrayList<LiveTvChannel>()
        val seen = HashSet<String>()
        forEachResult(settings) { group ->
            group.toChannel(settings, channels.size)?.takeIf { seen.add(it.streamUrl) }?.let(channels::add)
        }
        return channels
    }

    private suspend fun forEachResult(settings: LiveTvAceStreamSettings, block: (JSONObject) -> Unit) {
        for (page in 0 until MAX_PAGES) {
            val results = searchPage(settings, page)
            if (results.length() == 0) break
            for (i in 0 until results.length()) results.optJSONObject(i)?.let(block)
            if (results.length() < PAGE_SIZE) break
        }
    }

    // region Playback sessions

    /** What the engine reports while a channel loads and plays. */
    class SessionStats(
        /** "prebuf" while it buffers before playback, "dl" once it streams, "starting"/"wait" before. */
        val status: String,
        val peers: Int,
        val speedDownKbps: Int,
        /** Buffering progress 0..1 when the engine says, null when it does not. */
        val progress: Float?,
    )

    class Session(val playbackUrl: String, val statUrl: String, val startedAtMs: Long)

    private val sessionState = kotlinx.coroutines.flow.MutableStateFlow<Session?>(null)
    private val connectingState = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** The session of the Ace Stream channel playing now, for the loading meter. */
    val session: kotlinx.coroutines.flow.StateFlow<Session?> get() = sessionState

    /** Whether a channel is being opened (peers looked for) before the player starts. */
    val connecting: kotlinx.coroutines.flow.StateFlow<Boolean> get() = connectingState

    private var currentSession: Session?
        get() = sessionState.value
        set(value) {
            sessionState.value = value
        }

    /**
     * Opens a playback session (`format=json`): the engine answers with the link to play, a
     * statistics link the loading meter reads, and a command link. Each of [engineLinks] (the
     * channel's streams, best first) gets [PEER_WAIT_MS] to find peers; one that finds none is
     * stopped and the next is tried. When none does, the one that came closest plays anyway, and
     * when the engine does not do sessions at all, the first link plays as listed.
     */
    suspend fun startSession(engineLinks: List<String>): String {
        connectingState.value = true
        return try {
            openBestSession(engineLinks)
        } finally {
            connectingState.value = false
        }
    }

    private suspend fun openBestSession(engineLinks: List<String>): String {
        currentSession = null
        var fallback: Pair<Session, Int>? = null
        for ((index, link) in engineLinks.withIndex()) {
            val opened = openSession(link) ?: return if (index == 0) link else (fallback?.first?.playbackUrl ?: engineLinks.first())
            val (session, commandUrl) = opened
            currentSession = session
            val peers = waitForPeers(session)
            if (peers > 0) {
                // Handing the link over before the engine streams gets "500" answers.
                waitUntilStreaming(session)
                return session.playbackUrl
            }
            if (index == engineLinks.lastIndex) return session.playbackUrl
            Log.i(TAG, "Stream ${index + 1} of ${engineLinks.size} found no peers; trying the next")
            if (fallback == null || peers > fallback.second) fallback = session to peers
            commandUrl?.let { stopQuietly(it) }
        }
        return fallback?.first?.playbackUrl ?: engineLinks.first()
    }

    private suspend fun openSession(link: String): Pair<Session, String?>? {
        val url = link + (if ('?' in link) "&" else "?") + "format=json"
        return try {
            val response = JSONObject(LiveTvHttp.text(url, emptyMap())).optJSONObject("response") ?: return null
            val playback = response.optNonBlank("playback_url") ?: return null
            val stat = response.optNonBlank("stat_url") ?: return null
            Session(playback, stat, System.currentTimeMillis()) to response.optNonBlank("command_url")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Engine session failed for $link", error)
            null
        }
    }

    /** Peers the session found within [PEER_WAIT_MS] (or as soon as it streams). */
    private suspend fun waitForPeers(session: Session): Int {
        var peers = 0
        val deadline = System.currentTimeMillis() + PEER_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val stats = stats(session)
            if (stats != null) {
                peers = maxOf(peers, stats.peers)
                if (stats.peers > 0 || stats.status == "dl") return maxOf(peers, 1)
            }
            kotlinx.coroutines.delay(1000)
        }
        return peers
    }

    /** Waits (at most [STREAM_WAIT_MS]) until the engine has buffered enough to serve the stream. */
    private suspend fun waitUntilStreaming(session: Session) {
        val deadline = System.currentTimeMillis() + STREAM_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (stats(session)?.status == "dl") return
            kotlinx.coroutines.delay(1000)
        }
    }

    private suspend fun stopQuietly(commandUrl: String) {
        runCatching { LiveTvHttp.text(commandUrl + (if ('?' in commandUrl) "&" else "?") + "method=stop", emptyMap()) }
            .onFailure { if (it is CancellationException) throw it }
    }

    suspend fun stats(session: Session): SessionStats? = try {
        val json = JSONObject(LiveTvHttp.text(session.statUrl, emptyMap()))
        val response = json.optJSONObject("response")
        if (response == null) null else {
            val progress = listOf("progress", "prebuf_progress", "total_progress")
                .firstOrNull { response.has(it) && !response.isNull(it) }
                ?.let { response.optDouble(it).toFloat() }
                ?.let { if (it > 1f) it / 100f else it }
                ?.coerceIn(0f, 1f)
            SessionStats(
                status = response.optString("status").ifBlank { "starting" },
                peers = response.optInt("peers", 0),
                speedDownKbps = response.optInt("speed_down", 0),
                progress = progress,
            )
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    // endregion

    // region Guide

    private const val GUIDE_SCHEME = "acestream-guide:"

    /**
     * The guide link of an Ace Stream source: not a download, but the engine's search read again
     * with its programme data and written out as XMLTV, so the guide works like any other.
     */
    fun guideUrl(settings: LiveTvAceStreamSettings): String =
        GUIDE_SCHEME + listOf(settings.engineUrl, settings.query, settings.category).joinToString("|") { it.urlEncoded() }

    fun isGuideUrl(url: String): Boolean = url.startsWith(GUIDE_SCHEME)

    private fun settingsFromGuideUrl(url: String): LiveTvAceStreamSettings {
        val parts = url.removePrefix(GUIDE_SCHEME).split('|').map { java.net.URLDecoder.decode(it, "UTF-8") }
        return LiveTvAceStreamSettings(parts.getOrElse(0) { ACE_STREAM_DEFAULT_ENGINE }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    /** Writes the programmes the engine knows for the source behind [guideUrl] to [target] as XMLTV. */
    suspend fun writeGuide(guideUrl: String, target: java.io.File) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeGuideFile(guideUrl, target) }

    private suspend fun writeGuideFile(guideUrl: String, target: java.io.File) {
        val settings = settingsFromGuideUrl(guideUrl)
        val temp = java.io.File(target.parentFile, target.name + ".part")
        var programmes = 0
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { target.parentFile?.mkdirs() }
        val writer = java.io.OutputStreamWriter(java.util.zip.GZIPOutputStream(temp.outputStream().buffered()), Charsets.UTF_8)
        try {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            forEachResult(settings) { group ->
                val name = group.optNonBlank("name") ?: return@forEachResult
                val epg = group.optJSONArray("epg") ?: return@forEachResult
                if (epg.length() == 0) return@forEachResult
                val channelId = guideChannelId(group, name)
                writer.write("<channel id=\"${channelId.xml()}\"><display-name>${name.trim().xml()}</display-name>")
                logoOf(group)?.let { writer.write("<icon src=\"${it.xml()}\"/>") }
                writer.write("</channel>\n")
                for (i in 0 until epg.length()) {
                    val entry = epg.optJSONObject(i) ?: continue
                    val start = entry.optLong("start", 0L)
                    val stop = entry.optLong("stop", 0L)
                    val title = entry.optNonBlank("name") ?: continue
                    if (start <= 0L || stop <= start) continue
                    writer.write(
                        "<programme start=\"${xmlTvTime(start)}\" stop=\"${xmlTvTime(stop)}\" channel=\"${channelId.xml()}\">" +
                            "<title>${title.xml()}</title></programme>\n",
                    )
                    programmes++
                }
            }
            writer.write("</tv>\n")
        } finally {
            writer.close()
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (programmes == 0) {
                temp.delete()
                throw IllegalStateException("the engine gave no programmes")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun xmlTvTime(epochSeconds: Long): String =
        java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(epochSeconds * 1000L)) + " +0000"

    private fun String.xml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // endregion

    private suspend fun searchPage(settings: LiveTvAceStreamSettings, page: Int): JSONArray {
        val url = buildString {
            append(settings.engineUrl).append("/search?page=").append(page)
            append("&page_size=").append(PAGE_SIZE)
            append("&query=").append(settings.query.urlEncoded())
            if (settings.category.isNotBlank()) append("&category=").append(settings.category.urlEncoded())
            append("&group_by_channels=1&show_epg=1")
        }
        val body = try {
            LiveTvHttp.text(url, emptyMap())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Engine search failed at ${settings.engineUrl}", error)
            // Only the first page tells the engine is missing; a later failure keeps what was read.
            if (page == 0) throw LiveTvException(LiveTvError.AceStreamEngine) else return JSONArray()
        }
        val json = JSONObject(body)
        json.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let {
            Log.w(TAG, "Engine search error: $it")
            if (page == 0) throw LiveTvException(LiveTvError.AceStreamEngine) else return JSONArray()
        }
        return json.optJSONObject("result")?.optJSONArray("results") ?: JSONArray()
    }

    /**
     * A search result: `{name, icon, epg, items:[{infohash, name, categories, availability,
     * availability_updated_at, channel_id, status}]}`, or a single item when the engine does not group.
     */
    private fun JSONObject.toChannel(settings: LiveTvAceStreamSettings, index: Int): LiveTvChannel? {
        val items = optJSONArray("items")?.let { array -> List(array.length()) { array.optJSONObject(it) }.filterNotNull() }
            ?: listOf(this)
        // Best first: on air, most peers, most recently seen alive.
        val ranked = items
            .filter { it.optString("infohash").isNotBlank() }
            .sortedWith(
                compareByDescending<JSONObject> { it.optInt("status", 0) == 2 }
                    .thenByDescending { it.optDouble("availability", 0.0) }
                    .thenByDescending { it.optLong("availability_updated_at", 0L) },
            )
            .distinctBy { it.optString("infohash").trim().lowercase(Locale.ROOT) }
        val best = ranked.firstOrNull() ?: return null
        val infohash = best.optString("infohash").trim().lowercase(Locale.ROOT)
        val name = optNonBlank("name") ?: best.optNonBlank("name") ?: return null
        val categories = best.optJSONArray("categories") ?: optJSONArray("categories")
        val category = categories?.optString(0)?.trim().orEmpty()
        return LiveTvChannel(
            id = "ace-$index-$infohash",
            name = name.trim(),
            streamUrl = infohashUrl(settings.engineUrl, infohash),
            tvgId = guideChannelId(this, name),
            logoUrl = logoOf(this),
            health = healthOf(best),
            alternateUrls = ranked.drop(1).take(MAX_ALTERNATES)
                .map { infohashUrl(settings.engineUrl, it.optString("infohash").trim().lowercase(Locale.ROOT)) },
            streamCount = ranked.size,
            availability = best.optDouble("availability", -1.0).toFloat(),
            group = category.replaceFirstChar { it.titlecase(Locale.getDefault()) },
        )
    }

    /**
     * The catalogue's report on a stream: `status` 2 means it is being broadcast, `availability`
     * (0..1) how well it is seeded, as of `availability_updated_at` (seconds since epoch).
     */
    private fun healthOf(item: JSONObject): LiveTvChannelHealth {
        val status = item.optInt("status", 0)
        val availability = item.optDouble("availability", -1.0)
        val updatedAt = item.optLong("availability_updated_at", 0L)
        val fresh = updatedAt <= 0L || System.currentTimeMillis() / 1000 - updatedAt < HEALTH_FRESH_SECONDS
        return when {
            !fresh -> LiveTvChannelHealth.Offline
            status == 2 && availability != 0.0 -> if (availability in 0.0..0.5) LiveTvChannelHealth.Weak else LiveTvChannelHealth.Active
            availability > 0.5 -> LiveTvChannelHealth.Active
            availability > 0.0 -> LiveTvChannelHealth.Weak
            availability == 0.0 || status != 0 -> LiveTvChannelHealth.Offline
            else -> LiveTvChannelHealth.Unknown
        }
    }

    /** The id the channel's guide is written under: the catalogue's channel id, or its name. */
    private fun guideChannelId(group: JSONObject, name: String): String {
        val items = group.optJSONArray("items")
        val id = (0 until (items?.length() ?: 0)).asSequence()
            .mapNotNull { items?.optJSONObject(it)?.opt("channel_id") }
            .plus(listOfNotNull(group.opt("channel_id")))
            .map { it.toString().trim() }
            .firstOrNull { it.isNotBlank() && it != "null" && it != "0" }
        return if (id != null) "ace.$id" else "ace.${name.trim().lowercase(Locale.ROOT)}"
    }

    /**
     * The channel's logo: from `icons` (`[{type, url}]`; the light logo suits Nuvio's dark screens
     * best, then the plain one, the picon and the dark one), or an older single `icon`.
     */
    private fun logoOf(group: JSONObject): String? {
        val icons = group.optJSONArray("icons")
        if (icons != null && icons.length() > 0) {
            val byType = (0 until icons.length()).mapNotNull { icons.optJSONObject(it) }
                .mapNotNull { icon -> icon.optNonBlank("url")?.takeIf(String::isHttpUrl)?.let { icon.optInt("type", 0) to it } }
            for (type in intArrayOf(1, 0, 3, 2)) byType.firstOrNull { it.first == type }?.let { return it.second }
            byType.firstOrNull()?.let { return it.second }
        }
        return (group.optNonBlank("icon") ?: group.optNonBlank("logo"))?.takeIf(String::isHttpUrl)
    }

    private fun JSONObject.optNonBlank(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf(String::isNotBlank)
}
