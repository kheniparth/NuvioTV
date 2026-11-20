@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvEpgProgram
import com.nuvio.tv.livetv.data.LiveTvPlayable
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val GUIDE_ALL_KEY = "all"
private val GuideRowHeight = 76.dp
private val GuideRulerHeight = 30.dp
private val GuideTimelineWidth = (GUIDE_WINDOW_MILLIS / 60_000f * GUIDE_DP_PER_MINUTE).dp

data class LiveTvGuideUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val channels: List<LiveTvChannel> = emptyList(),
    val programs: Map<String, List<LiveTvEpgProgram>> = emptyMap(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val nowMillis: Long = System.currentTimeMillis(),
)

@HiltViewModel
class LiveTvGuideViewModel @Inject constructor(
    private val repository: LiveTvRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveTvGuideUiState())
    val state: StateFlow<LiveTvGuideUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val now = System.currentTimeMillis()
            try {
                val channels = repository.channels()
                val programs = repository.epgForChannels(channels, now)
                _state.update {
                    it.copy(
                        loading = false,
                        channels = channels,
                        programs = programs,
                        categories = repository.categories(channels),
                        nowMillis = now,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun selectCategory(category: String?) {
        _state.update { it.copy(selectedCategory = category) }
    }

    suspend fun playableFor(channel: LiveTvChannel): LiveTvPlayable? = try {
        repository.resolvePlayableUrl(channel)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }
}

@Composable
fun GuideScreen(
    onPlayChannel: (LiveTvChannel, LiveTvPlayable) -> Unit,
    viewModel: LiveTvGuideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val playback = rememberLiveTvPlayback()
    val onChannelClick: (LiveTvChannel) -> Unit = { channel ->
        scope.launch {
            viewModel.playableFor(channel)?.let { playable ->
                playback.noteReturnChannel(channel.id)
                onPlayChannel(channel, playable)
            }
        }
    }
    GuideContent(
        state = state,
        onCategorySelected = viewModel::selectCategory,
        onChannelClick = onChannelClick,
    )
}

@Composable
private fun GuideContent(
    state: LiveTvGuideUiState,
    onCategorySelected: (String?) -> Unit,
    onChannelClick: (LiveTvChannel) -> Unit,
) {
    val channels = remember(state.channels, state.selectedCategory) {
        state.channels.filter { state.selectedCategory == null || it.group?.trim() == state.selectedCategory }
    }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val categoryWidth = guideCategoryWidthDp(screenWidthDp).dp
    val channelColumnWidth = guideChannelColumnWidthDp(screenWidthDp).dp
    val playback = rememberLiveTvPlayback()
    val returnId = remember { playback.returnChannelId }
    val nowMillis = rememberGuideNowMillis()
    val windowStart = remember(state.nowMillis) { guideWindowStart(state.nowMillis) }
    val timelineScroll = rememberScrollState()
    val previewViewModel: LiveTvPreviewViewModel = hiltViewModel()
    val focus = rememberLiveTvColumnFocus()
    LiveTvColumnBack(focus)
    val selectedKey = state.selectedCategory ?: GUIDE_ALL_KEY
    val entries = listOf(LiveTvCategoryEntry(GUIDE_ALL_KEY, stringResource(R.string.livetv_guide_all))) +
        state.categories.map { LiveTvCategoryEntry(it, it) }

    var previewChannel by remember { mutableStateOf<LiveTvChannel?>(null) }
    val listState = rememberLazyListState()
    val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key.toString() }
    val returnIndex = if (returnId == null) -1 else channels.indexOfFirst { it.id == returnId }
    val restoreId = returnId?.takeIf { returnIndex >= 0 }
        ?: focus.restoreKey(selectedKey, visibleKeys)
        ?: channels.firstOrNull()?.id
    LiveTvRestorePlayedChannel(
        channelId = returnId,
        rowIndex = returnIndex,
        listReady = channels.isNotEmpty() || !state.loading,
        listState = listState,
        focus = focus,
        onDone = playback::clearReturnChannel,
    )
    val displayChannel = previewChannel ?: channels.firstOrNull()
    val previewPrograms = displayChannel?.let { state.programs[it.id] }.orEmpty()
    val nowProgram = previewPrograms.firstOrNull { it.isAiringAt(nowMillis) }
    val nextTitle = previewPrograms.firstOrNull { it.startMillis > nowMillis }?.title

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        LiveTvCategoryColumn(
            entries = entries,
            selectedKey = selectedKey,
            onSelect = { key -> onCategorySelected(key.takeUnless { it == GUIDE_ALL_KEY }) },
            focus = focus,
            focusCategoryOnEnter = returnId == null,
            modifier = Modifier.width(categoryWidth),
        )
        Column(
            modifier = Modifier
                .weight(0.78f)
                .fillMaxHeight()
                .liveTvReturnToCategories(focus),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (displayChannel != null) {
                LiveTvPreviewPanel(
                    focusedChannel = previewChannel,
                    displayChannel = displayChannel,
                    nowTitle = nowProgram?.title,
                    nextTitle = nextTitle,
                    timeRange = nowProgram?.let {
                        formatLiveTvClockRange(it.startMillis, it.endMillis, ZoneId.systemDefault())
                    },
                    description = nowProgram?.description,
                    live = nowProgram != null,
                    scrollInProgress = listState.isScrollInProgress,
                    viewModel = previewViewModel,
                )
            }
            GuideTimeRuler(
                windowStart = windowStart,
                nowMillis = nowMillis,
                scrollState = timelineScroll,
                channelColumnWidth = channelColumnWidth,
            )
            when {
                state.loading && state.channels.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator() }
                state.failed && state.channels.isEmpty() -> EmptyScreenState(
                    title = stringResource(R.string.livetv_guide_load_failed),
                    modifier = Modifier.weight(1f),
                )
                channels.isEmpty() -> EmptyScreenState(
                    title = stringResource(R.string.livetv_guide_empty),
                    modifier = Modifier.weight(1f),
                )
                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                ) {
                    items(channels, key = { it.id }) { channel ->
                        GuideRow(
                            channel = channel,
                            programmes = state.programs[channel.id].orEmpty(),
                            windowStart = windowStart,
                            nowMillis = nowMillis,
                            scrollState = timelineScroll,
                            channelColumnWidth = channelColumnWidth,
                            focusRequester = focus.listRequester.takeIf { channel.id == restoreId },
                            onClick = {
                                previewViewModel.stopForPlayback()
                                onChannelClick(channel)
                            },
                            onFocusChanged = { focused ->
                                if (focused) focus.onListItemFocused(selectedKey, channel.id)
                                if (focused) previewChannel = channel
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberGuideNowMillis(): Long {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L - nowMillis % 30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    return nowMillis
}

@Composable
private fun GuideTimeRuler(
    windowStart: Long,
    nowMillis: Long,
    scrollState: ScrollState,
    channelColumnWidth: Dp,
) {
    val format = rememberGuideTimeFormat()
    Row(modifier = Modifier.fillMaxWidth().height(GuideRulerHeight)) {
        Spacer(Modifier.width(channelColumnWidth))
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scrollState)) {
            Box(Modifier.width(GuideTimelineWidth).fillMaxHeight()) {
                repeat((GUIDE_WINDOW_MILLIS / GUIDE_SLOT_MILLIS).toInt()) { index ->
                    val slotStart = windowStart + index * GUIDE_SLOT_MILLIS
                    Text(
                        text = formatGuideTime(format, slotStart),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier
                            .offset(x = millisToDp(slotStart - windowStart))
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp),
                    )
                }
                GuideNowLine(windowStart = windowStart, nowMillis = nowMillis)
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveTvChannel,
    programmes: List<LiveTvEpgProgram>,
    windowStart: Long,
    nowMillis: Long,
    scrollState: ScrollState,
    channelColumnWidth: Dp,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val format = rememberGuideTimeFormat()
    val cells = remember(programmes, windowStart) {
        programmes.mapNotNull { programme ->
            guideProgrammeSpan(programme.startMillis, programme.endMillis, windowStart)
                ?.let { programme to it }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GuideRowHeight)
            .padding(vertical = 2.dp)
            .focusGroup()
            .onFocusChanged { onFocusChanged(it.hasFocus || it.isFocused) },
    ) {
        GuideChannelCell(
            channel = channel,
            columnWidth = channelColumnWidth,
            onClick = onClick,
            focusRequester = focusRequester,
        )
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scrollState)) {
            // shortcut: a row composes only the programmes inside the bounded window; make it a LazyRow if the window grows.
            Box(Modifier.width(GuideTimelineWidth).fillMaxHeight()) {
                cells.forEach { (programme, span) ->
                    GuideProgrammeCell(
                        programme = programme,
                        span = span,
                        timeLabel = "${formatGuideTime(format, programme.startMillis)} - ${formatGuideTime(format, programme.endMillis)}",
                        onClick = onClick,
                    )
                }
                GuideNowLine(windowStart = windowStart, nowMillis = nowMillis)
            }
        }
    }
}

@Composable
private fun GuideChannelCell(
    channel: LiveTvChannel,
    columnWidth: Dp,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val base = Modifier.width(columnWidth).fillMaxHeight()
    Surface(
        onClick = onClick,
        modifier = if (focusRequester != null) base.focusRequester(focusRequester) else base,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
        ),
        border = ClickableSurfaceDefaults.border(
            border = guideBorder(NuvioTheme.colors.Border),
            focusedBorder = guideBorder(NuvioTheme.colors.FocusRing),
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 26.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(NuvioTheme.colors.Background),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = channel.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GuideProgrammeCell(
    programme: LiveTvEpgProgram,
    span: GuideProgrammeSpan,
    timeLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .offset(x = span.xDp.dp)
            .width(span.widthDp.dp)
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 1.dp),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
        ),
        border = ClickableSurfaceDefaults.border(
            border = guideBorder(NuvioTheme.colors.Border),
            focusedBorder = guideBorder(NuvioTheme.colors.FocusRing),
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.xs)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = programme.title,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BoxScope.GuideNowLine(windowStart: Long, nowMillis: Long) {
    if (nowMillis < windowStart || nowMillis >= windowStart + GUIDE_WINDOW_MILLIS) return
    Box(
        modifier = Modifier
            .offset(x = millisToDp(nowMillis - windowStart))
            .width(2.dp)
            .fillMaxHeight()
            .background(NuvioTheme.colors.FocusRing),
    )
}

@Composable
private fun guideBorder(color: androidx.compose.ui.graphics.Color): Border =
    Border(
        border = BorderStroke(NuvioTheme.spacing.hairline, color),
        shape = RoundedCornerShape(NuvioTheme.radii.xs),
    )

private fun millisToDp(millis: Long): Dp = (millis / 60_000f * GUIDE_DP_PER_MINUTE).dp

@Composable
private fun rememberGuideTimeFormat(): SimpleDateFormat =
    remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

private fun formatGuideTime(format: SimpleDateFormat, millis: Long): String =
    format.format(Date(millis))
