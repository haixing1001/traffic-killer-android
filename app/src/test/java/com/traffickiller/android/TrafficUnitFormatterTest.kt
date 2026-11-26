package com.traffickiller.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficUnitFormatterTest {

    @Test
    fun parseThreadCount_clampsOutOfRangeValues() {
        assertEquals(TrafficUnitFormatter.DEFAULT_THREADS, TrafficUnitFormatter.parseThreadCount("abc"))
        assertEquals(1, TrafficUnitFormatter.parseThreadCount("0"))
        assertEquals(32, TrafficUnitFormatter.parseThreadCount("99"))
        assertEquals(8, TrafficUnitFormatter.parseThreadCount(" 8 "))
    }

    @Test
    fun parseLimitGbToBytes_handlesEmptyZeroAndFractionalGb() {
        assertEquals(0L, TrafficUnitFormatter.parseLimitGbToBytes(""))
        assertEquals(0L, TrafficUnitFormatter.parseLimitGbToBytes("0"))
        assertEquals(0L, TrafficUnitFormatter.parseLimitGbToBytes("-2"))
        assertEquals(5L * TrafficUnitFormatter.GIB_BYTES, TrafficUnitFormatter.parseLimitGbToBytes("5"))
        assertEquals(512L * 1024L * 1024L, TrafficUnitFormatter.parseLimitGbToBytes("0.5"))
    }

    @Test
    fun isValidHttpUrl_acceptsHttpAndHttpsOnly() {
        assertTrue(TrafficUnitFormatter.isValidHttpUrl("https://cloudphoneh5.buy.139.com/js/chunk-vendors.47eadd9a.js"))
        assertTrue(TrafficUnitFormatter.isValidHttpUrl("http://example.com/file.bin"))
        assertFalse(TrafficUnitFormatter.isValidHttpUrl("ftp://example.com/file.bin"))
        assertFalse(TrafficUnitFormatter.isValidHttpUrl("not a url"))
        assertFalse(TrafficUnitFormatter.isValidHttpUrl(""))
    }

    @Test
    fun formatBytes_and_formatSpeed_renderExpectedUnits() {
        assertEquals("0B", TrafficUnitFormatter.formatBytes(0L))
        assertEquals("512B", TrafficUnitFormatter.formatBytes(512L))
        assertEquals("1.0MB", TrafficUnitFormatter.formatBytes(1024L * 1024L))
        assertEquals("1.50GB", TrafficUnitFormatter.formatBytes((1.5 * TrafficUnitFormatter.GIB_BYTES).toLong()))
        assertEquals("10.0MB/s", TrafficUnitFormatter.formatSpeed(10.0 * 1024.0 * 1024.0))
        assertEquals("100.00Mbps", TrafficUnitFormatter.formatBitsPerSec(100_000_000.0))
    }
}
