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
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
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
        val release = java.util.concurrent.CountDownLatch(1)
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
