package com.dq.vpndlnarelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStatsTest {
    private var now = 0L
    private fun stats(firstByteMs: Long = 0) = StreamStats(firstByteMs * MS) { now }

    @Test
    fun summaryReportsRateAndWhereTimeWent() {
        val stats = stats(firstByteMs = 340)
        stats.recordRead(2 * S)
        stats.recordWrite(10_000_000, 5 * S)
        now = 10 * S

        assertEquals(
            "Stream 10.0 MB in 10.0 s · avg 8.0 Mbps · waiting on server 20% · " +
                "waiting on client 50% · server stalls 1 (max 2.0 s) · first byte 340 ms",
            stats.summary(),
        )
    }

    @Test
    fun countsOnlyReadsOfAtLeastASecondAsStalls() {
        val stats = stats()
        stats.recordRead(999 * MS)
        stats.recordRead(1 * S)
        stats.recordRead(3_500 * MS)
        stats.recordRead(1_200 * MS)
        now = 10 * S

        assertEquals(3, stats.stalls)
        assertTrue(stats.summary(), stats.summary().contains("server stalls 3 (max 3.5 s)"))
    }

    @Test
    fun formatsLongStreamsInMinutes() {
        val stats = stats()
        stats.recordRead(10 * MS)
        now = (5 * 60 + 12) * S

        val summary = stats.summary()
        assertTrue(summary, summary.startsWith("Stream 0.0 MB in 5m 12s · avg 0.0 Mbps"))
        assertTrue(summary, summary.contains("no server stalls"))
    }

    @Test
    fun capsSharesAtOneHundredPercent() {
        val stats = stats()
        stats.recordWrite(1000, 20 * S)
        now = 10 * S

        assertTrue(stats.summary(), stats.summary().contains("waiting on client 100%"))
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
