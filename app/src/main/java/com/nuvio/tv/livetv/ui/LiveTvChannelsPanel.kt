package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvChannelsPanel(
    onChannelSelected: (LiveTvChannel) -> Unit,
    modifier: Modifier = Modifier,
    playback: LiveTvPlaybackSession = rememberLiveTvPlayback(),
) {
    val channels = playback.inCategory()
    val currentId = playback.currentId
    val title = when (val category = playback.category) {
        ChannelCategory.All -> stringResource(R.string.livetv_category_all)
        ChannelCategory.Favourites -> stringResource(R.string.livetv_category_favourites)
        ChannelCategory.Recents -> stringResource(R.string.livetv_category_recents)
        is ChannelCategory.Group -> category.name
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val focusIndex = channels.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it }

    LaunchedEffect(channels, currentId) {
        if (channels.isEmpty()) return@LaunchedEffect
        runCatching {
            listState.scrollToItem(focusIndex)
            delay(32)
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(420.dp)
            .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
            .background(NuvioTheme.colors.BackgroundElevated)
            .padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
        )
        if (channels.isEmpty()) {
            Text(
                text = stringResource(R.string.livetv_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary,
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.lg),
            ) {
                itemsIndexed(channels, key = { index, channel -> "$index:${channel.id}" }) { index, channel ->
                    val current = channel.id == currentId
                    val shape = RoundedCornerShape(12.dp)
                    Surface(
                        onClick = { onChannelSelected(channel) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier),
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
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (current) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = NuvioTheme.colors.Primary,
                                )
                            }
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (current) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
