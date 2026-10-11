package com.dq.vpndlnarelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStatsTest {
    private var now = 0L
    private fun stats(firstByteMs: Long = 0) = StreamStats(firstByteMs * MS) { now }

    @Test
    fun summaryReportsRatesAndShares() {
        val stats = stats(firstByteMs = 340)
        stats.recordRead(10_000_000, 2 * S)
        stats.recordWrite(10_000_000, 5 * S)
        stats.recordEmpty(1 * S)
        now = 10 * S

        assertEquals(
            "Stream 10.0 MB in 10.0 s · avg 8.0 Mbps · VPN 40.0 Mbps · buffer empty 10% · " +
                "waiting on client 50% · VPN stalls 1 (max 2.0 s) · first byte 340 ms",
            stats.summary(),
        )
    }

    @Test
    fun countsOnlyReadsOfAtLeastASecondAsStalls() {
        val stats = stats()
        stats.recordRead(100, 999 * MS)
        stats.recordRead(100, 1 * S)
        stats.recordRead(100, 3_500 * MS)
        stats.recordRead(100, 1_200 * MS)
        now = 10 * S

        assertEquals(3, stats.stalls)
        assertTrue(stats.summary(), stats.summary().contains("VPN stalls 3 (max 3.5 s)"))
    }

    @Test
    fun ignoresWaitingBeforeTheFirstByte() {
        val stats = stats()
        stats.recordEmpty(4 * S)
        stats.recordWrite(1000, 0)
        stats.recordEmpty(2 * S)
        now = 10 * S

        assertTrue(stats.summary(), stats.summary().contains("buffer empty 20%"))
    }

    @Test
    fun endOfStreamReadIsNotCountedAsData() {
        val stats = stats()
        stats.recordRead(500, 0)
        stats.recordRead(-1, 0)

        assertEquals(500, stats.bytesRead)
    }

    @Test
    fun formatsLongStreamsInMinutesAndOmitsUnmeasuredVpnRate() {
        val stats = stats()
        now = (5 * 60 + 12) * S

        val summary = stats.summary()
        assertTrue(summary, summary.startsWith("Stream 0.0 MB in 5m 12s · avg 0.0 Mbps · buffer empty"))
        assertTrue(summary, !summary.contains("VPN 0"))
        assertTrue(summary, summary.contains("no VPN stalls"))
    }

    @Test
    fun zeroElapsedTimeDoesNotDivideByZero() {
        val stats = stats()
        stats.recordWrite(1000, 0)

        assertTrue(stats.summary().startsWith("Stream 0.0 MB in 0.0 s"))
    }

    private companion object {
        const val MS = 1_000_000L
        const val S = 1_000_000_000L
    }
}
