package app.livechannels.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.livechannels.AceException
import app.livechannels.Channel
import app.livechannels.HebrewTitles
import app.livechannels.Sessions
import app.livechannels.SubtitleController
import app.livechannels.SubtitleOption
import app.livechannels.Subtitles
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Phase { Connecting, Buffering, Playing, Failed }

/** Streams of a channel tried, in order, before it counts as not playable now. */
private const val MAX_STREAMS = 5
/** A stream that found no peer at all after this long is given up for the next one. */
private const val NO_PEER_GIVE_UP_MS = 25_000L
/** After the engine has peers, the player waits for the engine's prebuffer at most this long. */
private const val PREBUFFER_WAIT_MS = 10_000L
/** How long the player gets to show a picture once it opened the stream. */
private const val PLAYER_WAIT_MS = 30_000L
/** With the engine already streaming, a stream that shows no picture this long is reloaded once. */
private const val RELOAD_AFTER_MS = 20_000L
/** No picture and no sound this long counts as a dropped stream. */
private const val STALL_MS = 15_000L
/** Reloads in a row that did not bring the picture back before the engine session is renewed. */
private const val MAX_RELOADS = 3
/** Engine sessions renewed automatically for one channel before it shows as failed. */
private const val MAX_REOPENINGS = 3

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    model: AppModel,
    player: VlcPlayer,
    list: List<Channel>,
    startIndex: Int,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onExit: () -> Unit,
) {
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            // Leaving the player closes every engine session, even after this screen is gone.
            Sessions.appScope.launch { Sessions.stopAll() }
        }
    }

    var index by remember { mutableIntStateOf(startIndex) }
    var attempt by remember { mutableIntStateOf(0) }
    var reopenings by remember { mutableIntStateOf(0) }
    val channel = list[index]
    var phase by remember { mutableStateOf(Phase.Connecting) }
    var stats by remember { mutableStateOf<Sessions.Stats?>(null) }
    var streamNo by remember { mutableIntStateOf(1) }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var infoUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 6_000) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val now = rememberClock()
    val scope = rememberCoroutineScope()
    val subtitles = remember { SubtitleController(scope, model.prefs) }
    var panelOpen by remember { mutableStateOf(false) }
    val tmdbKey = model.prefs.tmdbKey
    // Subtitles belong to a programme: a new channel or the next programme starts over.
    val programme = channel.programmeAt(now)
    LaunchedEffect(index, programme?.startMs) { subtitles.reset(programme) }
    // Automatic sync: once 90 s of sound were heard, then every 3 minutes (ads move the film).
    LaunchedEffect(subtitles.cues) {
        if (subtitles.cues.isEmpty()) return@LaunchedEffect
        while (true) {
            if (player.secondsHeard() >= 90) subtitles.autoSync(player)
            delay(if (subtitles.lastAutoSyncMs == 0L) 10_000 else 180_000)
        }
    }
    LaunchedEffect(index) {
        model.lastChannelKey = list[index].key
        reopenings = 0
    }

    LaunchedEffect(index, attempt) {
        phase = Phase.Connecting
        stats = null
        errorText = null
        startedAt = System.currentTimeMillis()
        infoUntil = startedAt + 6_000
        player.stop()
        // The previous channel's sessions are closed before the next one opens.
        withContext(NonCancellable) { Sessions.stopAll() }
        val engine = model.prefs.engineUrl
        var session: Sessions.Session? = null
        try {
            try {
                WindowsEngine.ensureRunning(engine)
            } catch (error: AceException) {
                errorText = Strings[if (error.reason == AceException.Reason.NotInstalled) "engine_missing_title" else "engine_failed"]
                phase = Phase.Failed
                return@LaunchedEffect
            }
            val hashes = channel.infohashes.take(MAX_STREAMS)
            for ((i, hash) in hashes.withIndex()) {
                streamNo = i + 1
                val last = i == hashes.lastIndex
                val link = Sessions.streamUrl(engine, hash)
                session = if (Sessions.isEngineLink(link)) Sessions.open(link) else null
                val opened = session
                if (opened != null && !waitForStream(opened) { stats = it } && !last) {
                    Sessions.stop(opened)
                    session = null
                    continue
                }
                phase = Phase.Buffering
                val url = opened?.playbackUrl ?: link
                player.play(url)
                var playing = player.awaitPicture(RELOAD_AFTER_MS)
                if (!playing) {
                    // Stuck with no picture: open the same stream again once before moving on.
                    player.play(url)
                    playing = player.awaitPicture(PLAYER_WAIT_MS)
                }
                if (playing) {
                    phase = Phase.Playing
                    // Holds the session open until the channel changes or the player closes,
                    // reloading the stream whenever it drops or stalls.
                    player.keepAlive(url) { recovering -> phase = if (recovering) Phase.Buffering else Phase.Playing }
                    if (reopenings < MAX_REOPENINGS) {
                        // Reloading no longer helps: a new engine session for the same channel.
                        reopenings++
                        attempt++
                        awaitCancellation()
                    }
                }
                player.stop()
                Sessions.stop(session)
                session = null
            }
            errorText = Sessions.lastAnswer
            phase = Phase.Failed
        } finally {
            withContext(NonCancellable) { Sessions.stop(session) }
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(panelOpen) { if (!panelOpen) runCatching { focus.requestFocus() } }
    var controlsUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 3_000) }
    LaunchedEffect(controlsUntil) {
        delay((controlsUntil - System.currentTimeMillis()).coerceAtLeast(0))
        controlsUntil = 0
    }
    LaunchedEffect(infoUntil) {
        delay((infoUntil - System.currentTimeMillis()).coerceAtLeast(0))
        infoUntil = 0
    }

    fun zap(step: Int) {
        index = (index + step + list.size) % list.size
    }

    fun changeVolume(step: Int) {
        player.volume = (player.volume + step).coerceIn(0, 150)
        player.muted = false
        model.prefs.volume = player.volume
        controlsUntil = System.currentTimeMillis() + 3_000
    }

    fun openPanel() {
        panelOpen = true
        if (subtitles.status == SubtitleController.Status.Idle) subtitles.search()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPointerEvent(PointerEventType.Move) { controlsUntil = System.currentTimeMillis() + 3_000 }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onToggleFullscreen() },
                    onTap = {
                        if (panelOpen) panelOpen = false else infoUntil = System.currentTimeMillis() + 6_000
                    },
                )
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Escape, Key.Backspace -> {
                        when {
                            panelOpen -> panelOpen = false
                            fullscreen -> onToggleFullscreen()
                            else -> onExit()
                        }
                        true
                    }
                    Key.DirectionUp, Key.PageUp -> { zap(-1); true }
                    Key.DirectionDown, Key.PageDown -> { zap(1); true }
                    Key.S, Key.C, Key.DirectionLeft, Key.DirectionRight -> { if (panelOpen) panelOpen = false else openPanel(); true }
                    Key.F, Key.F11 -> { onToggleFullscreen(); true }
                    Key.M -> { player.muted = !player.muted; controlsUntil = System.currentTimeMillis() + 3_000; true }
                    Key.Plus, Key.Equals, Key.NumPadAdd -> { changeVolume(10); true }
                    Key.Minus, Key.NumPadSubtract -> { changeVolume(-10); true }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        if (phase == Phase.Failed) attempt++ else infoUntil = System.currentTimeMillis() + 6_000
                        true
                    }
                    else -> false
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { player.draw(this) }

        SubtitleLine(subtitles)
        val showControls = System.currentTimeMillis() < controlsUntil
        if (System.currentTimeMillis() < infoUntil || phase != Phase.Playing || showControls) {
            InfoBar(channel, index + 1, now, tmdbKey, Modifier.align(Alignment.TopStart).padding(36.dp))
        }
        if (showControls || phase == Phase.Failed) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f)).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton("← " + Strings["back"], onExit)
                PillButton("▲", { zap(-1) })
                PillButton("▼", { zap(1) })
                PillButton(Strings["subtitles"], ::openPanel)
                PillButton(Strings["fullscreen"], onToggleFullscreen)
                PillButton("−", { changeVolume(-10) })
                Text(if (player.muted) "🔇" else "${player.volume}%", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                PillButton("+", { changeVolume(10) })
            }
            Text(Strings["player_keys"], style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
        }
        if (panelOpen) SubtitlePanel(subtitles, tmdbKey, onAutoSync = { scope.launch { subtitles.autoSync(player) } }, onClose = { panelOpen = false })

        when (phase) {
            Phase.Connecting, Phase.Buffering -> LoadingMeter(
                phase = phase,
                stats = stats,
                startedAt = startedAt,
                streamNo = streamNo,
                streams = channel.infohashes.size.coerceAtMost(MAX_STREAMS),
                modifier = Modifier.align(Alignment.Center),
            )
            Phase.Failed -> Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(Strings["player_failed"], style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                errorText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary) }
                PillButton(Strings["retry"], { attempt++ })
            }
            Phase.Playing -> Unit
        }
    }
}

