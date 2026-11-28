package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val LIVE_TV_ZAP_DEBOUNCE_MILLIS = 400L

/** Opens only the channel selected last, so a burst of Up/Down presses opens one stream. */
internal class LiveTvZapDebounce(
    private val scope: CoroutineScope,
    private val delayMillis: Long = LIVE_TV_ZAP_DEBOUNCE_MILLIS,
    private val onOpen: suspend (LiveTvChannel) -> Unit,
) {
    private var job: Job? = null

    fun request(channel: LiveTvChannel) {
        job?.cancel()
        job = scope.launch {
            delay(delayMillis)
            onOpen(channel)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
