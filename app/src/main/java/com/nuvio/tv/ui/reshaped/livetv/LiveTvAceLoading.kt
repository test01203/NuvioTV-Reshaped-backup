package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvAceStream
import com.nuvio.tv.ui.screens.player.PlayerUiState
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * The loading meter of an Ace Stream channel: while the engine finds peers and buffers, a bar
 * with how far it got, the peers and the download speed, read from the engine every second.
 */
@Composable
internal fun BoxScope.LiveTvAceLoadingMeter(uiState: PlayerUiState?) {
    val activeSession by LiveTvAceStream.session.collectAsState()
    val connecting by LiveTvAceStream.connecting.collectAsState()
    val diagnostic by LiveTvAceStream.diagnostic.collectAsState()
    val streamUrl = uiState?.currentStreamUrl
    val playerLoading = uiState != null && (uiState.showLoadingOverlay || uiState.isBuffering) &&
        streamUrl != null && streamUrl.contains("/ace/")
    val shownSince = remember(connecting || playerLoading) { System.currentTimeMillis() }
    if (!(connecting || playerLoading)) return
    // Without a session (an engine that does not do them) the bar still shows, moving with time.
    val session = activeSession ?: LiveTvAceStream.Session("", "", shownSince)

    var stats by remember(session) { mutableStateOf<LiveTvAceStream.SessionStats?>(null) }
    var elapsedMs by remember(session) { mutableStateOf(0L) }
    LaunchedEffect(session) {
        while (true) {
            if (session.statUrl.isNotEmpty()) LiveTvAceStream.stats(session)?.let { stats = it }
            elapsedMs = System.currentTimeMillis() - session.startedAtMs
            delay(1000)
        }
    }

    val current = stats
    // The engine's own figure when it gives one; otherwise a bar that moves with time and peers,
    // so a slow start still shows that something is happening.
    val progress = when {
        current == null -> (elapsedMs / 30_000f).coerceAtMost(0.3f)
        current.status == "dl" -> 0.95f
        current.progress != null -> 0.1f + current.progress * 0.85f
        else -> (0.1f + elapsedMs / 25_000f + current.peers / 100f).coerceAtMost(0.9f)
    }
    val phase = stringResource(
        when {
            current == null || current.status == "starting" || current.status == "wait" -> R.string.live_tv_ace_loading_connecting
            current.status == "prebuf" -> R.string.live_tv_ace_loading_buffering
            else -> R.string.live_tv_ace_loading_starting
        },
    )
    val details = current?.let { stringResource(R.string.live_tv_ace_loading_details, it.peers, it.speedDownKbps) }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .zIndex(10f)
            .padding(bottom = 64.dp)
            .width(420.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = phase, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        LiveTvMeterBar(progress)
        Text(
            text = (details?.let { "$it · " } ?: "") + "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary,
        )
        diagnostic?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 2)
        }
    }
}

@Composable
private fun LiveTvMeterBar(progress: Float) {
    val shape = RoundedCornerShape(3.dp)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(shape).background(Color.White.copy(alpha = 0.15f)),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(shape)
                .background(Color(0xFF34C759)),
        )
    }
}