/** True once the stream shows a picture (or plays sound, for a radio); false on an error or timeout. */
private suspend fun VlcPlayer.awaitPicture(timeoutMs: Long): Boolean {
    val until = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < until) {
        if (firstFrameAt > 0L) return true
        if (state == VlcPlayer.State.Playing && secondsHeard() >= 3) return true
        if (state == VlcPlayer.State.Error) return false
        delay(250)
    }
    return false
}

/**
 * Returns only when the stream cannot be brought back: after an error, an end (the engine closed
 * the connection) or a long stall, the same stream is loaded again, a few times in a row.
 */
private suspend fun VlcPlayer.keepAlive(url: String, onRecovering: (Boolean) -> Unit) {
    var reloads = 0
    while (true) {
        delay(1_000)
        val dropped = state == VlcPlayer.State.Error || state == VlcPlayer.State.Ended ||
            System.currentTimeMillis() - lastDataAt >= STALL_MS
        if (!dropped) continue
        onRecovering(true)
        var back = false
        while (!back && reloads < MAX_RELOADS) {
            reloads++
            delay(1_000L * reloads)
            play(url)
            back = awaitPicture(RELOAD_AFTER_MS)
        }
        if (!back) return
        onRecovering(false)
        reloads = 0
    }
}

/**
 * Follows the session until the engine streams: like Ace's own player, once the engine finished
 * its prebuffer, or a little after the first peer. False when it found no peer at all in time.
 */
