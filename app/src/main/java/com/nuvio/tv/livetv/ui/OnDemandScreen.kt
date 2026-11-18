@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.livetv.data.LiveTvVodEpisode
import com.nuvio.tv.livetv.data.LiveTvVodItem
import com.nuvio.tv.livetv.data.LiveTvVodKind
import com.nuvio.tv.livetv.data.LiveTvVodPlayback
import com.nuvio.tv.livetv.data.filterLiveTvVodItems
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.launch

private const val VOD_ALL = "all"
private const val VOD_FAVORITES = "favorites"
private const val VOD_CATEGORY = "cat:"

@Composable
fun OnDemandScreen(
    onPlay: (LiveTvVodPlayback) -> Unit,
    onBackPress: () -> Unit,
    viewModel: LiveTvOnDemandViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val catalogState = uiState.catalog(uiState.kind)
    val catalog = catalogState.catalog
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategoryId by rememberSaveable(uiState.kind) { mutableStateOf<String?>(null) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<LiveTvVodItem?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = rememberLiveTvColumnFocus()
    val gridState = rememberLazyGridState()

    LaunchedEffect(selectedSeries?.id) { selectedSeries?.let(viewModel::loadEpisodes) }

    BackHandler {
        when {
            selectedSeries != null -> selectedSeries = null
            focus.inList -> focus.focusCategory()
            else -> onBackPress()
        }
    }

    fun startPlayback(block: suspend () -> LiveTvVodPlayback?) {
        scope.launch {
            val playback = block()
            if (playback == null) {
                Toast.makeText(context, R.string.livetv_on_demand_play_failed, Toast.LENGTH_SHORT).show()
            } else {
                onPlay(playback)
            }
        }
    }

    val visibleItems = remember(catalog.items, selectedCategoryId, searchQuery, favoritesOnly, uiState.favoriteIds) {
        filterLiveTvVodItems(catalog.items, selectedCategoryId, searchQuery)
            .filter { item -> !favoritesOnly || item.id in uiState.favoriteIds }
    }
    val selectedKey = when {
        favoritesOnly -> VOD_FAVORITES
        selectedCategoryId == null -> VOD_ALL
        else -> VOD_CATEGORY + selectedCategoryId
    }
    val entries = listOf(
        LiveTvCategoryEntry(VOD_ALL, stringResource(R.string.livetv_on_demand_all)),
        LiveTvCategoryEntry(VOD_FAVORITES, stringResource(R.string.livetv_on_demand_favorites)),
    ) + catalog.categories.map { LiveTvCategoryEntry(VOD_CATEGORY + it.id, it.name) }
    val visibleKeys = gridState.layoutInfo.visibleItemsInfo.map { it.key.toString() }
    val restoreId = focus.restoreKey(selectedKey, visibleKeys) ?: visibleItems.firstOrNull()?.id

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
    ) {
        LiveTvCategoryColumn(
            entries = entries,
            selectedKey = selectedKey,
            onSelect = { key ->
                selectedSeries = null
                when (key) {
                    VOD_ALL -> {
                        favoritesOnly = false
                        selectedCategoryId = null
                    }
                    VOD_FAVORITES -> {
                        favoritesOnly = true
                        selectedCategoryId = null
                    }
                    else -> {
                        favoritesOnly = false
                        selectedCategoryId = key.removePrefix(VOD_CATEGORY)
                    }
                }
            },
            focus = focus,
            modifier = Modifier.weight(0.2f),
            header = {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    SourceStatusFilterChip(
                        name = stringResource(R.string.livetv_on_demand_movies),
                        isSelected = uiState.kind == LiveTvVodKind.Movies,
                        onClick = {
                            selectedSeries = null
                            selectedCategoryId = null
                            favoritesOnly = false
                            viewModel.selectKind(LiveTvVodKind.Movies)
                        },
                    )
                    SourceStatusFilterChip(
                        name = stringResource(R.string.livetv_on_demand_series),
                        isSelected = uiState.kind == LiveTvVodKind.Series,
                        onClick = {
                            selectedSeries = null
                            selectedCategoryId = null
                            favoritesOnly = false
                            viewModel.selectKind(LiveTvVodKind.Series)
                        },
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(0.8f)
                .fillMaxHeight()
                .liveTvReturnToCategories(focus),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.livetv_on_demand_title),
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioTheme.colors.TextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            OnDemandSearchField(query = searchQuery, onQueryChange = { searchQuery = it })
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        Box(modifier = Modifier.weight(1f)) {
            val series = selectedSeries
            when {
                series != null -> SeriesEpisodes(
                    series = series,
                    state = uiState.episodes[series.id],
                    isFavorite = series.id in uiState.favoriteIds,
                    onToggleFavorite = { viewModel.toggleFavorite(series.id) },
                    onRetry = { viewModel.retryEpisodes(series) },
                    onEpisodeClick = { episode ->
                        startPlayback { viewModel.playback(series, episode) }
                    },
                )

                !uiState.hasSource -> CenteredMessage(
                    message = stringResource(R.string.livetv_on_demand_no_source),
                    modifier = Modifier.align(Alignment.Center),
                )

                catalogState.isLoading && catalog.items.isEmpty() -> LoadingIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                catalogState.errorMessage != null && catalog.items.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CenteredMessage(catalogState.errorMessage.orEmpty())
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                    SourceStatusFilterChip(
                        name = stringResource(R.string.livetv_on_demand_retry),
                        isSelected = false,
                        onClick = viewModel::refresh,
                    )
                }

                visibleItems.isEmpty() -> CenteredMessage(
                    message = stringResource(R.string.livetv_on_demand_empty),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .dpadRepeatThrottle(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        Box {
                            GridContentCard(
                                item = item.toMetaPreview(),
                                showReleaseInfo = item.year != null,
                                focusRequester = focus.listRequester.takeIf { item.id == restoreId },
                                onFocused = { focus.onListItemFocused(selectedKey, item.id) },
                                onLongPress = { viewModel.toggleFavorite(item.id) },
                                onClick = {
                                    if (item.kind == LiveTvVodKind.Series) {
                                        selectedSeries = item
                                    } else {
                                        startPlayback { viewModel.playback(item) }
                                    }
                                },
                            )
                            if (item.id in uiState.favoriteIds) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .padding(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Star,
                                        contentDescription = null,
                                        tint = NuvioTheme.colors.Primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedSeries == null) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.livetv_on_demand_favorite_hint),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary,
            )
        }
        }
    }
}

@Composable
private fun SeriesEpisodes(
    series: LiveTvVodItem,
    state: LiveTvVodEpisodesState?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onRetry: () -> Unit,
    onEpisodeClick: (LiveTvVodEpisode) -> Unit,
) {
    val episodes = state?.episodes.orEmpty()
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    var selectedSeason by rememberSaveable(series.id) { mutableStateOf<Int?>(null) }
    val activeSeason = selectedSeason?.takeIf { it in seasons } ?: seasons.firstOrNull()
    val seasonEpisodes = remember(episodes, activeSeason) { episodes.filter { it.season == activeSeason } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.BackgroundCard),
            ) {
                if (!series.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = series.posterUrl,
                        contentDescription = series.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            ) {
                Text(
                    text = series.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                series.year?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                }
                series.plot?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SourceStatusFilterChip(
                    name = stringResource(R.string.livetv_on_demand_favorites),
                    isSelected = isFavorite,
                    onClick = onToggleFavorite,
                )
            }
        }

        if (seasons.size > 1) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                items(seasons, key = { it }) { season ->
                    SourceStatusFilterChip(
                        name = stringResource(R.string.livetv_on_demand_season, season),
                        isSelected = season == activeSeason,
                        onClick = { selectedSeason = season },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        when {
            state == null || state.isLoading -> LoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            state.errorMessage != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CenteredMessage(state.errorMessage)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                SourceStatusFilterChip(
                    name = stringResource(R.string.livetv_on_demand_retry),
                    isSelected = false,
                    onClick = onRetry,
                )
            }

            seasonEpisodes.isEmpty() -> CenteredMessage(stringResource(R.string.livetv_on_demand_no_episodes))

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .dpadRepeatThrottle(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            ) {
                items(seasonEpisodes, key = { it.id }) { episode ->
                    EpisodeRow(episode = episode, onClick = { onEpisodeClick(episode) })
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: LiveTvVodEpisode, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
        ),
    ) {
        Row(
            modifier = Modifier.padding(NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(shape)
                    .background(NuvioTheme.colors.Background),
            ) {
                if (!episode.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${stringResource(R.string.livetv_on_demand_episode, episode.episodeNumber)} · ${episode.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.durationText?.let {
                    Text(text = it, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary)
                }
                episode.plot?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnDemandSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .width(340.dp)
                .focusRequester(focusRequester),
            singleLine = true,
            shape = RoundedCornerShape(NuvioTheme.radii.md),
            placeholder = {
                Text(
                    text = stringResource(R.string.livetv_on_demand_search),
                    color = NuvioTheme.colors.TextTertiary,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedIndicatorColor = NuvioTheme.colors.FocusRing,
                unfocusedIndicatorColor = NuvioTheme.colors.Border,
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                cursorColor = NuvioTheme.colors.FocusRing,
            ),
        )
        if (query.isNotEmpty()) {
            var isFocused by remember { mutableStateOf(false) }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            IconButton(
                onClick = {
                    // Clearing drops this button, so hand focus back to the field first.
                    runCatching { focusRequester.requestFocus() }
                    onQueryChange("")
                },
                modifier = Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .size(NuvioTheme.spacing.huge)
                    .border(
                        width = if (isFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                        color = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                        shape = RoundedCornerShape(NuvioTheme.radii.md),
                    )
                    .background(
                        color = NuvioTheme.colors.BackgroundCard,
                        shape = RoundedCornerShape(NuvioTheme.radii.md),
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.livetv_on_demand_clear_search),
                    tint = NuvioTheme.colors.TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = NuvioTheme.colors.TextSecondary,
        modifier = modifier.padding(NuvioTheme.spacing.xl),
    )
}

private fun LiveTvVodItem.toMetaPreview() = MetaPreview(
    id = id,
    type = if (kind == LiveTvVodKind.Movies) ContentType.MOVIE else ContentType.SERIES,
    name = name,
    poster = posterUrl,
    posterShape = PosterShape.POSTER,
    background = null,
    logo = null,
    description = plot,
    releaseInfo = year,
    imdbRating = rating?.toFloat(),
    genres = emptyList(),
)
