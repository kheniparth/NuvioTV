package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LiveTv
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onPlay: (LiveTvPlayRequest) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.playRequests.collect(onPlay)
    }

    var previewChannel by remember { mutableStateOf<LiveTvChannel?>(null) }
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(NuvioTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.nav_live_tv),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
            )
            ChannelSearchField(state.query, viewModel::setQuery)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                items(state.categories, key = { it.key() }) { category ->
                    CategoryChip(
                        label = category.label(),
                        selected = category == state.selectedCategory,
                        onClick = { viewModel.selectCategory(category) },
                    )
                }
            }
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
                            onPlay = { viewModel.play(row.channel) },
                            onToggleFavorite = { viewModel.toggleFavorite(row.channel.id) },
                            onFocusChanged = { focused ->
                                previewChannel = when {
                                    focused -> row.channel
                                    previewChannel?.id == row.channel.id -> null
                                    else -> previewChannel
                                }
                            },
                        )
                    }
                }
            }
        }
        LiveTvPreviewOverlay(
            focusedChannel = previewChannel,
            scrollInProgress = listState.isScrollInProgress,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(NuvioTheme.spacing.lg),
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
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextPrimary,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelLine(
    row: ChannelRow,
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

@Composable
private fun ChannelLogo(channel: LiveTvChannel) {
    val shape = RoundedCornerShape(8.dp)
    if (channel.logoUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.LiveTv,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary,
            )
        }
    } else {
        AsyncImage(
            model = channel.logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(shape),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun guideLine(nowTitle: String?, nextTitle: String?): String? = when {
    nowTitle != null && nextTitle != null -> stringResource(R.string.livetv_now_next, nowTitle, nextTitle)
    nowTitle != null -> stringResource(R.string.livetv_now, nowTitle)
    nextTitle != null -> stringResource(R.string.livetv_next, nextTitle)
    else -> null
}

@Composable
private fun ChannelCategory.label(): String = when (this) {
    ChannelCategory.All -> stringResource(R.string.livetv_category_all)
    ChannelCategory.Favourites -> stringResource(R.string.livetv_category_favourites)
    is ChannelCategory.Group -> name
}

private fun ChannelCategory.key(): String = when (this) {
    ChannelCategory.All -> "all"
    ChannelCategory.Favourites -> "favourites"
    is ChannelCategory.Group -> "group:$name"
}
