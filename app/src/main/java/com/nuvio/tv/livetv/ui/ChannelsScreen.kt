package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onPlay: (LiveTvPlayRequest) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val previewViewModel: LiveTvPreviewViewModel = hiltViewModel()
    LaunchedEffect(viewModel, previewViewModel) {
        viewModel.playRequests.collect { request ->
            previewViewModel.stopForPlayback()
            onPlay(request)
        }
    }

    var previewRow by remember { mutableStateOf<ChannelRow?>(null) }
    val listState = rememberLazyListState()
    val focus = rememberLiveTvColumnFocus()
    LiveTvColumnBack(focus)
    val allLabel = stringResource(R.string.livetv_category_all)
    val favouritesLabel = stringResource(R.string.livetv_category_favourites)
    val recentsLabel = stringResource(R.string.livetv_category_recents)
    val entries = state.categories.map { category ->
        LiveTvCategoryEntry(category.key(), category.label(allLabel, favouritesLabel, recentsLabel))
    }
    val selectedKey = state.selectedCategory.key()
    val playback = rememberLiveTvPlayback()
    val returnId = remember { playback.returnChannelId }
    val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key.toString() }
    val returnIndex = if (returnId == null) -1 else state.rows.indexOfFirst { it.channel.id == returnId }
    val restoreId = returnId?.takeIf { returnIndex >= 0 }
        ?: focus.restoreKey(selectedKey, visibleKeys)
        ?: state.rows.firstOrNull()?.channel?.id
    LiveTvRestorePlayedChannel(
        channelId = returnId,
        rowIndex = returnIndex,
        listReady = state.rows.isNotEmpty() || !state.loading,
        listState = listState,
        focus = focus,
        onDone = playback::clearReturnChannel,
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(NuvioTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
    ) {
        LiveTvCategoryColumn(
            entries = entries,
            selectedKey = selectedKey,
            onSelect = { key -> state.categories.firstOrNull { it.key() == key }?.let(viewModel::selectCategory) },
            focus = focus,
            focusCategoryOnEnter = returnId == null,
            modifier = Modifier.weight(0.2f),
        )
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
                .liveTvReturnToCategories(focus),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.nav_live_tv),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
            )
            ChannelSearchField(state.query, viewModel::setQuery)
            state.error?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Primary)
            }
            when {
                state.loading -> Text(
                    text = stringResource(R.string.livetv_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary,
                )
                state.rows.isEmpty() -> Text(
                    text = stringResource(R.string.livetv_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary,
                )
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.lg),
                ) {
                    items(state.rows, key = { it.channel.id }) { row ->
                        ChannelLine(
                            row = row,
                            listRequester = focus.listRequester.takeIf { row.channel.id == restoreId },
                            onPlay = { viewModel.play(row.channel) },
                            onToggleFavorite = { viewModel.toggleFavorite(row.channel.id) },
                            onFocusChanged = { focused ->
                                if (focused) focus.onListItemFocused(selectedKey, row.channel.id)
                                if (focused) previewRow = row
                            },
                        )
                    }
                }
            }
        }
        LiveTvPreviewPanel(
            focusedChannel = previewRow?.channel,
            nowTitle = previewRow?.nowTitle,
            nextTitle = previewRow?.nextTitle,
            scrollInProgress = listState.isScrollInProgress,
            modifier = Modifier.weight(0.35f),
            viewModel = previewViewModel,
        )
    }
}

@Composable
private fun ChannelSearchField(value: String, onValueChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(
                border = if (focused) {
                    NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
                } else {
                    BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
                },
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    else -> null
                }
                if (direction != null && event.type == KeyEventType.KeyDown) {
                    keyboardController?.hide()
                    focusManager.moveFocus(direction)
                    true
                } else {
                    false
                }
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
        cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.livetv_search),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                }
                inner()
            }
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelLine(
    row: ChannelRow,
    listRequester: FocusRequester?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onPlay,
            modifier = Modifier
                .weight(1f)
                .then(if (listRequester != null) Modifier.focusRequester(listRequester) else Modifier)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = shape,
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = shape,
                ),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(row.channel)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val guide = guideLine(row.nowTitle, row.nextTitle)
                    if (guide != null) {
                        Text(
                            text = guide,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (row.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = stringResource(
                    if (row.favorite) R.string.livetv_unfavourite else R.string.livetv_favourite
                ),
                tint = NuvioTheme.colors.TextPrimary,
            )
        }
    }
}

private fun ChannelCategory.label(all: String, favourites: String, recents: String): String = when (this) {
    ChannelCategory.All -> all
    ChannelCategory.Favourites -> favourites
    ChannelCategory.Recents -> recents
    is ChannelCategory.Group -> name
}

private fun ChannelCategory.key(): String = when (this) {
    ChannelCategory.All -> "all"
    ChannelCategory.Favourites -> "favourites"
    ChannelCategory.Recents -> "recents"
    is ChannelCategory.Group -> "group:$name"
}