private suspend fun waitForStream(session: Sessions.Session, onStats: (Sessions.Stats) -> Unit): Boolean {
    val started = System.currentTimeMillis()
    var firstPeerAt = 0L
    while (true) {
        val elapsed = System.currentTimeMillis() - started
        Sessions.stats(session)?.let { stats ->
            onStats(stats)
            if (stats.status == "dl") return true
            if (stats.peers > 0) {
                if (firstPeerAt == 0L) firstPeerAt = System.currentTimeMillis()
                if (System.currentTimeMillis() - firstPeerAt >= PREBUFFER_WAIT_MS) return true
            }
        }
        if (elapsed >= NO_PEER_GIVE_UP_MS && firstPeerAt == 0L) return false
        delay(500)
    }
}

@Composable
private fun InfoBar(channel: Channel, number: Int, now: Long, tmdbKey: String, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChannelLogo(channel, width = 96, height = 60)
        Column {
            Text("$number  ${channel.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            channel.programmeAt(now)?.let { programme ->
                Text(Strings["now"] + ": " + HebrewTitles.of(programme.title, tmdbKey), style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val progress = (now - programme.startMs).toFloat() / (programme.stopMs - programme.startMs).coerceAtLeast(1)
                ProgressBar(progress, Modifier.padding(vertical = 4.dp).width(240.dp), height = 3.dp, fill = Palette.TextPrimary)
            }
            channel.programmeAfter(now)?.let { programme ->
                Text(Strings["next"] + ": " + HebrewTitles.of(programme.title, tmdbKey), style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Strings["subs_hint"], style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
        HealthDot(channel.health)
    }
}

@Composable
private fun LoadingMeter(
    phase: Phase,
    stats: Sessions.Stats?,
    startedAt: Long,
    streamNo: Int,
    streams: Int,
    modifier: Modifier = Modifier,
) {
    var elapsed by remember(startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(500)
        }
    }
    val progress = when {
        phase == Phase.Buffering -> (0.9f + elapsed / 600_000f).coerceAtMost(0.98f)
        stats == null -> (elapsed / 30_000f).coerceAtMost(0.3f)
        stats.status == "dl" -> 0.9f
        stats.progress != null -> 0.1f + stats.progress!! * 0.8f
        else -> (0.1f + elapsed / 30_000f + stats.peers / 100f).coerceAtMost(0.85f)
    }
    val title = Strings[
        when {
            phase == Phase.Buffering -> "loading_starting"
            stats == null || stats.peers == 0 -> "loading_connecting"
            else -> "loading_buffering"
        },
    ]
    Column(
        modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        ProgressBar(progress, Modifier.fillMaxWidth(), height = 6.dp)
        val parts = buildList {
            stats?.let { add(Strings["loading_details", it.peers, it.speedDownKbps]) }
            if (streams > 1) add(Strings["loading_stream", streamNo, streams])
            add("${(progress * 100).toInt()}%")
        }
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
        Sessions.lastAnswer?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1) }
    }
}

