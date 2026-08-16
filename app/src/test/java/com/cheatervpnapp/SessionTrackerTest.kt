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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTrackerTest {

    private lateinit var context: Context

    private val server = Server(
        id = "1",
        name = "Moscow",
        country = "Russia",
        countryCode = "RU",
        host = "vpn.example.com",
        port = 51820,
        config = "[Interface]",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StatsStore.clear(context)
    }

    @After
    fun tearDown() {
        StatsStore.clear(context)
    }

    @Test
    fun `finish records session with traffic deltas and server info`() {
        SessionTracker.start(server, rx = 500, tx = 100)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        SessionTracker.finish(context, rx = 700, tx = 150)

        val record = StatsStore.loadHistory(context).single()
        assertEquals("Russia", record.serverLabel)
        assertEquals("vpn.example.com", record.serverHost)
        assertEquals(200L, record.rxBytes)
        assertEquals(50L, record.txBytes)
        assertEquals(5L, record.durationSec)
        assertTrue(record.startTime <= record.endTime)
    }

    @Test
    fun `finish without active session is a no-op`() {
        SessionTracker.finish(context, rx = 1, tx = 1)
        assertTrue(StatsStore.loadHistory(context).isEmpty())
    }

    @Test
    fun `snapshot returns null when not active`() {
        assertEquals(null, SessionTracker.snapshot(rx = 0, tx = 0))
    }

    @Test
    fun `snapshot reports elapsed time and deltas`() {
        SessionTracker.start(server, rx = 500, tx = 100)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))

        val live = SessionTracker.snapshot(rx = 800, tx = 200)!!
        assertEquals(10L, live.elapsedSec)
        assertEquals(300L, live.rxBytes)
        assertEquals(100L, live.txBytes)
    }
}
