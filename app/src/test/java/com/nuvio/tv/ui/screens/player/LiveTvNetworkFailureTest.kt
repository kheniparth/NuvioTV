package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import java.net.ConnectException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvNetworkFailureTest {
    @Test
    fun `network connection failed on a live channel is offline`() {
        val error = PlaybackException(
            "Source error",
            UnknownHostException("Unable to resolve host \"example.invalid\""),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        assertTrue(isLiveTvNetworkFailure("channel", error))
        assertTrue(isLiveTvNetworkFailure("CHANNEL", error))
    }

    @Test
    fun `network timeout on a live channel is offline`() {
        val error = PlaybackException(
            "Source error",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        assertTrue(isLiveTvNetworkFailure("channel", error))
    }

    @Test
    fun `connect exception under unspecified io is offline for live`() {
        val error = PlaybackException(
            "Source error",
            ConnectException("Failed to connect to /10.0.0.1"),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        assertTrue(isLiveTvNetworkFailure("channel", error))
    }

    @Test
    fun `vod and non-network errors are not offline live failures`() {
        val network = PlaybackException(
            "Source error",
            UnknownHostException("host"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        assertFalse(isLiveTvNetworkFailure("movie", network))
        assertFalse(isLiveTvNetworkFailure(null, network))

        val http = PlaybackException(
            "HTTP 403",
            null,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        assertFalse(isLiveTvNetworkFailure("channel", http))
    }
}
