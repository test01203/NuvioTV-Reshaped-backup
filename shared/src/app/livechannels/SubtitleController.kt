package app.livechannels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The settings subtitles use, kept by each app its own way. */
interface SubtitleSettings {
    val tmdbKey: String
    var autoSubtitles: Boolean
}

/** A sound or subtitle track the broadcast itself carries, for choosing in the panel. */
data class SourceTrack(val id: String, val label: String, val selected: Boolean)

/** What the automatic sync last did, for the panel to say in the viewer's language. */
sealed interface SyncNote {
    /** Still listening: [seconds] of sound heard of the [needed]. */
    data class Listening(val seconds: Int, val needed: Int) : SyncNote
    /** No sound reaches the sync at all. */
    data object NoSound : SyncNote
    data class Synced(val confidence: Int, val offsetMs: Long) : SyncNote
    data class NoClearMatch(val confidence: Int, val speechShare: Int) : SyncNote
}

/** Where the automatic sync hears the sound: each app's player. */
interface SpeechSource {
    fun speech(): List<Pair<Long, Boolean>>
    fun secondsHeard(): Int
    /** False while no sound reaches the detector (a format it cannot read). */
    val hearsSound: Boolean
}

/**
 * Hebrew subtitles for the programme on air: matched from the guide's title, timed by the
 * guide's start time (the film's second 0 is when the programme began), and adjusted by hand
 * where the broadcast starts late or runs ads.
 */
class SubtitleController(private val scope: CoroutineScope, private val prefs: SubtitleSettings) {
    private companion object {
        /** With a guide, the film is looked for this far either side of where it is thought to be. */
        const val SEARCH_MS = 20 * 60_000L
    }

    enum class Status { Idle, NoGuide, Searching, NoMatch, NoSubs, Choose, Loading, Ready, Failed }

    var status by mutableStateOf(Status.Idle)
        private set
    var title by mutableStateOf<Title?>(null)
        private set
    var options by mutableStateOf<List<SubtitleOption>>(emptyList())
        private set
    var selected by mutableStateOf<SubtitleOption?>(null)
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set
    var offsetMs by mutableLongStateOf(0L)
        private set
    var programme: Programme? = null
        private set
    /** Why the last file could not be used, shown in the panel. */
    var error by mutableStateOf<String?>(null)
        private set
    /** What the automatic sync last did, shown in the panel. */
    var syncNote by mutableStateOf<SyncNote?>(null)
        private set
    /** The film's start is unknown (no guide: the viewer named the film), so the sync looks through all of it. */
    var startUnknown by mutableStateOf(false)
        private set
    /** Whether the automatic sync has matched this file yet. */
    var synced by mutableStateOf(false)
        private set
    var lastAutoSyncMs = 0L
        private set
    private var job: Job? = null

    /** A new programme or channel: what was found for the last one no longer applies. */
    fun reset(programme: Programme?) {
        job?.cancel()
        this.programme = programme
        title = null
        options = emptyList()
        selected = null
        cues = emptyList()
        offsetMs = 0
        error = null
        syncNote = null
        synced = false
        startUnknown = false
        lastAutoSyncMs = 0
        status = if (programme == null) Status.NoGuide else Status.Idle
        // Once chosen, subtitles follow the viewer from programme to programme.
        if (programme != null && prefs.autoSubtitles) search(autoPick = true)
    }

