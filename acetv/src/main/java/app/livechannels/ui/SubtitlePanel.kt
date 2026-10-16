package app.livechannels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.livechannels.Cue
import app.livechannels.HebrewTitles
import app.livechannels.Prefs
import app.livechannels.Programme
import app.livechannels.R
import app.livechannels.SubtitleOption
import app.livechannels.Subtitles
import app.livechannels.Title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hebrew subtitles for the programme on air: matched from the guide's title, timed by the
 * guide's start time (the film's second 0 is when the programme began), and adjusted by hand
 * where the broadcast starts late or runs ads.
 */
class SubtitleController(private val scope: CoroutineScope, private val prefs: Prefs) {
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
    suspend fun autoSync(tap: app.livechannels.SpeechTap) {
        val start = programme?.startMs ?: return
        if (cues.isEmpty()) return
        val speech = tap.speech()
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            app.livechannels.AutoSync.align(speech, cues, start, offsetMs)
        }
        lastAutoSyncMs = System.currentTimeMillis()
        syncNote = when {
            result == null -> "auto: not enough speech heard yet"
            result.confidence >= 0.35f -> {
                offsetMs = result.offsetMs
                "auto: synced (%.0f%%)".format(result.confidence * 100)
            }
            else -> "auto: no clear match (%.0f%%)".format(result.confidence * 100)
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

/** The current line, at the bottom of the picture. */
@Composable
fun BoxScope.SubtitleLine(controller: SubtitleController) {
    if (controller.cues.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(controller.cues) {
        while (true) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val cue = Subtitles.cueAt(controller.cues, controller.positionMs(now)) ?: return
    Text(
        text = cue.text,
        textAlign = TextAlign.Center,
        style = TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            shadow = Shadow(Color.Black, blurRadius = 8f),
        ),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 48.dp, start = 80.dp, end = 80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** The subtitles panel: matching, the choice of file, and the sync controls. */
@Composable
fun BoxScope.SubtitlePanel(controller: SubtitleController, tmdbKey: String, onAutoSync: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(440.dp)
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.subs_title), style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
        controller.programme?.let { programme ->
            Text(HebrewTitles.of(programme.title, tmdbKey), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 2)
        }
        controller.title?.let { title ->
            Text(stringResource(R.string.subs_matched, title.name + (title.year?.let { " ($it)" } ?: "")),
                style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
        }
        val statusText = when (controller.status) {
            SubtitleController.Status.NoGuide -> stringResource(R.string.subs_no_guide)
            SubtitleController.Status.Searching -> stringResource(R.string.subs_searching)
            SubtitleController.Status.NoMatch -> stringResource(if (tmdbKey.isBlank()) R.string.subs_no_match_key else R.string.subs_no_match)
            SubtitleController.Status.NoSubs -> stringResource(R.string.subs_none)
            SubtitleController.Status.Loading -> stringResource(R.string.subs_loading)
            SubtitleController.Status.Failed -> stringResource(R.string.subs_failed)
            else -> null
        }
        statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Amber) }
        controller.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 2) }

        // Sync controls, once a file is shown.
        if (controller.cues.isNotEmpty()) {
            Text(stringResource(R.string.subs_offset, controller.offsetMs / 1000.0), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("−5s", { controller.nudge(-5_000) }, Modifier.focusRequester(focus))
                PillButton("−0.5s", { controller.nudge(-500) })
                PillButton("+0.5s", { controller.nudge(500) })
                PillButton("+5s", { controller.nudge(5_000) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton(stringResource(R.string.subs_auto_sync), onAutoSync)
                controller.syncNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                    modifier = Modifier.align(Alignment.CenterVertically)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton(stringResource(R.string.subs_next_now), { controller.nextLineNow(System.currentTimeMillis()) })
                PillButton(stringResource(R.string.subs_prev_now), { controller.previousLineNow(System.currentTimeMillis()) })
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "off") {
                OptionRow(stringResource(R.string.subs_off), null, controller.selected == null,
                    onClick = controller::off,
                    modifier = if (controller.cues.isEmpty()) Modifier.focusRequester(focus) else Modifier)
            }
            if (controller.status == SubtitleController.Status.Idle || controller.status == SubtitleController.Status.Failed ||
                controller.status == SubtitleController.Status.NoMatch || controller.status == SubtitleController.Status.NoSubs
            ) {
                item(key = "search") { OptionRow(stringResource(R.string.subs_search), null, false, onClick = { controller.search() }) }
            }
            items(controller.options, key = { it.url }) { option ->
                OptionRow(option.label, option.source, controller.selected == option, onClick = { controller.choose(option) })
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, source: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 400.dp)) {
            Text((if (selected) "✓ " else "") + title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            source?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
}
