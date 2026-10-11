package com.dq.vpndlnarelay

import java.util.Locale

/**
 * Timing counters for one streamed response body, summarised in a single log line when the stream
 * ends. Recording is a few additions per chunk and nothing runs on a timer, so it costs no wakeups.
 *
 * The read side (upstream, over the VPN) and the write side (the client on Wi-Fi) run on different
 * threads; each field is only ever written by one of them.
 */
class StreamStats(
    /** Time from opening the upstream connection to receiving the response head. */
    private val firstByteNanos: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private val start = clock()

    @Volatile var bytesRead = 0L; private set
    @Volatile private var readNanos = 0L
    @Volatile var stalls = 0; private set
    @Volatile private var longestStallNanos = 0L
    @Volatile var coveredStalls = 0; private set

    @Volatile var bytesWritten = 0L; private set
    @Volatile private var writeNanos = 0L
    @Volatile private var emptyNanos = 0L

    fun now(): Long = clock()

    /**
     * [n] bytes arrived from upstream after blocking for [nanos]. [covered] means the buffer kept the
     * client supplied for the whole wait.
     */
    fun recordRead(n: Int, nanos: Long, covered: Boolean = false) {
        if (n > 0) bytesRead += n
        readNanos += nanos
        if (nanos >= STALL_NANOS) {
            stalls++
            if (covered) coveredStalls++
            if (nanos > longestStallNanos) longestStallNanos = nanos
        }
    }

    /** [n] bytes were handed to the client, blocking for [nanos]. */
    fun recordWrite(n: Int, nanos: Long) {
        bytesWritten += n
        writeNanos += nanos
    }

    /** The client side waited [nanos] for upstream data with nothing buffered. */
    fun recordEmpty(nanos: Long) {
        // Waiting before the first byte is start-up latency, not an underrun during playback.
        if (bytesWritten > 0) emptyNanos += nanos
    }

    fun summary(): String {
        val elapsed = (clock() - start).coerceAtLeast(1)
        val parts = mutableListOf(
            "Stream ${megabytes(bytesWritten)} in ${duration(elapsed)}",
            "avg ${mbps(bytesWritten, elapsed)}",
        )
        if (readNanos > 0) parts += "VPN ${mbps(bytesRead, readNanos)}"
        parts += "buffer empty ${percent(emptyNanos, elapsed)}"
        parts += "waiting on client ${percent(writeNanos, elapsed)}"
        parts += if (stalls == 0) "no VPN stalls"
        else "VPN stalls $stalls (max ${seconds(longestStallNanos)}, $coveredStalls covered by buffer)"
        parts += "first byte ${firstByteNanos / 1_000_000} ms"
        return parts.joinToString(" · ")
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
        /** A single upstream read blocking this long counts as a stall. */
        const val STALL_NANOS = 1_000_000_000L
    }
}