    fun search(autoPick: Boolean = false) {
        val current = programme ?: run { status = Status.NoGuide; return }
        job?.cancel()
        job = scope.launch {
            status = Status.Searching
            try {
                val found = HebrewTitles.match(current.title, prefs.tmdbKey)
                title = found
                if (found == null) {
                    status = Status.NoMatch
                    return@launch
                }
                options = Subtitles.search(found)
                status = if (options.isEmpty()) Status.NoSubs else Status.Choose
                if (autoPick) options.firstOrNull()?.let { loadOption(it) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                status = Status.Failed
            }
        }
    }

    /**
     * The viewer names the film (a channel without a guide, or a guide that names it wrongly):
     * it is matched by that name, and the sync finds where in the film the broadcast is.
     */
    fun searchByName(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        programme = Programme(clean, now, now + 4 * 60 * 60_000L)
        startUnknown = true
        title = null
        options = emptyList()
        selected = null
        cues = emptyList()
        offsetMs = 0
        synced = false
        syncNote = null
        search()
    }

    fun choose(option: SubtitleOption) {
        job?.cancel()
        job = scope.launch { loadOption(option) }
        prefs.autoSubtitles = true
    }

    private suspend fun loadOption(option: SubtitleOption) {
        status = Status.Loading
        selected = option
        error = null
        synced = false
        syncNote = null
        try {
            cues = Subtitles.load(option.url)
            status = if (cues.isEmpty()) Status.Failed else Status.Ready
            if (cues.isEmpty()) error = Subtitles.lastError
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            cues = emptyList()
            status = Status.Failed
            error = Subtitles.lastError ?: "${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(120)}"
        }
    }

    fun off() {
        job?.cancel()
        selected = null
        cues = emptyList()
        status = if (options.isEmpty()) Status.Idle else Status.Choose
        prefs.autoSubtitles = false
    }

    /** Where in the film the broadcast is now. */
    fun positionMs(nowMs: Long): Long = nowMs - (programme?.startMs ?: nowMs) + offsetMs

    /** Lines the subtitles up with the speech heard; applied only when the match is clear. */
    suspend fun autoSync(source: SpeechSource) {
        val start = programme?.startMs ?: return
        if (cues.isEmpty()) return
        if (!source.hearsSound) {
            syncNote = SyncNote.NoSound
            return
        }
        val heard = source.secondsHeard()
        if (heard < AutoSync.MIN_SECONDS) {
            syncNote = SyncNote.Listening(heard, AutoSync.MIN_SECONDS)
            return
        }
        val speech = source.speech()
        val share = if (speech.isEmpty()) 0 else speech.count { it.second } * 100 / speech.size
        // With the guide, the film started near the programme's start; without it, anywhere.
        val range = if (startUnknown) {
            val sinceStart = System.currentTimeMillis() - start
            (-sinceStart - 5 * 60_000L)..(cues.maxOf { it.endMs })
        } else {
            (offsetMs - SEARCH_MS)..(offsetMs + SEARCH_MS)
        }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            AutoSync.align(speech, cues, start, range)
        }
        lastAutoSyncMs = System.currentTimeMillis()
        val confidence = ((result?.confidence ?: 0f) * 100).toInt()
        syncNote = if (result != null && result.confidence >= AutoSync.TRUSTED) {
            offsetMs = result.offsetMs
            synced = true
            // Found once: later checks only follow small drifts (ads), around this point.
            startUnknown = false
            SyncNote.Synced(confidence, result.offsetMs)
        } else {
            SyncNote.NoClearMatch(confidence, share)
        }
    }

    /**
     * Keeps the subtitles in sync while a file is shown: tries every 20 seconds until the first
     * clear match, then checks every 3 minutes (ads and breaks move the film).
     */
    suspend fun runAutoSync(source: SpeechSource) {
        if (cues.isEmpty()) return
        while (true) {
            autoSync(source)
            kotlinx.coroutines.delay(if (synced) 180_000L else 20_000L)
        }
    }

    fun nudge(deltaMs: Long) {
        offsetMs += deltaMs
    }

    /** The next line was just said: its start is now. */
    fun nextLineNow(nowMs: Long) {
        val position = positionMs(nowMs)
        cues.firstOrNull { it.startMs > position }?.let { offsetMs += it.startMs - position }
    }

    /** The line on screen comes too late: the previous one starts now. */
    fun previousLineNow(nowMs: Long) {
        val position = positionMs(nowMs)
        cues.lastOrNull { it.startMs < position - 500 }?.let { offsetMs -= position - it.startMs }
    }
}

