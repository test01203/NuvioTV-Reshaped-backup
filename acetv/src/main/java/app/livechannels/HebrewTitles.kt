package app.livechannels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Programme titles in Hebrew where TMDB knows the film or series (with the viewer's TMDB key);
 * others stay as the guide gives them. Each title is looked up once.
 */
object HebrewTitles {
    private val names = mutableStateMapOf<String, String>()
    private val tried = HashSet<String>()
    private val matches = HashMap<String, Title?>()
    private val permits = Semaphore(3)

    /** The film or series [programmeTitle] was matched to, cached. */
    suspend fun match(programmeTitle: String, tmdbKey: String): Title? {
        synchronized(matches) { if (programmeTitle in matches) return matches[programmeTitle] }
        val title = runCatching { permits.withPermit { Subtitles.findTitle(programmeTitle, tmdbKey) } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        synchronized(matches) { matches[programmeTitle] = title }
        return title
    }

    @Composable
    fun of(programmeTitle: String, tmdbKey: String): String {
        if (tmdbKey.isNotBlank()) {
            LaunchedEffect(programmeTitle, tmdbKey) {
                if (!synchronized(tried) { tried.add(programmeTitle) }) return@LaunchedEffect
                val title = match(programmeTitle, tmdbKey) ?: return@LaunchedEffect
                runCatching { permits.withPermit { Subtitles.hebrewName(title, tmdbKey) } }
                    .onFailure { if (it is CancellationException) synchronized(tried) { tried.remove(programmeTitle) } }
                    .getOrNull()?.let { names[programmeTitle] = it }
            }
        }
        return names[programmeTitle] ?: programmeTitle
    }
}
