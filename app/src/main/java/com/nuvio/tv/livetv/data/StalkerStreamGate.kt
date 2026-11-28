package com.nuvio.tv.livetv.data

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val STALKER_LOG_TAG = "LiveTvStalker"
internal const val STALKER_BUSY = "The portal is busy"
internal const val STALKER_MAX_TRIES = 3

enum class StalkerStage { Preview, Full, Zap }

class StalkerPortalBusy : IllegalStateException(STALKER_BUSY)

/** HTTP status from `error("HTTP 403")` in the portal client. CDN 403s are reported by the player. */
internal fun stalkerHttpStatus(error: Throwable): Int? {
    val code = error.message?.removePrefix("HTTP ")?.takeWhile(Char::isDigit)?.toIntOrNull()
    return code?.takeIf { it == 401 || it == 403 }
}

internal fun stalkerBackoffMillis(nextTry: Int): Long = if (nextTry <= 2) 1_000L else 2_000L

/**
 * One live stream per MAC. The previous stream is released, and that release finishes,
 * before the next create_link. A 403 or 401 takes a new link, up to [STALKER_MAX_TRIES].
 */
@Singleton
class StalkerStreamGate @Inject constructor() {
    internal var pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    internal var log: (String) -> Unit = { Log.i(STALKER_LOG_TAG, it) }
    private val mutex = Mutex()
    private val releasers = mutableListOf<suspend () -> Unit>()
    private var activeMac: String? = null
    var activeChannelId: String? = null
        private set
    var fullPlayerLoading: Boolean = false
        private set
    var lastStage: StalkerStage = StalkerStage.Full
        private set

    fun addReleaser(block: suspend () -> Unit) {
        releasers += block
    }

    fun allowPreview() {
        fullPlayerLoading = false
    }

    suspend fun open(
        macAddress: String,
        channelId: String,
        stage: StalkerStage,
        release: suspend () -> Unit = {},
        createLink: suspend () -> LiveTvPlayable,
    ): LiveTvPlayable = mutex.withLock {
        if (stage == StalkerStage.Preview && fullPlayerLoading) throw CancellationException()
        val holdsPlayer = stage != StalkerStage.Preview
        if (holdsPlayer) fullPlayerLoading = true
        lastStage = stage
        try {
            var attempt = 1
            var playable: LiveTvPlayable? = null
            while (playable == null) {
                try {
                    releaseAll(release)
                    activeChannelId = null
                    activeMac = null
                    val created = createLink()
                    currentCoroutineContext().ensureActive()
                    activeChannelId = channelId
                    activeMac = macAddress
                    log("open channel=$channelId stage=${stage.wire()} attempt=$attempt")
                    playable = created
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    val status = stalkerHttpStatus(error) ?: throw error
                    log("$status channel=$channelId stage=${stage.wire()} attempt=$attempt")
                    if (attempt >= STALKER_MAX_TRIES) throw StalkerPortalBusy()
                    attempt++
                    pause(stalkerBackoffMillis(attempt))
                    currentCoroutineContext().ensureActive()
                    log("retry channel=$channelId stage=${stage.wire()} attempt=$attempt")
                }
            }
            playable
        } catch (cancel: CancellationException) {
            releaseQuiet(release)
            activeChannelId = null
            activeMac = null
            if (holdsPlayer) fullPlayerLoading = false
            throw cancel
        } catch (error: Exception) {
            if (holdsPlayer) fullPlayerLoading = false
            throw error
        }
    }

    /** The CDN rejected the link the player already opened. Release, wait, then a new create_link. */
    suspend fun retry(
        macAddress: String,
        channelId: String,
        stage: StalkerStage,
        status: Int,
        failedAttempt: Int,
        release: suspend () -> Unit = {},
        createLink: suspend () -> LiveTvPlayable,
    ): LiveTvPlayable = mutex.withLock {
        log("$status channel=$channelId stage=${stage.wire()} attempt=$failedAttempt")
        releaseAll(release)
        activeChannelId = null
        activeMac = null
        if (failedAttempt >= STALKER_MAX_TRIES) throw StalkerPortalBusy()
        val next = failedAttempt + 1
        pause(stalkerBackoffMillis(next))
        currentCoroutineContext().ensureActive()
        log("retry channel=$channelId stage=${stage.wire()} attempt=$next")
        val playable = createLink()
        currentCoroutineContext().ensureActive()
        activeChannelId = channelId
        activeMac = macAddress
        lastStage = stage
        playable
    }

    private suspend fun releaseAll(extra: suspend () -> Unit) {
        extra()
        releasers.forEach { it() }
    }

    private suspend fun releaseQuiet(extra: suspend () -> Unit) {
        try {
            releaseAll(extra)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        }
    }

    private fun StalkerStage.wire() = when (this) {
        StalkerStage.Preview -> "preview"
        StalkerStage.Full -> "full"
        StalkerStage.Zap -> "zap"
    }
}
