package com.cheatervpnapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StatsStore.clear(context)
    }

    @After
    fun tearDown() {
        StatsStore.clear(context)
    }

    private fun session(id: Long, rx: Long, tx: Long, durationSec: Long = 60) =
        SessionRecord(
            id = id,
            serverLabel = "Test",
            serverHost = "vpn.example.com",
            startTime = 0,
            endTime = durationSec * 1000,
            durationSec = durationSec,
            rxBytes = rx,
            txBytes = tx,
        )

    @Test
    fun `saveSession persists history newest first`() {
        StatsStore.saveSession(context, session(1, 100, 10))
        StatsStore.saveSession(context, session(2, 200, 20))

        val history = StatsStore.loadHistory(context)
        assertEquals(listOf(2L, 1L), history.map { it.id })
        assertEquals("Test", history[0].serverLabel)
        assertEquals("vpn.example.com", history[0].serverHost)
        assertEquals(60L, history[0].durationSec)
    }

    @Test
    fun `totals accumulate across sessions`() {
        StatsStore.saveSession(context, session(1, 100, 10, 60))
        StatsStore.saveSession(context, session(2, 200, 20, 120))

        val totals = StatsStore.totals(context)
        assertEquals(300L, totals.totalRx)
        assertEquals(30L, totals.totalTx)
        assertEquals(2L, totals.totalSessions)
        assertEquals(180L, totals.totalDurationSec)
    }

    @Test
    fun `history is capped at 100 entries keeping the newest`() {
        repeat(150) { i -> StatsStore.saveSession(context, session(i.toLong(), 1, 1)) }

        val history = StatsStore.loadHistory(context)
        assertEquals(100, history.size)
        assertEquals(149L, history.first().id)
        assertEquals(50L, history.last().id)
    }

    @Test
    fun `clear resets history and totals`() {
        StatsStore.saveSession(context, session(1, 100, 10, 60))
        StatsStore.clear(context)

        assertTrue(StatsStore.loadHistory(context).isEmpty())
        assertEquals(Totals(totalRx = 0, totalTx = 0, totalSessions = 0, totalDurationSec = 0), StatsStore.totals(context))
    }
}
