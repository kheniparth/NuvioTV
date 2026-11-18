package com.nuvio.tv.livetv.ui

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val PREVIEW_FOCUS_DELAY_MILLIS = 1_000L
internal const val PREVIEW_FOCUS_LOSS_GRACE_MILLIS = 400L
internal const val LIVE_TV_PREVIEW_LOG_TAG = "LiveTvPreview"
private const val SCREEN_CHANGE_RELEASE_DELAY_MILLIS = 750L

/** Waits out focus changes so fast scrolling never starts a preview. */
internal class LiveTvPreviewDebounce(
    private val scope: CoroutineScope,
    private val delayMillis: Long = PREVIEW_FOCUS_DELAY_MILLIS,
    private val onStart: suspend (LiveTvChannel) -> Unit,
    private val onStop: () -> Unit,
) {
    private var focused: LiveTvChannel? = null
    private var playingId: String? = null
    private var job: Job? = null
    private var stopJob: Job? = null

    fun focus(channel: LiveTvChannel?) {
        if (channel == null) {
            job?.cancel()
            job = null
            if (playingId == null) {
                focused = null
                stopJob?.cancel()
                onStop()
                return
            }
            // Focus and scroll flicker for a frame after the surface appears. Wait before dropping the stream.
            if (stopJob?.isActive == true) return
            val leaving = playingId
            stopJob = scope.launch {
                delay(PREVIEW_FOCUS_LOSS_GRACE_MILLIS)
                if (playingId == leaving) {
                    focused = null
                    playingId = null
                    onStop()
                }
            }
            return
        }
        stopJob?.cancel()
        stopJob = null
        if (channel.id == focused?.id && (playingId == channel.id || job?.isActive == true)) return
        val switched = channel.id != focused?.id
        focused = channel
        job?.cancel()
        if (switched) {
            playingId = null
            onStop()
        }
        val target = channel
        job = scope.launch {
            delay(delayMillis)
            playingId = target.id
            onStart(target)
        }
    }

    /** Scrolling must not open a stream, and must not cut one that already started. */
    fun scroll() {
        if (playingId != null) return
        job?.cancel()
        job = null
    }

    fun cancel() {
        focused = null
        playingId = null
        job?.cancel()
        job = null
        stopJob?.cancel()
        stopJob = null
        onStop()
    }
}

internal enum class PreviewSourceKind { Hls, Ts, Progressive }

/** Same HLS / MPEG-TS / progressive choice the full player makes for a channel URL. */
internal fun previewSourceKind(mimeType: String?): PreviewSourceKind = when (mimeType) {
    MimeTypes.APPLICATION_M3U8 -> PreviewSourceKind.Hls
    MimeTypes.VIDEO_MP2T -> PreviewSourceKind.Ts
    else -> PreviewSourceKind.Progressive
}

/** Extension from the URL when it has one; otherwise the same network probe the full player uses after a parse error. */
internal suspend fun previewMimeType(url: String, headers: Map<String, String>): String? {
    val inferred = PlayerMediaSourceFactory.inferMimeType(url, filename = null)
    if (inferred != null) return inferred
    return PlayerMediaSourceFactory.probeNetworkMimeType(url, headers)
}

