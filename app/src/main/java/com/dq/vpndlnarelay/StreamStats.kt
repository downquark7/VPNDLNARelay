package com.dq.vpndlnarelay

import java.util.Locale

/**
 * Timing counters for one streamed response body, summarised in a single log line when the stream
 * ends. Recording is a few additions per chunk and nothing runs on a timer, so it costs no wakeups.
 *
 * Time spent waiting on the server (over the VPN) versus waiting on the client (on Wi-Fi) shows
 * which side is holding the stream back.
 */
class StreamStats(
    /** Time from opening the upstream connection to receiving the response head. */
    private val firstByteNanos: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private val start = clock()

    var bytesWritten = 0L; private set
    var stalls = 0; private set
    private var readNanos = 0L
    private var writeNanos = 0L
    private var longestStallNanos = 0L

    /** A read from the server blocked for [nanos]. */
    fun recordRead(nanos: Long) {
        readNanos += nanos
        if (nanos >= STALL_NANOS) {
            stalls++
            if (nanos > longestStallNanos) longestStallNanos = nanos
        }
    }

    /** [n] bytes were handed to the client, blocking for [nanos]. */
    fun recordWrite(n: Int, nanos: Long) {
        bytesWritten += n
        writeNanos += nanos
    }

    fun summary(): String {
        val elapsed = (clock() - start).coerceAtLeast(1)
        return listOf(
            "Stream ${megabytes(bytesWritten)} in ${duration(elapsed)}",
            "avg ${mbps(bytesWritten, elapsed)}",
            "waiting on server ${percent(readNanos, elapsed)}",
            "waiting on client ${percent(writeNanos, elapsed)}",
            if (stalls == 0) "no server stalls" else "server stalls $stalls (max ${seconds(longestStallNanos)})",
            "first byte ${firstByteNanos / 1_000_000} ms",
        ).joinToString(" · ")
    }

    private fun megabytes(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1e6)
    private fun mbps(bytes: Long, nanos: Long) = String.format(Locale.US, "%.1f Mbps", bytes * 8e3 / nanos)
    private fun percent(part: Long, whole: Long) = "${(part * 100 / whole).coerceIn(0, 100)}%"
    private fun seconds(nanos: Long) = String.format(Locale.US, "%.1f s", nanos / 1e9)

    private fun duration(nanos: Long): String {
        val s = nanos / 1_000_000_000
        return if (s < 60) String.format(Locale.US, "%.1f s", nanos / 1e9) else "${s / 60}m ${s % 60}s"
    }

    companion object {
        /** A single server read blocking this long counts as a stall. */
        const val STALL_NANOS = 1_000_000_000L
    }
}
