package com.nuvio.tv.livetv.ui

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.SportsGame
import com.nuvio.tv.livetv.data.SportsScoreboard
import com.nuvio.tv.livetv.data.SportsState
import com.nuvio.tv.livetv.data.matchSportsChannels
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal const val SPORTS_LOAD_TIMEOUT_MILLIS = 10_000L
private const val SPORTS_ALL_KEY = "all"

data class SportsRow(val game: SportsGame, val channels: List<LiveTvChannel>)

data class SportsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val rows: List<SportsRow> = emptyList(),
    val nowMillis: Long = 0L,
)

@HiltViewModel
class SportsViewModel(
    private val scores: SportsScoreboard,
    private val repository: LiveTvRepository,
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    @Inject
    constructor(scores: SportsScoreboard, repository: LiveTvRepository) :
        this(scores, repository, Dispatchers.Default)

    private val _uiState = MutableStateFlow(SportsUiState())
    val uiState: StateFlow<SportsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            try {
                val rows = withTimeout(SPORTS_LOAD_TIMEOUT_MILLIS) {
                    withContext(loadDispatcher) {
                        val games = scores.games(now)
                        val channels = repository.channels()
                        games.map { game -> SportsRow(game, matchSportsChannels(game, channels)) }
                    }
                }
                _uiState.value = SportsUiState(loading = false, rows = rows, nowMillis = now)
            } catch (timeout: TimeoutCancellationException) {
                Log.w(TAG, "Sports scores timed out after ${SPORTS_LOAD_TIMEOUT_MILLIS}ms")
                _uiState.value = SportsUiState(loading = false, nowMillis = now)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Sports scores failed", error)
                _uiState.value = SportsUiState(loading = false, error = error.message, nowMillis = now)
            }
        }
    }

    suspend fun playable(channel: LiveTvChannel) = repository.resolvePlayableUrl(channel)

    private companion object {
        const val TAG = "SportsViewModel"
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SportsScreen(
    onBackPress: () -> Unit,
    onPlay: (url: String, headers: Map<String, String>, title: String) -> Unit,
    viewModel: SportsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var previewChannel by remember { mutableStateOf<LiveTvChannel?>(null) }
    val listState = rememberLazyListState()
    val previewViewModel: LiveTvPreviewViewModel = hiltViewModel()
    val focus = rememberLiveTvColumnFocus()
    val playback = rememberLiveTvPlayback()
    val returnId = remember { playback.returnChannelId }
    LiveTvColumnBack(focus, onLeave = onBackPress)
    val allLabel = stringResource(R.string.livetv_category_all)
    var selectedLeague by rememberSaveable { mutableStateOf(SPORTS_ALL_KEY) }
    val leagues = remember(state.rows, allLabel) {
        listOf(LiveTvCategoryEntry(SPORTS_ALL_KEY, allLabel)) +
            state.rows.distinctBy { it.game.leagueId }
                .map { LiveTvCategoryEntry(it.game.leagueId, it.game.leagueLabel) }
    }
    val games = remember(state.rows, selectedLeague) {
        if (selectedLeague == SPORTS_ALL_KEY) state.rows else state.rows.filter { it.game.leagueId == selectedLeague }
    }
    val visibleGameKeys = listState.layoutInfo.visibleItemsInfo.map { it.key.toString() }
    val visibleChannelIds = games
        .filter { "${it.game.leagueId}:${it.game.id}" in visibleGameKeys }
        .flatMap { row -> row.channels.map { it.id } }
    val returnGameIndex = if (returnId == null) -1 else games.indexOfFirst { row -> row.channels.any { it.id == returnId } }
    val restoreChannelId = returnId?.takeIf { returnGameIndex >= 0 }
        ?: focus.restoreKey(selectedLeague, visibleChannelIds)
        ?: games.firstNotNullOfOrNull { it.channels.firstOrNull()?.id }
    LiveTvRestorePlayedChannel(
        channelId = returnId,
        rowIndex = returnGameIndex,
        listReady = games.isNotEmpty() || !state.loading,
        listState = listState,
        focus = focus,
        onDone = playback::clearReturnChannel,
    )
    val restoreGameKey = games.firstOrNull { row -> row.channels.any { it.id == restoreChannelId } }
        ?.let { "${it.game.leagueId}:${it.game.id}" }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
    ) {
        LiveTvCategoryColumn(
            entries = leagues,
            selectedKey = selectedLeague,
            onSelect = { selectedLeague = it },
            focus = focus,
            focusCategoryOnEnter = returnId == null,
            modifier = Modifier.weight(0.2f),
        )
        Column(
            modifier = Modifier
                .weight(0.8f)
                .fillMaxHeight()
                .liveTvReturnToCategories(focus),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.livetv_sports_title),
                style = NuvioTheme.textStyles.headline,
                color = NuvioTheme.colors.TextPrimary,
            )
            val message = playError ?: state.error
            if (message != null) {
                Text(text = message, color = NuvioTheme.colors.Error)
            }
            when {
                state.loading -> Text(text = stringResource(R.string.livetv_sports_loading), color = NuvioTheme.colors.TextSecondary)
                games.isEmpty() && state.error == null ->
                    Text(text = stringResource(R.string.livetv_sports_empty), color = NuvioTheme.colors.TextSecondary)
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(games, key = { "${it.game.leagueId}:${it.game.id}" }) { row ->
                        SportsGameBlock(
                            row = row,
                            nowMillis = state.nowMillis,
                            restoreChannelId = restoreChannelId,
                            restoreFocus = "${row.game.leagueId}:${row.game.id}" == restoreGameKey,
                            listRequester = focus.listRequester,
                            onChannel = { channel ->
                                if (playing) return@SportsGameBlock
                                previewViewModel.stopForPlayback()
                                playing = true
                                scope.launch {
                                    try {
                                        val playable = viewModel.playable(channel)
                                        playback.noteReturnChannel(channel.id)
                                        onPlay(playable.url, playable.headers, channel.name)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        playing = false
                                        playError = error.message
                                    }
                                }
                            },
                            onChannelFocus = { channel, focused ->
                                if (focused) focus.onListItemFocused(selectedLeague, channel.id)
                                if (focused) previewChannel = channel
                            },
                        )
                    }
                }
            }
        }
        LiveTvPreviewPanel(
            focusedChannel = previewChannel,
            scrollInProgress = listState.isScrollInProgress,
            modifier = Modifier.width(LiveTvPreviewPanelWidth),
            viewModel = previewViewModel,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SportsGameBlock(
    row: SportsRow,
    nowMillis: Long,
    restoreChannelId: String?,
    restoreFocus: Boolean,
    listRequester: FocusRequester,
    onChannel: (LiveTvChannel) -> Unit,
    onChannelFocus: (LiveTvChannel, Boolean) -> Unit,
) {
    val game = row.game
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = game.leagueLabel,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sportsStatus(game, nowMillis),
                color = if (game.state == SportsState.Live) NuvioTheme.colors.Error else NuvioTheme.colors.TextPrimary,
                maxLines = 1,
            )
        }
        if (game.sides.size >= 2) {
            game.sides.forEach { side ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = side.name,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (game.state != SportsState.Upcoming && !side.score.isNullOrBlank()) {
                        Text(text = side.score, color = NuvioTheme.colors.TextPrimary)
                    }
                }
            }
        } else {
            Text(text = game.name, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.channels.isEmpty()) {
            if (game.state != SportsState.Finished) {
                Text(
                    text = stringResource(R.string.livetv_sports_no_channels),
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.channels.forEach { channel ->
                    Button(
                        onClick = { onChannel(channel) },
                        modifier = Modifier
                            .then(
                                if (restoreFocus && channel.id == restoreChannelId) {
                                    Modifier.focusRequester(listRequester)
                                } else {
                                    Modifier
                                },
                            )
                            .onFocusChanged { onChannelFocus(channel, it.isFocused) },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary,
                        ),
                    ) {
                        Text(text = channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun sportsStatus(game: SportsGame, nowMillis: Long): String = when (game.state) {
    SportsState.Live -> {
        val badge = stringResource(R.string.livetv_sports_live)
        if (game.statusDetail.isBlank()) badge else "$badge ${game.statusDetail}"
    }
    SportsState.Upcoming -> formatSportsStart(game.startMillis, nowMillis)
    SportsState.Finished -> game.statusDetail.ifBlank { formatSportsStart(game.startMillis, nowMillis) }
}

private fun formatSportsStart(startMillis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val time = start.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (start.toLocalDate() == today) time else "${start.format(DateTimeFormatter.ofPattern("dd/MM"))} $time"
}
