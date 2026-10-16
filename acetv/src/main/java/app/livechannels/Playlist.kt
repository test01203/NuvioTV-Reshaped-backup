package app.livechannels

import java.util.Locale

/**
 * A channel list the viewer adds by its address (Settings): M3U entries with acestream://
 * links, engine links or plain stream links. Nothing is built in; the list is read on the device.
 */
object Playlist {
    /** The category of entries the list gives none. */
    const val DEFAULT_CATEGORY = "playlist"

    suspend fun load(url: String): List<Channel> = parse(Http.text(url, readTimeoutMs = 30_000))

    internal fun parse(text: String): List<Channel> {
        val channels = ArrayList<Channel>()
        val seen = HashSet<String>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() || line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    name = line.substringAfterLast(',').trim().ifBlank { null }
                    logo = attribute(line, "tvg-logo")
                    group = attribute(line, "group-title")
                }
                line.startsWith("#") -> Unit
                else -> {
                    val stream = streamOf(line)
                    if (stream != null && seen.add(stream)) {
                        channels += Channel(
                            key = "list:$stream",
                            name = name ?: "Channel ${channels.size + 1}",
                            logo = logo?.takeIf { it.startsWith("http") },
                            category = group?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: DEFAULT_CATEGORY,
                            infohashes = listOf(stream),
                            health = Health.Unknown,
                            availability = -1f,
                            programmes = emptyList(),
                        )
                    }
                    name = null
                    logo = null
                    group = null
                }
            }
        }
        return channels
    }

    private fun attribute(line: String, key: String): String? =
        Regex("""$key="([^"]*)"""").find(line)?.groupValues?.get(1)?.trim()?.ifBlank { null }

    /**
     * The stream an entry names, as the player takes it: "id:<content id>" for acestream:// and
     * engine `id=` links, the infohash for engine `infohash=` links, "url:<link>" for other streams.
     */
    private fun streamOf(line: String): String? {
        val link = line.substringBefore('|').trim()
        val hex = Regex("""[0-9a-fA-F]{40}""")
        return when {
            link.startsWith("acestream://", ignoreCase = true) ->
                hex.find(link)?.value?.let { "id:" + it.lowercase(Locale.ROOT) }
            link.contains("infohash=") -> Regex("""infohash=([0-9a-fA-F]{40})""").find(link)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            link.contains("/ace/") && link.contains("id=") ->
                Regex("""[?&]id=([0-9a-fA-F]{40})""").find(link)?.groupValues?.get(1)?.let { "id:" + it.lowercase(Locale.ROOT) }
            hex.matches(link) -> "id:" + link.lowercase(Locale.ROOT)
            link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true) -> "url:$link"
            else -> null
        }
    }
}
