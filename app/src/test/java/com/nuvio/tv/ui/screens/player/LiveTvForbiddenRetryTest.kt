package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvForbiddenRetryTest {
    @Test
    fun `403 on a live channel is retried once`() {
        assertTrue(isLiveTvForbiddenRetry("channel", 403))
        assertTrue(isLiveTvForbiddenRetry("CHANNEL", 403))
    }

    @Test
    fun `other statuses and content types are not retried`() {
        assertFalse(isLiveTvForbiddenRetry("channel", 404))
        assertFalse(isLiveTvForbiddenRetry("channel", null))
        assertFalse(isLiveTvForbiddenRetry("movie", 403))
        assertFalse(isLiveTvForbiddenRetry(null, 403))
    }
}
