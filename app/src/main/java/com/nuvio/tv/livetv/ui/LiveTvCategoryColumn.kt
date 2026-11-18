package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.first

internal const val LIVE_TV_CATEGORY_FOCUS_DELAY_MILLIS = 300L

data class LiveTvCategoryEntry(val key: String, val label: String)

/**
 * Item to focus when leaving the category column to the right.
 * The last focused item in this category when it is still on screen, otherwise the first visible one.
 */
internal fun liveTvListRestoreKey(
    categoryKey: String,
    lastFocusedByCategory: Map<String, String>,
    visibleKeys: List<String>,
): String? {
    val remembered = lastFocusedByCategory[categoryKey]
    if (remembered != null && remembered in visibleKeys) return remembered
    return visibleKeys.firstOrNull()
}

internal class LiveTvColumnFocus(
    val categoryRequester: FocusRequester,
    val listRequester: FocusRequester,
) {
    var inList by mutableStateOf(false)
    var categoryRequest by mutableIntStateOf(0)
        private set
    private val lastFocused = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun focusCategory() {
        categoryRequest += 1
    }

    fun onListItemFocused(categoryKey: String, itemKey: String) {
        lastFocused[categoryKey] = itemKey
    }

    fun restoreKey(categoryKey: String, visibleKeys: List<String>): String? =
        liveTvListRestoreKey(categoryKey, lastFocused, visibleKeys)

    fun focusListItem(): Boolean = runCatching { listRequester.requestFocus() }.isSuccess
}

@Composable
internal fun rememberLiveTvColumnFocus(): LiveTvColumnFocus {
    val categoryRequester = remember { FocusRequester() }
    val listRequester = remember { FocusRequester() }
    return remember(categoryRequester, listRequester) { LiveTvColumnFocus(categoryRequester, listRequester) }
}

/** Back from the list returns to the selected category. Back from the categories leaves when [onLeave] is set. */
@Composable
internal fun LiveTvColumnBack(
    focus: LiveTvColumnFocus,
    onLeave: (() -> Unit)? = null,
) {
    BackHandler(enabled = focus.inList || onLeave != null) {
        if (focus.inList) focus.focusCategory() else onLeave?.invoke()
    }
}

/** Left (right in RTL) from the list column focuses the selected category. */
internal fun Modifier.liveTvReturnToCategories(focus: LiveTvColumnFocus): Modifier = composed {
    val towardCategories = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        Key.DirectionRight
    } else {
        Key.DirectionLeft
    }
    onFocusChanged { focus.inList = it.hasFocus }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != towardCategories) return@onPreviewKeyEvent false
            focus.focusCategory()
            true
        }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveTvCategoryColumn(
    entries: List<LiveTvCategoryEntry>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    focus: LiveTvColumnFocus,
    modifier: Modifier = Modifier,
    header: @Composable (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val openListKey = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    var pendingKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingKey) {
        val key = pendingKey ?: return@LaunchedEffect
        kotlinx.coroutines.delay(LIVE_TV_CATEGORY_FOCUS_DELAY_MILLIS)
        if (key != selectedKey) onSelect(key)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { entries.isNotEmpty() }.first { it }
        focus.focusCategory()
    }

    LaunchedEffect(focus.categoryRequest) {
        if (focus.categoryRequest == 0 || entries.isEmpty()) return@LaunchedEffect
        val index = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        listState.scrollToItem(index)
        repeat(2) { withFrameNanos { } }
        runCatching { focus.categoryRequester.requestFocus() }
    }

    Column(modifier = modifier.fillMaxHeight()) {
        if (header != null) {
            Column(modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)) { header() }
        }
        val selectedIndex = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != openListKey) return@onPreviewKeyEvent false
                    focus.focusListItem()
                },
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                CategoryEntry(
                    label = entry.label,
                    selected = entry.key == selectedKey,
                    modifier = if (index == selectedIndex) Modifier.focusRequester(focus.categoryRequester) else Modifier,
                    onClick = {
                        pendingKey = null
                        onSelect(entry.key)
                    },
                    onFocused = { pendingKey = entry.key },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryEntry(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.BackgroundCard else NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    NuvioTheme.spacing.hairline,
                    if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.Border,
                ),
                shape = shape,
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