/** The current line, at the bottom of the picture. */
@Composable
private fun BoxScope.SubtitleLine(controller: SubtitleController) {
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
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            shadow = Shadow(Color.Black, blurRadius = 8f),
        ),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 80.dp, start = 80.dp, end = 80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** The subtitles panel: matching, the choice of file, and the sync controls. */
@Composable
private fun BoxScope.SubtitlePanel(controller: SubtitleController, tmdbKey: String, onAutoSync: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(460.dp)
            .background(Color.Black.copy(alpha = 0.88f))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Strings["subs_title"], style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            PillButton("✕", onClose)
        }
        controller.programme?.let { programme ->
            Text(HebrewTitles.of(programme.title, tmdbKey), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 2)
        }
        controller.title?.let { title ->
            Text(Strings["subs_matched", title.name + (title.year?.let { " ($it)" } ?: "")],
                style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
        }
        val statusText = when (controller.status) {
            SubtitleController.Status.NoGuide -> Strings["subs_no_guide"]
            SubtitleController.Status.Searching -> Strings["subs_searching"]
            SubtitleController.Status.NoMatch -> Strings[if (tmdbKey.isBlank()) "subs_no_match_key" else "subs_no_match"]
            SubtitleController.Status.NoSubs -> Strings["subs_none"]
            SubtitleController.Status.Loading -> Strings["subs_loading"]
            SubtitleController.Status.Failed -> Strings["subs_failed"]
            else -> null
        }
        statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Amber) }
        controller.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 2) }

        if (controller.cues.isNotEmpty()) {
            Text(Strings["subs_offset", controller.offsetMs / 1000.0], style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("−5s", { controller.nudge(-5_000) })
                PillButton("−0.5s", { controller.nudge(-500) })
                PillButton("+0.5s", { controller.nudge(500) })
                PillButton("+5s", { controller.nudge(5_000) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(Strings["subs_auto_sync"], onAutoSync)
                controller.syncNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton(Strings["subs_next_now"], { controller.nextLineNow(System.currentTimeMillis()) })
                PillButton(Strings["subs_prev_now"], { controller.previousLineNow(System.currentTimeMillis()) })
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "off") { OptionRow(Strings["subs_off"], null, controller.selected == null, onClick = controller::off) }
            if (controller.status == SubtitleController.Status.Idle || controller.status == SubtitleController.Status.Failed ||
                controller.status == SubtitleController.Status.NoMatch || controller.status == SubtitleController.Status.NoSubs
            ) {
                item(key = "search") { OptionRow(Strings["subs_search"], null, false, onClick = { controller.search() }) }
            }
            items(controller.options, key = { it.url }) { option: SubtitleOption ->
                OptionRow(option.label, option.source, controller.selected == option, onClick = { controller.choose(option) })
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, source: String?, selected: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text((if (selected) "✓ " else "") + title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            source?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
}
