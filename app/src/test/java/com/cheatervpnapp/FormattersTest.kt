package com.cheatervpnapp

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `bytes formats small values`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("1023 B", Formatters.bytes(1023))
    }

    @Test
    fun `bytes formats KB MB GB`() {
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.5 MB", Formatters.bytes(1572864))
        assertEquals("1.00 GB", Formatters.bytes(1073741824))
    }

    @Test
    fun `durationClock formats hours minutes seconds`() {
        assertEquals("00:00", Formatters.durationClock(0))
        assertEquals("00:59", Formatters.durationClock(59))
        assertEquals("01:00", Formatters.durationClock(60))
        assertEquals("1:30:00", Formatters.durationClock(5400))
        assertEquals("25:00:00", Formatters.durationClock(90000))
    }

    @Test
    fun `durationShort formats compactly`() {
        assertEquals("0s", Formatters.durationShort(0))
        assertEquals("45s", Formatters.durationShort(45))
        assertEquals("5m 1s", Formatters.durationShort(301))
        assertEquals("1h 30m", Formatters.durationShort(5400))
    }

    @Test
    fun `time uses fixed pattern`() {
        val result = Formatters.time(0)
        assertEquals(16, result.length)
        assertEquals('.', result[2])
        assertEquals('.', result[5])
        assertEquals(' ', result[10])
        assertEquals(':', result[13])
    }
}
