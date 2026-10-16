package app.livechannels

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One subtitle line: shown from [startMs] to [endMs] of the film. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** A film or series the programme guide's title was matched to. */
data class Title(val type: String, val imdbId: String, val name: String, val year: String?, val tmdbId: Long? = null)

/** A Hebrew subtitle file one of the addons offers for a title. */
data class SubtitleOption(val source: String, val label: String, val url: String)

/**
 * Hebrew subtitles for what a channel is showing: the programme guide's title is matched to a
 * film (Cinemeta, Stremio's catalogue), Stremio subtitle addons are asked for its Hebrew
 * subtitles, and the chosen file is read into cues. Timing against the broadcast is the
 * player's job (see the sync controls there).
 */
object Subtitles {
    /** Stremio subtitle addons asked for Hebrew subtitles, by name. */
    val ADDONS = listOf(
        "Wizdom" to "https://4b139a4b7f94-wizdom-stremio-v2.baby-beamup.club",
        "Ktuvit" to "https://4b139a4b7f94-ktuvit-stremio.baby-beamup.club",
        "OpenSubtitles" to "https://opensubtitles.stremio.homes/he%7Cen/ai-translated=true%7Cfrom=all%7Cauto-adjustment=true",
    )
    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private val HEBREW = setOf("heb", "he", "iw", "hebrew", "עברית")

    /**
     * The film (or else series) whose name best matches [programmeTitle]; null when none does.
     * Guide titles are often Russian: TMDB (with the viewer's own key, from Settings) matches
     * titles in any language and gives the IMDb id the addons use. Without a key, Cinemeta
     * (English names only) is asked.
     */
    suspend fun findTitle(programmeTitle: String, tmdbKey: String): Title? {
        val (query, year) = cleanTitle(programmeTitle)
        if (query.isBlank()) return null
        if (tmdbKey.isNotBlank()) {
            runCatching { findOnTmdb(query, year, tmdbKey.trim()) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()?.let { return it }
        }
        return findOnCinemeta(query, year)
    }

    private const val TMDB = "https://api.themoviedb.org/3"

    private suspend fun findOnTmdb(query: String, year: String?, key: String): Title? {
        // The guide's language first (Cyrillic means Russian), so localized titles match.
        val language = if (query.any { it in '\u0400'..'\u04FF' }) "ru-RU" else "en-US"
        val results = JSONObject(
            Http.text("$TMDB/search/multi?api_key=${Http.encode(key)}&language=$language&include_adult=false&query=${Http.encode(query)}"),
        ).optJSONArray("results") ?: return null
        val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("media_type") == "movie" || it.optString("media_type") == "tv" }
        fun yearOf(item: JSONObject) = item.optString("release_date").ifBlank { item.optString("first_air_date") }.take(4).ifBlank { null }
        fun nameOf(item: JSONObject) = item.optString("title").ifBlank { item.optString("name") }
        val best = candidates.firstOrNull { year != null && yearOf(it) == year }
            ?: candidates.firstOrNull { nameOf(it).equals(query, ignoreCase = true) }
            ?: candidates.firstOrNull()
            ?: return null
        val isMovie = best.optString("media_type") == "movie"
        val ids = JSONObject(Http.text("$TMDB/${if (isMovie) "movie" else "tv"}/${best.optLong("id")}/external_ids?api_key=${Http.encode(key)}"))
        val imdb = ids.optString("imdb_id").takeIf { it.startsWith("tt") } ?: return null
        return Title(if (isMovie) "movie" else "series", imdb, nameOf(best), yearOf(best), best.optLong("id"))
    }

    /** The Hebrew name TMDB has for [title]; null when it has none or there is no key. */
    suspend fun hebrewName(title: Title, tmdbKey: String): String? {
        val id = title.tmdbId ?: return null
        if (tmdbKey.isBlank()) return null
        val kind = if (title.type == "movie") "movie" else "tv"
        val json = JSONObject(Http.text("$TMDB/$kind/$id?api_key=${Http.encode(tmdbKey.trim())}&language=he-IL"))
        return json.optString("title").ifBlank { json.optString("name") }.takeIf { name -> name.any { it in '\u0590'..'\u05FF' } }
    }

