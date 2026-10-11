package com.dq.vpndlnarelay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class ReadAheadTest {
    private val threads = mutableListOf<Thread>()
    private val executor = Executor { task -> threads += Thread(task).also { it.start() } }
    private fun stats() = StreamStats(0)

    @Test(timeout = 10_000)
    fun copiesEveryByteInOrderThroughASmallBuffer() {
        val data = Random(1).nextBytes(300_000)
        val out = ByteArrayOutputStream()
        val stats = stats()

        ReadAhead(capacity = 1000, chunk = 100).copy(Trickle(data), out, Long.MAX_VALUE, stats, executor)

        assertArrayEquals(data, out.toByteArray())
        assertEquals(data.size.toLong(), stats.bytesRead)
        assertEquals(data.size.toLong(), stats.bytesWritten)
    }

    @Test(timeout = 10_000)
    fun stopsAtTheLimitWithoutReadingFurther() {
        val data = Random(2).nextBytes(10_000)
        val input = ByteArrayInputStream(data)
        val out = ByteArrayOutputStream()

        ReadAhead(capacity = 4096, chunk = 512).copy(input, out, 6_000, stats(), executor)

        assertArrayEquals(data.copyOf(6_000), out.toByteArray())
        assertEquals(4_000, input.available())
    }

    @Test(timeout = 10_000)
    fun emptyBodyWritesNothing() {
        val out = ByteArrayOutputStream()

        ReadAhead(capacity = 1024).copy(ByteArrayInputStream(ByteArray(0)), out, Long.MAX_VALUE, stats(), executor)

        assertEquals(0, out.size())
    }

    @Test(timeout = 10_000)
    fun deliversBufferedDataThenRethrowsUpstreamFailure() {
        val failure = IOException("vpn dropped")
        val input = object : InputStream() {
            var sent = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent >= 2_500) throw failure
                val n = minOf(len, 500)
                b.fill(7, off, off + n)
                sent += n
                return n
            }
        }
        val out = ByteArrayOutputStream()

        try {
            ReadAhead(capacity = 4096, chunk = 512).copy(input, out, Long.MAX_VALUE, stats(), executor)
            fail("expected the upstream failure")
        } catch (e: IOException) {
            assertSame(failure, e)
        }
        assertEquals(2_500, out.size())
    }

    @Test(timeout = 10_000)
    fun clientFailureStopsTheReader() {
        val endless = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        val failing = object : OutputStream() {
            var written = 0
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written > 10_000) throw IOException("client went away")
                written += len
            }
        }

        try {
            ReadAhead(capacity = 4096, chunk = 512).copy(endless, failing, Long.MAX_VALUE, stats(), executor)
            fail("expected the client failure")
        } catch (e: IOException) {
            assertEquals("client went away", e.message)
        }
        threads.single().join(5_000)
        assertFalse("reader thread should have exited", threads.single().isAlive)
    }

    @Test
    fun rejectedReaderLeavesStreamsUntouched() {
        val input = ByteArrayInputStream(ByteArray(100))
        val out = ByteArrayOutputStream()

        try {
            ReadAhead(capacity = 1024).copy(input, out, Long.MAX_VALUE, stats(), Executor { throw RejectedExecutionException() })
            fail("expected rejection")
        } catch (e: RejectedExecutionException) {
            // Caller falls back to a direct copy.
        }
        assertEquals(100, input.available())
        assertEquals(0, out.size())
    }

    @Test(timeout = 10_000)
    fun readerKeepsFillingWhileClientIsBlocked() {
        val data = Random(3).nextBytes(8_192)
        val input = ByteArrayInputStream(data)
        val release = CountDownLatch(1)
        val out = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                release.await()
                super.write(b, off, len)
            }
        }
        val stats = stats()

        val copier = Thread { ReadAhead(capacity = 16_384, chunk = 1024).copy(input, out, Long.MAX_VALUE, stats, executor) }
        copier.start()
        while (stats.bytesRead < data.size) Thread.sleep(5)
        assertTrue(stats.bytesWritten == 0L)
        release.countDown()
        copier.join(5_000)

        assertArrayEquals(data, out.toByteArray())
    }

    @Test(timeout = 10_000)
    fun stallIsCoveredWhenTheClientKeepsReceivingBufferedData() {
        val clock = AtomicLong()
        val stats = StreamStats(0) { clock.get() }
        val inFirstWrite = CountDownLatch(1)
        val finishFirstWrite = CountDownLatch(1)
        val inSecondWrite = CountDownLatch(1)
        val finishSecondWrite = CountDownLatch(1)
        val out = object : OutputStream() {
            var writes = 0
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray, off: Int, len: Int) {
                when (++writes) {
                    1 -> { inFirstWrite.countDown(); finishFirstWrite.await() }
                    2 -> { inSecondWrite.countDown(); finishSecondWrite.await() }
                }
            }
        }
        val input = Scripted(
            { 4000 },
            { inFirstWrite.await(); 2000 },
            {
                // The VPN stalls while the client finishes one chunk and starts on the next.
                clock.addAndGet(2 * S)
                finishFirstWrite.countDown()
                inSecondWrite.await()
                1000
            },
            { finishSecondWrite.countDown(); -1 },
        )

        ReadAhead(capacity = 16_384, chunk = 1024).copy(input, out, Long.MAX_VALUE, stats, executor)

        assertEquals(1, stats.stalls)
        assertEquals(1, stats.coveredStalls)
    }

    @Test(timeout = 10_000)
    fun stallIsNotCoveredWhenTheBufferRunsDry() {
        val clock = AtomicLong()
        val stats = StreamStats(0) { clock.get() }
        val input = Scripted(
            { 1000 },
            {
                while (stats.bytesWritten < 1000) Thread.sleep(5)
                Thread.sleep(100) // let the client side find the buffer empty
                clock.addAndGet(2 * S)
                -1
            },
        )

        ReadAhead(capacity = 4096, chunk = 512).copy(input, ByteArrayOutputStream(), Long.MAX_VALUE, stats, executor)

        assertEquals(1, stats.stalls)
        assertEquals(0, stats.coveredStalls)
    }

    @Test(timeout = 10_000)
    fun stallWithAnEmptyBufferIsNotCovered() {
        val clock = AtomicLong()
        val stats = StreamStats(0) { clock.get() }
        val input = Scripted({ clock.addAndGet(2 * S); 1000 }, { -1 })

        ReadAhead(capacity = 4096, chunk = 512).copy(input, ByteArrayOutputStream(), Long.MAX_VALUE, stats, executor)

        assertEquals(1, stats.stalls)
        assertEquals(0, stats.coveredStalls)
    }

    /** Each read runs the next step, which returns how many bytes to deliver (or -1). */
    private class Scripted(vararg steps: () -> Int) : InputStream() {
        private val steps = steps.iterator()
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (steps.hasNext()) steps.next()().coerceAtMost(len) else -1
    }

    private companion object {
        const val S = 1_000_000_000L
    }

    /** Returns data in small, irregular pieces, like a socket. */
    private class Trickle(private val data: ByteArray) : InputStream() {
        private var pos = 0
        private val random = Random(42)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos == data.size) return -1
            val n = minOf(len, data.size - pos, 1 + random.nextInt(700))
            data.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }
}
