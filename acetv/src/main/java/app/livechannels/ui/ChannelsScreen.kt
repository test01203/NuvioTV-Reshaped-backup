package app.livechannels.ui

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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import app.livechannels.AceException
import app.livechannels.AppModel
import app.livechannels.Channel
import app.livechannels.Engine
import app.livechannels.HebrewTitles
import app.livechannels.LoadState
import app.livechannels.R
import kotlinx.coroutines.delay

private const val ALL = "*all"
private const val FAVORITES = "*favorites"

/** Ace Stream's catalogue categories, by their names in the catalogue. */
private val CATEGORY_LABELS = mapOf(
    "sport" to R.string.cat_sport,
    "movies" to R.string.cat_movies,
    "informational" to R.string.cat_informational,
    "entertaining" to R.string.cat_entertaining,
    "educational" to R.string.cat_educational,
    "documentaries" to R.string.cat_documentaries,
    "music" to R.string.cat_music,
    "kids" to R.string.cat_kids,
    "series" to R.string.cat_series,
    "regional" to R.string.cat_regional,
    "other" to R.string.cat_other,
    "playlist" to R.string.cat_playlist,
)

@Composable
fun categoryLabel(category: String): String = when {
    category.isBlank() -> stringResource(R.string.uncategorized)
    else -> CATEGORY_LABELS[category]?.let { stringResource(it) } ?: category.replaceFirstChar { it.uppercase() }
}

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
    val context = LocalContext.current
    val channels = model.shownChannels
    var category by rememberSaveable { mutableStateOf(model.prefs.lastCategory ?: ALL) }
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
    val firstFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Back from a channel: the list returns to it; otherwise to the category last chosen.
    val returnIndex = remember(visible) { visible.indexOfFirst { it.key == model.lastChannelKey } }
    LaunchedEffect(model.loadState) {
        if (model.loadState != LoadState.Ready) return@LaunchedEffect
        if (returnIndex >= 0) {
            listState.scrollToItem(returnIndex)
            withFrameNanos { }
            if (runCatching { channelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        runCatching { firstFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().then(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // Categories
        Column(Modifier.width(270.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
            Text(
                text = if (model.loadState == LoadState.Loading) stringResource(R.string.loading_channels)
                else stringResource(R.string.channels_count, channels.size),
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
                            ALL -> stringResource(R.string.all_channels)
                            FAVORITES -> stringResource(R.string.favorites)
                            else -> categoryLabel(entry)
                        },
                        count = count,
                        selected = entry == category,
                        onFocused = {
                            category = entry
                            model.prefs.lastCategory = entry
                        },
                        modifier = if (entry == category) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                PillButton(stringResource(R.string.refresh), onReload)
                PillButton(stringResource(R.string.settings), onSettings)
            }
        }

        // Channels
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (val state = model.loadState) {
                LoadState.Loading -> CenterMessage(stringResource(R.string.loading_channels))
                is LoadState.Failed -> FailedState(
                    reason = state.reason,
                    onRetry = onReload,
                    onInstall = {
                        Engine.installIntent().firstOrNull { runCatching { context.startActivity(it) }.isSuccess }
                    },
                )
                LoadState.Ready -> if (visible.isEmpty()) {
                    CenterMessage(stringResource(R.string.no_channels))
                } else {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visible.size, key = { visible[it].key }) { index ->
                            val channel = visible[index]
                            ChannelRow(
                                channel = channel,
                                now = now,
                                tmdbKey = model.prefs.tmdbKey,
                                isFavorite = channel.key in model.favorites,
                                onClick = { onPlay(visible, index) },
                                onLongClick = { model.toggleFavorite(channel) },
                                modifier = if (index == returnIndex) Modifier.focusRequester(channelFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(label: String, count: Int, selected: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    FocusCard(
        onClick = onFocused,
        selected = selected,
        modifier = modifier.fillMaxWidth().onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) Color.Black.copy(alpha = 0.55f) else Palette.TextTertiary,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    now: Long,
    tmdbKey: String,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val secondary = if (focused) Color.Black.copy(alpha = 0.6f) else Palette.TextSecondary
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(channel.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val programme = channel.programmeAt(now)
                if (programme != null) {
                    Text(HebrewTitles.of(programme.title, tmdbKey), style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val progress = (now - programme.startMs).toFloat() / (programme.stopMs - programme.startMs).coerceAtLeast(1)
                    ProgressBar(progress, Modifier.padding(top = 4.dp).widthIn(max = 180.dp).fillMaxWidth(), height = 3.dp,
                        fill = if (focused) Color.Black else Palette.TextPrimary)
                } else {
                    Text(categoryLabel(channel.category), style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1)
                }
            }
            val details = buildList {
                add(stringResource(R.string.streams, channel.infohashes.size))
                if (channel.availability >= 0f) add(stringResource(R.string.seeded, (channel.availability * 100).toInt()))
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1)
            HealthDot(channel.health, Modifier.padding(start = 10.dp))
            if (isFavorite) Text("★", color = if (focused) Color.Black else Palette.Amber, modifier = Modifier.padding(start = 10.dp))
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
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
    }
}

@Composable
private fun FailedState(reason: AceException.Reason, onRetry: () -> Unit, onInstall: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(reason) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp)) {
            if (reason == AceException.Reason.NotInstalled) {
                Text(stringResource(R.string.engine_missing_title), style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.engine_missing_text), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(R.string.install_engine), onInstall, Modifier.focusRequester(focus))
                    PillButton(stringResource(R.string.retry), onRetry)
                }
            } else {
                Text(
                    stringResource(if (reason == AceException.Reason.EngineFailed) R.string.engine_failed else R.string.catalog_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Error,
                )
                Spacer(Modifier.height(20.dp))
                PillButton(stringResource(R.string.retry), onRetry, Modifier.focusRequester(focus))
            }
        }
    }
}
