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

internal object LiveTvAceStream {
    private const val TAG = "LiveTvAceStream"
    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 50

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
        for (page in 0 until MAX_PAGES) {
            val results = searchPage(settings, page)
            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                val group = results.optJSONObject(i) ?: continue
                group.toChannel(settings, channels.size)?.takeIf { seen.add(it.streamUrl) }?.let(channels::add)
            }
            if (results.length() < PAGE_SIZE) break
        }
        return channels
    }

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
        // The stream most peers have, and most recently seen alive.
        val best = items
            .filter { it.optString("infohash").isNotBlank() }
            .maxWithOrNull(
                compareBy<JSONObject> { it.optInt("status", 0) == 2 }
                    .thenBy { it.optDouble("availability", 0.0) }
                    .thenBy { it.optLong("availability_updated_at", 0L) },
            ) ?: return null
        val infohash = best.optString("infohash").trim().lowercase(Locale.ROOT)
        val name = optNonBlank("name") ?: best.optNonBlank("name") ?: return null
        val categories = best.optJSONArray("categories") ?: optJSONArray("categories")
        val category = categories?.optString(0)?.trim().orEmpty()
        return LiveTvChannel(
            id = "ace-$index-$infohash",
            name = name.trim(),
            streamUrl = infohashUrl(settings.engineUrl, infohash),
            tvgId = (best.opt("channel_id") ?: opt("channel_id"))?.toString()?.takeIf { it.isNotBlank() && it != "null" },
            logoUrl = optNonBlank("icon")?.takeIf(String::isHttpUrl),
            group = category.replaceFirstChar { it.titlecase(Locale.getDefault()) },
        )
    }

    private fun JSONObject.optNonBlank(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf(String::isNotBlank)
}
