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
    var syncNote by mutableStateOf<String?>(null)
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

    fun choose(option: SubtitleOption) {
        job?.cancel()
        job = scope.launch { loadOption(option) }
        prefs.autoSubtitles = true
    }

    private suspend fun loadOption(option: SubtitleOption) {
        status = Status.Loading
        selected = option
        error = null
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

    /** Lines the subtitles up with the speech the tap heard; applied only when the match is clear. */
    suspend fun autoSync(tap: SpeechSource) {
        val start = programme?.startMs ?: return
        if (cues.isEmpty()) return
        val speech = tap.speech()
        val heard = tap.secondsHeard()
        val share = if (speech.isEmpty()) 0 else speech.count { it.second } * 100 / speech.size
        val diagnostic = if (!tap.hearsSound) "no sound reaches the sync (format not supported)"
        else "heard ${heard}s, speech $share%"
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            AutoSync.align(speech, cues, start, offsetMs)
        }
        lastAutoSyncMs = System.currentTimeMillis()
        syncNote = when {
            result == null -> "auto: not enough to match yet ($diagnostic)"
            result.confidence >= 0.35f -> {
                offsetMs = result.offsetMs
                "auto: synced (${(result.confidence * 100).toInt()}%) · $diagnostic"
            }
            else -> "auto: no clear match (${(result.confidence * 100).toInt()}%) · $diagnostic"
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