/**
 * One muted ExoPlayer shared by every Live TV screen. It is stopped between focus changes and
 * released once no Live TV screen is left (after a short grace period for screen switches).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class LiveTvPreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var sessions = 0
    private var releaseJob: Job? = null
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    internal val exoPlayer: ExoPlayer? get() = player

    fun enterScreen() {
        sessions += 1
        releaseJob?.cancel()
        releaseJob = null
    }

    fun leaveScreen() {
        sessions = (sessions - 1).coerceAtLeast(0)
        stop()
        if (sessions == 0) {
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(SCREEN_CHANGE_RELEASE_DELAY_MILLIS)
                release()
            }
        }
    }

    fun play(url: String, headers: Map<String, String>, mimeType: String? = null) {
        val exo = player ?: createPlayer().also { player = it }
        val dataSource = PlayerPlaybackNetworking.createHttpDataSourceFactory(headers)
        val item = MediaItem.Builder().setUri(url).apply { mimeType?.let(::setMimeType) }.build()
        val mediaSource = when (previewSourceKind(mimeType)) {
            PreviewSourceKind.Hls -> HlsMediaSource.Factory(dataSource)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
            PreviewSourceKind.Ts, PreviewSourceKind.Progressive ->
                DefaultMediaSourceFactory(dataSource).createMediaSource(item)
        }
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
        _error.value = null
        _previewing.value = true
    }

    fun stop() {
        player?.let { exo ->
            runCatching {
                exo.playWhenReady = false
                exo.stop()
                exo.clearMediaItems()
            }
        }
        _previewing.value = false
        _error.value = null
    }

    fun fail(message: String, cause: Throwable? = null) {
        Log.e(LIVE_TV_PREVIEW_LOG_TAG, message, cause)
        stop()
        _error.value = message
    }

    fun release() {
        releaseJob?.cancel()
        releaseJob = null
        stop()
        player?.release()
        player = null
    }

    private fun createPlayer(): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            // No audio track is selected, so the preview can never make noise or claim an audio codec.
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true))
        }
        return ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                        val headline = error.message?.takeIf { it.isNotBlank() } ?: error.errorCodeName
                        val message = if (detail != null && detail != headline) "$headline: $detail" else headline
                        this@LiveTvPreviewPlayer.fail(message, error)
                    }
                })
            }
    }
}

@HiltViewModel
class LiveTvPreviewViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    internal val player: LiveTvPreviewPlayer,
) : ViewModel() {
    private val debounce = LiveTvPreviewDebounce(
        scope = viewModelScope,
        onStart = { channel -> startPreview(channel) },
        onStop = { player.stop() },
    )

    fun onFocus(channel: LiveTvChannel?) = debounce.focus(channel)

    fun onScroll() = debounce.scroll()

    /** The account allows one stream: stop the preview before the full-screen player opens. */
    fun stopForPlayback() = debounce.cancel()

    fun onAppBackgrounded() {
        debounce.cancel()
        player.release()
    }

    private suspend fun startPreview(channel: LiveTvChannel) {
        val playable = try {
            repository.resolvePlayableUrl(channel)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            player.fail(error.message ?: error.javaClass.simpleName)
            return
        }
        currentCoroutineContext().ensureActive()
        if (playable.url.isBlank()) {
            player.fail("No stream URL")
            return
        }
        val mimeType = try {
            previewMimeType(playable.url, playable.headers)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            player.fail(error.message ?: error.javaClass.simpleName)
            return
        }
        currentCoroutineContext().ensureActive()
        player.play(playable.url, playable.headers, mimeType)
    }
}

internal val LiveTvPreviewPanelWidth = 320.dp

/**
 * Always-visible preview beside the list: the focused channel's name at once, then muted video.
 * Not focusable, so the D-pad stays in the list.
 */
@Composable
fun LiveTvPreviewPanel(
    focusedChannel: LiveTvChannel?,
    scrollInProgress: Boolean,
    modifier: Modifier = Modifier,
    nowTitle: String? = null,
    nextTitle: String? = null,
    viewModel: LiveTvPreviewViewModel = hiltViewModel(),
) {
    val player = viewModel.player
    val previewing by player.previewing.collectAsStateWithLifecycle()
    val error by player.error.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? LifecycleOwner
    val latestFocus by rememberUpdatedState(focusedChannel)

    DisposableEffect(player) {
        player.enterScreen()
        onDispose {
            viewModel.onFocus(null)
            player.leaveScreen()
        }
    }

    DisposableEffect(activity) {
        val lifecycle = activity?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onFocus(latestFocus)
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(focusedChannel?.id, scrollInProgress) {
        if (scrollInProgress) viewModel.onScroll() else viewModel.onFocus(focusedChannel)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val exo = player.exoPlayer
            if (previewing && exo != null) {
                PreviewSurface(exo, Modifier.fillMaxSize())
            } else {
                val message = error
                    ?: if (focusedChannel != null) stringResource(R.string.livetv_preview_starting) else null
                if (message != null) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (error != null) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        focusedChannel?.let { channel ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(channel)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val guide = guideLine(nowTitle, nextTitle)
                    if (guide != null) {
                        Text(
                            text = guide,
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
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PreviewSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val playerView = remember(player) {
        PlayerView(context).apply {
            useController = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            this.player = player
        }
    }
    DisposableEffect(playerView) {
        onDispose {
            if (playerView.player === player) playerView.player = null
        }
    }
    AndroidView(
        factory = { playerView },
        update = { view -> view.player = player },
        modifier = modifier.focusProperties { canFocus = false },
    )
}

@Composable
internal fun ChannelLogo(channel: LiveTvChannel) {
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
internal fun guideLine(nowTitle: String?, nextTitle: String?): String? = when {
    nowTitle != null && nextTitle != null -> stringResource(R.string.livetv_now_next, nowTitle, nextTitle)
    nowTitle != null -> stringResource(R.string.livetv_now, nowTitle)
    nextTitle != null -> stringResource(R.string.livetv_next, nextTitle)
    else -> null
}