    private suspend fun findOnCinemeta(query: String, year: String?): Title? {
        for (type in listOf("movie", "series")) {
            val metas = runCatching {
                JSONObject(Http.text("$CINEMETA/catalog/$type/top/search=${Http.encode(query)}.json")).optJSONArray("metas")
            }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: continue
            val candidates = (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }
                .mapNotNull { meta ->
                    val id = meta.optString("imdb_id").ifBlank { meta.optString("id") }.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                    Title(type, id, meta.optString("name"), meta.optString("releaseInfo").take(4).ifBlank { null })
                }
            // A matching year wins; then an exact name; then the catalogue's first answer.
            val best = candidates.firstOrNull { year != null && it.year == year }
                ?: candidates.firstOrNull { it.name.equals(query, ignoreCase = true) }
                ?: candidates.firstOrNull()
            if (best != null) return best
        }
        return null
    }

    /**
     * "The Matrix (1999) - film" → ("The Matrix", "1999"); Russian guide titles such as
     * "Х/ф «Матрица» (16+)" → ("Матрица", null).
     */
    internal fun cleanTitle(raw: String): Pair<String, String?> {
        val year = Regex("""\b(19|20)\d{2}\b""").find(raw)?.value
        // A quoted name is the title itself: Х/ф «Матрица», Т/с "Друзья".
        val quoted = Regex("""[«"„“]([^»"”“]{2,})[»"”“]""").find(raw)?.groupValues?.get(1)
        val name = (quoted ?: raw)
            .replace(Regex("""(?i)(Х/ф|М/ф|Т/с|Д/ф|Д/с|М/с|фильм|сериал|премьера)\.?"""), " ")
            .replace(Regex("""\d{1,2}\+"""), " ")
            .replace(Regex("""(?i)\d+\s*-?\s*(я|й)?\s*(серия|сезон|часть)"""), " ")
            .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .substringBefore(" - ").substringBefore(" | ").substringBefore(": ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', ',', '-', '"', '\'')
        return name to year
    }

    /** Every Hebrew subtitle the addons offer for [title], asked in parallel. */
    suspend fun search(title: Title): List<SubtitleOption> = coroutineScope {
        ADDONS.map { (name, base) ->
            async {
                runCatching {
                    val json = JSONObject(Http.text("$base/subtitles/${title.type}/${title.imdbId}.json"))
                    val list = json.optJSONArray("subtitles") ?: return@runCatching emptyList()
                    (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                        .filter { it.optString("lang").trim().lowercase() in HEBREW }
                        .mapNotNull { sub ->
                            val url = sub.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                            val label = listOf("title", "name", "id").map { sub.optString(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("http") }
                            SubtitleOption(name, label ?: name, url)
                        }
                }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    /** Downloads and reads a subtitle file (SRT or WebVTT, UTF-8 or Windows-1255, plain or gzip). */
    suspend fun load(url: String): List<Cue> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        val bytes = try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } else {
            bytes
        }
        parse(decode(raw))
    }

    /** UTF-8 when the bytes are valid UTF-8; otherwise Windows-1255, which older Hebrew subtitles use. */
    internal fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, runCatching { Charset.forName("windows-1255") }.getOrDefault(Charsets.ISO_8859_1))
        }
        return text.removePrefix("﻿")
    }

    private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val SHORT_TIME = Regex("""(\d{2}):(\d{2})[.](\d{3})\s*-->\s*(\d{2}):(\d{2})[.](\d{3})""")

    /** Reads SRT or WebVTT cues; formatting tags are dropped. */
    internal fun parse(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val full = TIME.find(line)
            val short = if (full == null) SHORT_TIME.find(line) else null
            if (full == null && short == null) {
                i++
                continue
            }
            val (start, end) = if (full != null) {
                val g = full.groupValues
                ms(g[1], g[2], g[3], g[4]) to ms(g[5], g[6], g[7], g[8])
            } else {
                val g = short!!.groupValues
                ms("0", g[1], g[2], g[3]) to ms("0", g[4], g[5], g[6])
            }
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            val clean = body.toString()
                .replace(Regex("""<[^>]+>"""), "")
                .replace(Regex("""\{\\[^}]*}"""), "")
                .trim()
            if (clean.isNotEmpty() && end > start) cues += Cue(start, end, clean)
        }
        return cues.sortedBy { it.startMs }
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long =
        ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + frac.padEnd(3, '0').take(3).toLong()

    /** The cue showing at [positionMs] of the film, if any. */
    fun cueAt(cues: List<Cue>, positionMs: Long): Cue? {
        var low = 0
        var high = cues.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> low = mid + 1
                else -> return cue
            }
        }
        return null
    }
}
