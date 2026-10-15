package app.livechannels.ui

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.livechannels.AceException
import app.livechannels.AppModel
import app.livechannels.Channel
import app.livechannels.Engine
import app.livechannels.R
import app.livechannels.Sessions
import kotlin.coroutines.resume
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class Phase { Connecting, Buffering, Playing, Failed }

/** Streams of a channel tried, in order, before it counts as not playable now. */
private const val MAX_STREAMS = 5
/** A stream that found no peer at all after this long is given up for the next one. */
private const val NO_PEER_GIVE_UP_MS = 25_000L
/** How long the engine gets to start streaming before the player opens the stream anyway. */
private const val STREAM_WAIT_MS = 35_000L
/** How long the player gets to show a picture once it opened the stream. */
private const val PLAYER_WAIT_MS = 60_000L

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context): ExoPlayer {
    // Broadcast streams often send key frames without IDR flags: start on the first one.
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
    val http = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)
    // The engine answers errors while it still buffers a channel: keep asking.
    val retries = object : DefaultLoadErrorHandlingPolicy(30) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = 1_500L
    }
    val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors)
        .setLoadErrorHandlingPolicy(retries)
    return ExoPlayer.Builder(context).setMediaSourceFactory(sources).build()
}

/** True once the player shows the stream (a picture, or sound for a radio), false on an error or timeout. */
private suspend fun ExoPlayer.awaitPlaying(): Boolean = withTimeoutOrNull(PLAYER_WAIT_MS) {
    suspendCancellableCoroutine { continuation ->
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && continuation.isActive) continuation.resume(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
        addListener(listener)
        continuation.invokeOnCancellation { removeListener(listener) }
    }
} ?: false

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(model: AppModel, list: List<Channel>, startIndex: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val player = remember { buildPlayer(context) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var index by remember { mutableIntStateOf(startIndex) }
    var attempt by remember { mutableIntStateOf(0) }
    val channel = list[index]
    var phase by remember { mutableStateOf(Phase.Connecting) }
    var stats by remember { mutableStateOf<Sessions.Stats?>(null) }
    var streamNo by remember { mutableIntStateOf(1) }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var infoUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 6_000) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val now = rememberClock()

    LaunchedEffect(index, attempt) {
        phase = Phase.Connecting
        stats = null
        errorText = null
        startedAt = System.currentTimeMillis()
        infoUntil = startedAt + 6_000
        player.stop()
        val engine = model.prefs.engineUrl
        var session: Sessions.Session? = null
        try {
            try {
                Engine.ensureRunning(context, engine)
            } catch (error: AceException) {
                errorText = context.getString(if (error.reason == AceException.Reason.NotInstalled) R.string.engine_missing_title else R.string.engine_failed)
                phase = Phase.Failed
                return@LaunchedEffect
            }
            val hashes = channel.infohashes.take(MAX_STREAMS)
            for ((i, hash) in hashes.withIndex()) {
                streamNo = i + 1
                val last = i == hashes.lastIndex
                val link = Sessions.streamUrl(engine, hash)
                session = Sessions.open(link)
                val opened = session
                if (opened != null && !waitForStream(opened) { stats = it } && !last) {
                    Sessions.stop(opened)
                    session = null
                    continue
                }
                phase = Phase.Buffering
                player.setMediaItem(MediaItem.fromUri(opened?.playbackUrl ?: link))
                player.prepare()
                player.playWhenReady = true
                if (player.awaitPlaying()) {
                    phase = Phase.Playing
                    // Holds the session open until the channel changes or the player closes.
                    awaitCancellation()
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
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        index = (index - 1 + list.size) % list.size
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        index = (index + 1) % list.size
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (phase == Phase.Failed) attempt++ else infoUntil = System.currentTimeMillis() + 6_000
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (System.currentTimeMillis() < infoUntil || phase != Phase.Playing) {
            InfoBar(channel, index + 1, now, Modifier.align(Alignment.TopStart).padding(36.dp))
        }
        LaunchedEffect(infoUntil) {
            delay((infoUntil - System.currentTimeMillis()).coerceAtLeast(0))
            infoUntil = 0
        }

        when (phase) {
            Phase.Connecting, Phase.Buffering -> LoadingMeter(
                phase = phase,
                stats = stats,
                startedAt = startedAt,
                streamNo = streamNo,
                streams = channel.infohashes.size.coerceAtMost(MAX_STREAMS),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            )
            Phase.Failed -> Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.player_failed), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                errorText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(top = 6.dp)) }
                Text("OK: " + stringResource(R.string.retry) + "   ▲▼", style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextSecondary, modifier = Modifier.padding(top = 12.dp))
            }
            Phase.Playing -> Unit
        }
    }
}

/**
 * Follows the session until the engine streams. False when it found no peer at all within
 * [NO_PEER_GIVE_UP_MS], so the next stream can be tried.
 */
private suspend fun waitForStream(session: Sessions.Session, onStats: (Sessions.Stats) -> Unit): Boolean {
    val started = System.currentTimeMillis()
    var sawPeers = false
    while (true) {
        val elapsed = System.currentTimeMillis() - started
        Sessions.stats(session)?.let { stats ->
            onStats(stats)
            if (stats.peers > 0) sawPeers = true
            if (stats.status == "dl") return true
        }
        if (!sawPeers && elapsed >= NO_PEER_GIVE_UP_MS) return false
        if (elapsed >= STREAM_WAIT_MS) return sawPeers
        delay(1_000)
    }
}

@Composable
private fun InfoBar(channel: Channel, number: Int, now: Long, modifier: Modifier = Modifier) {
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
                Text(stringResource(R.string.now) + ": " + programme.title, style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val progress = (now - programme.startMs).toFloat() / (programme.stopMs - programme.startMs).coerceAtLeast(1)
                ProgressBar(progress, Modifier.padding(vertical = 4.dp).width(240.dp), height = 3.dp, fill = Palette.TextPrimary)
            }
            channel.programmeAfter(now)?.let { programme ->
                Text(stringResource(R.string.next) + ": " + programme.title, style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
        stats.progress != null -> 0.1f + stats.progress * 0.8f
        else -> (0.1f + elapsed / 30_000f + stats.peers / 100f).coerceAtMost(0.85f)
    }
    val title = stringResource(
        when {
            phase == Phase.Buffering -> R.string.loading_starting
            stats == null || stats.peers == 0 -> R.string.loading_connecting
            else -> R.string.loading_buffering
        },
    )
    Column(
        modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        ProgressBar(progress, Modifier.fillMaxWidth(), height = 6.dp)
        val parts = buildList {
            stats?.let { add(stringResource(R.string.loading_details, it.peers, it.speedDownKbps)) }
            if (streams > 1) add(stringResource(R.string.loading_stream, streamNo, streams))
            add("${(progress * 100).toInt()}%")
        }
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
        Sessions.lastAnswer?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1) }
    }
}
