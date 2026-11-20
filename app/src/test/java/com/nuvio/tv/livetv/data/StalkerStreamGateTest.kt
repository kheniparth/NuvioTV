package com.nuvio.tv.livetv.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerStreamGateTest {
    @Test
    fun `a 403 followed by success retries and plays`() = runTest {
        val order = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val played = mutableListOf<String>()
        val gate = gate(waits, logs)
        var created = 0
        val first = gate.open("AA", "stalker:1", StalkerStage.Full, { order += "release" }) {
            order += "create"
            created++
            LiveTvPlayable("http://old", emptyMap())
        }
        played += first.url
        val second = gate.retry("AA", "stalker:1", StalkerStage.Full, 403, 1, { order += "release" }) {
            order += "create"
            created++
            LiveTvPlayable("http://new", emptyMap())
        }
        played += second.url

        assertEquals(listOf("http://old", "http://new"), played)
        assertEquals(listOf("release", "create", "release", "create"), order)
        assertEquals(listOf(1_000L), waits)
        assertEquals("stalker:1", gate.activeChannelId)
        assertTrue(logs.any { "403" in it && "channel=stalker:1" in it && "stage=full" in it && "attempt=1" in it })
        assertTrue(logs.any { it.startsWith("retry") && "attempt=2" in it && "stage=full" in it })

        try {
            gate.retry("AA", "stalker:1", StalkerStage.Full, 403, 3, { order += "release" }) {
                order += "create"
                LiveTvPlayable("http://again", emptyMap())
            }
            error("expected the portal to be busy")
        } catch (busy: StalkerPortalBusy) {
            assertEquals(STALKER_BUSY, busy.message)
        }
        assertEquals("release", order.last())
    }

    @Test
    fun `a stream is released before the next create_link`() = runTest {
        val order = mutableListOf<String>()
        val gate = gate()
        gate.open("AA", "1", StalkerStage.Preview, { order += "release" }) {
            order += "create-1"
            LiveTvPlayable("http://1", emptyMap())
        }
        gate.open("AA", "2", StalkerStage.Zap, { order += "release" }) {
            order += "create-2"
            LiveTvPlayable("http://2", emptyMap())
        }

        assertEquals(listOf("release", "create-1", "release", "create-2"), order)
        assertEquals("2", gate.activeChannelId)
    }

    @Test
    fun `switching back to a channel just left does not keep the old stream open`() = runTest {
        val open = linkedSetOf<String>()
        val creates = mutableListOf<String>()
        val gate = StalkerStreamGate().apply {
            pause = {}
            log = {}
        }
        suspend fun go(id: String) {
            gate.open("AA", id, StalkerStage.Zap, { open.clear() }) {
                creates += id
                open += id
                LiveTvPlayable("http://$id/${creates.size}", emptyMap())
            }
        }

        go("A")
        go("B")
        go("A")

        assertEquals(setOf("A"), open)
        assertEquals(listOf("A", "B", "A"), creates)
        assertEquals("A", gate.activeChannelId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancelled create_link does not leave the stream open`() = runTest {
        val released = mutableListOf<String>()
        val gate = StalkerStreamGate().apply {
            pause = {}
            log = {}
        }
        val job = launch {
            gate.open("AA", "1", StalkerStage.Full, { released += "release" }) {
                suspendCancellableCoroutine { }
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()

        assertEquals(listOf("release", "release"), released)
        assertNull(gate.activeChannelId)
    }

    private fun gate(
        waits: MutableList<Long> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
    ) = StalkerStreamGate().apply {
        pause = { waits += it }
        log = { logs += it }
    }
}
