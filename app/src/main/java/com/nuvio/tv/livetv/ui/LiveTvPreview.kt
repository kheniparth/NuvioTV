package com.nuvio.tv.livetv.ui

import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvRepository
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
import okhttp3.OkHttpClient

internal const val PREVIEW_FOCUS_DELAY_MILLIS = 1_000L
private const val SCREEN_CHANGE_RELEASE_DELAY_MILLIS = 750L

/** Waits out focus changes so fast scrolling never starts a preview. */
internal class LiveTvPreviewDebounce(
    private val scope: CoroutineScope,
    private val delayMillis: Long = PREVIEW_FOCUS_DELAY_MILLIS,
    private val onStart: suspend (LiveTvChannel) -> Unit,
    private val onStop: () -> Unit,
) {
    private var focused: LiveTvChannel? = null
    private var job: Job? = null

    fun focus(channel: LiveTvChannel?) {
        if (channel?.id == focused?.id) return
        focused = channel
        job?.cancel()
        if (channel == null) {
            onStop()
            return
        }
        job = scope.launch {
            delay(delayMillis)
            onStart(channel)
        }
    }

    fun cancel() {
        focused = null
        job?.cancel()
        onStop()
    }
}

/**
 * One muted ExoPlayer shared by every Live TV screen. It is stopped between focus changes and
 * released once no Live TV screen is left (after a short grace period for screen switches).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class LiveTvPreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var sessions = 0
    private var releaseJob: Job? = null
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()

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

    fun play(url: String, headers: Map<String, String>) {
        val exo = player ?: createPlayer().also { player = it }
        val mediaSource = DefaultMediaSourceFactory(
            OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers)
        ).createMediaSource(MediaItem.fromUri(url))
        exo.stop()
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
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
                        this@LiveTvPreviewPlayer.stop()
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

    fun onScroll() = debounce.cancel()

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
            return
        }
        currentCoroutineContext().ensureActive()
        if (playable.url.isNotBlank()) player.play(playable.url, playable.headers)
    }
}

@Composable
fun LiveTvPreviewOverlay(
    focusedChannel: LiveTvChannel?,
    scrollInProgress: Boolean,
    modifier: Modifier = Modifier,
    viewModel: LiveTvPreviewViewModel = hiltViewModel(),
) {
    val player = viewModel.player
    val previewing by player.previewing.collectAsStateWithLifecycle()
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

    Box(modifier = modifier) {
        if (previewing) {
            viewModel.player.exoPlayer?.let { exo -> PreviewSurface(exo) }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PreviewSurface(player: ExoPlayer) {
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
        modifier = Modifier
            .size(width = 320.dp, height = 180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .focusProperties { canFocus = false },
    )
}
