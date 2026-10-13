package com.ikunshare.music.ui.screens.search

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ikunshare.music.platform.base.SearchType
import com.ikunshare.music.R
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.ui.components.GlassButton
import com.ikunshare.music.ui.components.GlassIconButton
import com.ikunshare.music.ui.components.GlassSectionHeading
import com.ikunshare.music.ui.components.liquidGlass
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import com.ikunshare.music.ui.utils.LocalScrollOffsetReporter
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    currentProvider: String,
    availableProviders: List<String>,
    onProviderSwitch: (String) -> Unit,
    searchType: SearchType,
    supportedSearchTypes: List<SearchType>,
    onSearchTypeSwitch: (SearchType) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onOpenDiscovery: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    val compact = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && WindowInsets.isImeVisible
    val fieldDescription = stringResource(R.string.search_placeholder)
    fun submit() {
        if (query.isBlank()) return
        onSearch()
        focusManager.clearFocus()
        keyboard?.hide()
    }
    Column(
        Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().liquidGlass(Capsule()).heightIn(min = 60.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onOpenDiscovery != null) GlassIconButton(onClick = onOpenDiscovery) {
                Icon(Icons.Filled.Explore, "发现歌单与排行榜")
            } else Icon(Icons.Filled.Search, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .semantics { contentDescription = fieldDescription },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(fieldDescription,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        field()
                    }
                }
            )
            if (query.isNotEmpty()) {
                GlassIconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, stringResource(R.string.clear_search))
                }
            }
            GlassIconButton(onClick = ::submit, enabled = query.isNotBlank()) {
                Icon(Icons.Filled.Search, stringResource(R.string.nav_search),
                    tint = MaterialTheme.colorScheme.tertiary.copy(alpha = if (query.isBlank()) 0.38f else 1f))
            }
        }
        // Give landscape typing room; filters return when the keyboard closes.
        if (!compact) {
            if (availableProviders.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableProviders.forEach { source ->
                        SearchChoiceChip(
                            label = MusicApplication.instance?.musicRepository?.getProvider(source)?.displayName ?: source,
                            selected = source == currentProvider,
                            onClick = {
                                focusManager.clearFocus()
                                keyboard?.hide()
                                onProviderSwitch(source)
                            }
                        )
                    }
                }
            }
            if (supportedSearchTypes.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    supportedSearchTypes.forEach { type ->
                        SearchChoiceChip(type.displayName, type == searchType) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onSearchTypeSwitch(type)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) bringIntoView.bringIntoView()
    }
    GlassButton(onClick, accented = selected, height = 44.dp,
        modifier = Modifier.bringIntoViewRequester(bringIntoView).semantics { this.selected = selected }) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
internal fun SearchSuggestionsList(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier.fillMaxWidth().heightIn(max = 240.dp).liquidGlass().padding(vertical = 6.dp)
    ) {
        items(suggestions.distinct(), key = { it }) { suggestion ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onSuggestionClick(suggestion) }
                    .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(suggestion, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HotSearchSection(
    hotSearches: List<String>,
    onHotSearchClick: (String) -> Unit,
    searchHistory: List<String>,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberLazyListState()
    val reporter = LocalScrollOffsetReporter.current
    val bottomReserve = LocalFloatingBottomBarReserve.current
    LaunchedEffect(scrollState, reporter) {
        var previousIndex = 0
        var previousOffset = 0
        snapshotFlow { scrollState.firstVisibleItemIndex to scrollState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                reporter?.invoke(index, offset, index < previousIndex || (index == previousIndex && offset < previousOffset))
                previousIndex = index
                previousOffset = offset
            }
    }
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 2
    val hotSearchRows = remember(hotSearches, columns) { hotSearches.chunked(columns) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = scrollState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomReserve + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (searchHistory.isNotEmpty()) {
            item(key = "history_heading", contentType = "heading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_history), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    GlassButton(onClearHistory, height = 44.dp) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.clear), maxLines = 1)
                    }
                }
                }
            item(key = "history_tags", contentType = "history") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    searchHistory.forEach { keyword ->
                        Row(
                            Modifier.widthIn(max = 280.dp).liquidGlass(Capsule()).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(keyword,
                                Modifier.weight(1f, fill = false).clickable(role = Role.Button) { onHistoryClick(keyword) }
                                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onRemoveHistory(keyword) }) {
                                Icon(Icons.Filled.Clear, stringResource(R.string.search_remove_history, keyword), Modifier.size(18.dp))
                            }
                        }
                    }
                }
                }
        }
        item(key = "hot_heading", contentType = "heading") {
            GlassSectionHeading(stringResource(R.string.hot_searches))
        }
        when {
            hotSearches.isNotEmpty() -> items(hotSearchRows.size, key = { "hot_row_$it" }, contentType = { "hot_row" }) { group ->
                val keywords = hotSearchRows[group]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    keywords.forEachIndexed { column, keyword ->
                        val rank = group * columns + column + 1
                        Row(
                            Modifier.weight(1f).liquidGlass(RoundedRectangle(20.dp))
                                .clickable(role = Role.Button) { onHotSearchClick(keyword) }
                                .heightIn(min = 76.dp).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(rank.toString().padStart(2, '0'), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (rank <= 3) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(keyword, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(columns - keywords.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            isLoading -> item(key = "hot_feedback", contentType = "feedback") {
                SearchFeedback(stringResource(R.string.loading), loading = true)
            }
            error != null -> item(key = "hot_feedback", contentType = "feedback") {
                SearchFeedback(stringResource(R.string.search_error_title), error,
                    icon = Icons.Outlined.ErrorOutline, onRetry = onRetry)
            }
            else -> item(key = "hot_feedback", contentType = "feedback") {
                SearchFeedback(stringResource(R.string.no_hot_search),
                    stringResource(R.string.search_ready_message), icon = Icons.Outlined.History)
            }
        }
    }
}

@Composable
internal fun SearchFeedback(
    title: String,
    message: String? = null,
    loading: Boolean = false,
    icon: ImageVector = Icons.Outlined.SearchOff,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.widthIn(max = 480.dp).fillMaxWidth().liquidGlass().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
        else Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (onRetry != null) GlassButton(onRetry, accented = true) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
internal fun SearchResultsHeading(query: String, type: SearchType, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$query · ${type.displayName}", Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.search_results_count, count), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchResultCard(
    title: String,
    subtitle: String,
    cover: String?,
    onClick: () -> Unit,
    source: String,
    artist: Boolean = false,
    album: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val sourceTag = MusicApplication.instance?.musicRepository?.getProvider(source)?.shortTag ?: source
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).liquidGlass()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick?.let { action ->
                { haptic.performHapticFeedback(HapticFeedbackType.LongPress); action() }
            }).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(64.dp).clip(if (artist) CircleShape else RoundedRectangle(16.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center) {
            Icon(if (artist) Icons.Filled.Person else if (album) Icons.Filled.Album else Icons.AutoMirrored.Filled.QueueMusic,
                null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.tertiary)
            if (!cover.isNullOrBlank()) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(sourceTag, subtitle).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
