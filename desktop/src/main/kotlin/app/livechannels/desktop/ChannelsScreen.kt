package app.livechannels.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.livechannels.AceException
import app.livechannels.Channel
import app.livechannels.HebrewTitles
import coil3.compose.AsyncImage
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.delay

const val ALL = "*all"
const val FAVORITES = "*favorites"

/** The clock the "now playing" lines follow, moving on every 30 seconds. */
@Composable
fun rememberClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun ChannelsScreen(
    model: AppModel,
    onPlay: (List<Channel>, Int) -> Unit,
    onSettings: () -> Unit,
    onReload: () -> Unit,
) {
    val channels = model.shownChannels
    var category by remember { mutableStateOf(model.prefs.lastCategory ?: ALL) }
    val categories = remember(channels) {
        channels.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            .filter { it != ALL && it != FAVORITES }
    }
    val visible = remember(channels, category, model.favorites) {
        when (category) {
            ALL -> channels
            FAVORITES -> channels.filter { it.key in model.favorites }
            else -> channels.filter { it.category == category }
        }
    }
    val now = rememberClock()
    val listState = rememberLazyListState()
    // Back from a channel: the list returns to it.
    val returnIndex = remember(visible) { visible.indexOfFirst { it.key == model.lastChannelKey } }
    LaunchedEffect(model.loadState, returnIndex) {
        if (model.loadState == LoadState.Ready && returnIndex >= 0) listState.scrollToItem(returnIndex)
    }

    Row(Modifier.fillMaxSize().then(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // Categories
        Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Strings["app_name"], style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
            Text(
                text = if (model.loadState == LoadState.Loading) Strings["loading_channels"] else Strings["channels_count", channels.size],
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val entries = listOf(ALL, FAVORITES) + categories
                items(entries, key = { "c:$it" }) { entry ->
                    val count = when (entry) {
                        ALL -> channels.size
                        FAVORITES -> channels.count { it.key in model.favorites }
                        else -> channels.count { it.category == entry }
                    }
                    CategoryRow(
                        label = when (entry) {
                            ALL -> Strings["all_channels"]
                            FAVORITES -> Strings["favorites"]
                            else -> Strings.category(entry)
                        },
                        count = count,
                        selected = entry == category,
                        onClick = {
                            category = entry
                            model.prefs.lastCategory = entry
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                PillButton(Strings["refresh"], onReload)
                PillButton(Strings["settings"], onSettings)
            }
        }

        // Channels
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (val state = model.loadState) {
                LoadState.Loading -> CenterMessage(Strings["loading_channels"])
                is LoadState.Failed -> FailedState(state.reason, onRetry = onReload)
                LoadState.Ready -> if (visible.isEmpty()) {
                    CenterMessage(Strings["no_channels"])
                } else {
                    Column {
                        Text(Strings["favorite_hint"], style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                            modifier = Modifier.padding(bottom = 8.dp))
                        Box(Modifier.weight(1f)) {
                            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 14.dp)) {
                                items(visible.size, key = { visible[it].key }) { index ->
                                    val channel = visible[index]
                                    ChannelRow(
                                        channel = channel,
                                        now = now,
                                        tmdbKey = model.prefs.tmdbKey,
                                        isFavorite = channel.key in model.favorites,
                                        isLast = channel.key == model.lastChannelKey,
                                        onClick = { onPlay(visible, index) },
                                        onRightClick = { model.toggleFavorite(channel) },
                                    )
                                }
                            }
                            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) { highlighted ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium,
                color = if (highlighted) Color.Black.copy(alpha = 0.55f) else Palette.TextTertiary)
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    now: Long,
    tmdbKey: String,
    isFavorite: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onRightClick: () -> Unit,
) {
    FocusCard(onClick = onClick, onRightClick = onRightClick, selected = isLast, modifier = Modifier.fillMaxWidth()) { highlighted ->
        val secondary = if (highlighted) Color.Black.copy(alpha = 0.6f) else Palette.TextSecondary
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(channel.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val programme = channel.programmeAt(now)
                if (programme != null) {
                    Text(HebrewTitles.of(programme.title, tmdbKey), style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val progress = (now - programme.startMs).toFloat() / (programme.stopMs - programme.startMs).coerceAtLeast(1)
                    ProgressBar(progress, Modifier.padding(top = 4.dp).widthIn(max = 180.dp).fillMaxWidth(), height = 3.dp,
                        fill = if (highlighted) Color.Black else Palette.TextPrimary)
                } else {
                    Text(Strings.category(channel.category), style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1)
                }
            }
            val details = buildList {
                add(Strings["streams", channel.infohashes.size])
                if (channel.availability >= 0f) add(Strings["seeded", (channel.availability * 100).toInt()])
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1)
            HealthDot(channel.health, Modifier.padding(start = 10.dp))
            if (isFavorite) Text("★", color = if (highlighted) Color.Black else Palette.Amber, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
fun ChannelLogo(channel: Channel, width: Int = 64, height: Int = 40) {
    Box(
        Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Card),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Text(channel.name.take(2).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
    }
}

fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

@Composable
private fun FailedState(reason: AceException.Reason, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp)) {
            if (reason == AceException.Reason.NotInstalled) {
                Text(Strings["engine_missing_title"], style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
                Spacer(Modifier.height(10.dp))
                Text(Strings["engine_missing_text"], style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(Strings["install_engine"], { openInBrowser(WindowsEngine.DOWNLOAD_PAGE) })
                    PillButton(Strings["retry"], onRetry)
                }
            } else {
                Text(
                    Strings[if (reason == AceException.Reason.EngineFailed) "engine_failed" else "catalog_failed"],
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(20.dp))
                PillButton(Strings["retry"], onRetry)
            }
        }
    }
}
