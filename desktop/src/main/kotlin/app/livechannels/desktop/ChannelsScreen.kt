package app.livechannels.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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

/** Whether [channel] matches what was typed: its name, the programme on now, or its category. */
private fun Channel.matches(query: String, now: Long): Boolean {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val haystack = buildString {
        append(name.lowercase()).append(' ')
        programmeAt(now)?.let { append(it.title.lowercase()).append(' ') }
        append(category.lowercase()).append(' ').append(Strings.category(category).lowercase())
    }
    return words.all { it in haystack }
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
    var query by remember { mutableStateOf("") }
    val categories = remember(channels) {
        channels.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            .filter { it != ALL && it != FAVORITES }
    }
    val entries = listOf(ALL, FAVORITES) + categories
    val now = rememberClock()
    val visible = remember(channels, category, model.favorites, query) {
        when {
            // A search looks through every channel, whatever category is chosen.
            query.isNotBlank() -> channels.filter { it.matches(query, now) }
            category == ALL -> channels
            category == FAVORITES -> channels.filter { it.key in model.favorites }
            else -> channels.filter { it.category == category }
        }
    }
    val listState = rememberLazyListState()
    val categoryState = rememberLazyListState()
    // The keyboard's place: the categories or the channels, and the row chosen in each.
    var inCategories by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(0) }
    // Back from a channel: the list returns to it.
    LaunchedEffect(model.loadState, visible) {
        if (model.loadState != LoadState.Ready) return@LaunchedEffect
        val back = visible.indexOfFirst { it.key == model.lastChannelKey }
        selected = if (back >= 0) back else 0
        if (back >= 0) listState.scrollToItem(back)
    }
    LaunchedEffect(selected, inCategories) {
        if (inCategories) return@LaunchedEffect
        val shown = listState.layoutInfo.visibleItemsInfo
        if (shown.isNotEmpty() && (selected <= shown.first().index || selected >= shown.last().index)) {
            listState.animateScrollToItem((selected - 3).coerceAtLeast(0))
        }
    }
    LaunchedEffect(category, inCategories) {
        if (inCategories) categoryState.animateScrollToItem((entries.indexOf(category) - 3).coerceAtLeast(0))
    }
    val search = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { search.requestFocus() } }

    fun chooseCategory(entry: String) {
        category = entry
        model.prefs.lastCategory = entry
        query = ""
        selected = 0
    }

    // Arrows move in the list, Enter plays, Esc clears the search; letters go to the search box.
    val keys: (KeyEvent) -> Boolean = keys@{ event ->
        if (event.type != KeyEventType.KeyDown) return@keys false
        val towardCategories = if (Strings.isHebrew) Key.DirectionRight else Key.DirectionLeft
        val towardChannels = if (Strings.isHebrew) Key.DirectionLeft else Key.DirectionRight
        fun move(step: Int) {
            if (inCategories) {
                val at = (entries.indexOf(category) + step).coerceIn(0, entries.lastIndex)
                chooseCategory(entries[at])
            } else if (visible.isNotEmpty()) {
                selected = (selected + step).coerceIn(0, visible.lastIndex)
            }
        }
        when {
            event.key == Key.DirectionDown -> { move(1); true }
            event.key == Key.DirectionUp -> { move(-1); true }
            event.key == Key.PageDown -> { move(10); true }
            event.key == Key.PageUp -> { move(-10); true }
            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                if (inCategories) inCategories = false
                else visible.getOrNull(selected)?.let { onPlay(visible, selected) }
                true
            }
            event.key == Key.Escape -> {
                if (query.isNotEmpty()) query = "" else inCategories = false
                true
            }
            event.key == towardCategories && query.isEmpty() -> { inCategories = true; true }
            event.key == towardChannels && query.isEmpty() -> { inCategories = false; true }
            event.key == Key.F5 -> { onReload(); true }
            event.isCtrlPressed && event.key == Key.D -> {
                visible.getOrNull(selected)?.let(model::toggleFavorite)
                true
            }
            event.isCtrlPressed && event.key == Key.F -> { runCatching { search.requestFocus() }; true }
            else -> false
        }
    }
    SideEffect { Keys.handler = keys }

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
            LazyColumn(Modifier.weight(1f), state = categoryState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        selected = entry == category && query.isBlank(),
                        active = inCategories && entry == category,
                        onClick = {
                            chooseCategory(entry)
                            inCategories = false
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                PillButton(Strings["refresh"], onReload)
                PillButton(Strings["settings"], onSettings)
            }
        }

        // Search and channels
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchBox(query, { query = it; selected = 0; inCategories = false }, Modifier.focusRequester(search))
            Text(
                listOfNotNull(
                    if (query.isBlank()) when (category) {
                        ALL -> Strings["all_channels"]
                        FAVORITES -> Strings["favorites"]
                        else -> Strings.category(category)
                    } else null,
                    Strings["channels_count", visible.size],
                    Strings["keys_hint"],
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val state = model.loadState) {
                    LoadState.Loading -> CenterMessage(Strings["loading_channels"])
                    is LoadState.Failed -> FailedState(state.reason, onRetry = onReload)
                    LoadState.Ready -> if (visible.isEmpty()) {
                        CenterMessage(Strings[if (query.isBlank()) "no_channels" else "no_results"])
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
                        ) {
                            items(visible.size, key = { visible[it].key }) { index ->
                                val channel = visible[index]
                                ChannelRow(
                                    channel = channel,
                                    now = now,
                                    tmdbKey = model.prefs.tmdbKey,
                                    isFavorite = channel.key in model.favorites,
                                    isLast = channel.key == model.lastChannelKey,
                                    active = !inCategories && index == selected,
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

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(Strings["search_hint"], color = Palette.TextTertiary) },
        leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary) },
        trailingIcon = if (query.isNotEmpty()) {
            { Text("✕", color = Palette.TextSecondary, modifier = Modifier.clickable { onChange("") }.padding(8.dp)) }
        } else null,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.TextPrimary,
            unfocusedTextColor = Palette.TextPrimary,
            focusedContainerColor = Palette.Elevated,
            unfocusedContainerColor = Palette.Elevated,
            focusedBorderColor = Palette.TextSecondary,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = Palette.TextPrimary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CategoryRow(label: String, count: Int, selected: Boolean, active: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = onClick, selected = selected, active = active, modifier = Modifier.fillMaxWidth()) { highlighted ->
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
    active: Boolean,
    onClick: () -> Unit,
    onRightClick: () -> Unit,
) {
    FocusCard(onClick = onClick, onRightClick = onRightClick, selected = isLast, active = active, modifier = Modifier.fillMaxWidth()) { highlighted ->
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
