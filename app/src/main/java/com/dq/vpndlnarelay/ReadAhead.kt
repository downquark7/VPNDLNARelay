package com.dq.vpndlnarelay

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Copies a response body through a fixed-size ring buffer while a second thread reads ahead from
 * upstream, so a short VPN stall is covered by data that already arrived.
 *
 * Both threads block on the socket or on the buffer and are woken only by data moving, so there is
 * no polling. The reader resumes only once a full chunk of space is free, which keeps hand-offs
 * between the threads to one per chunk instead of one per write.
 */
class ReadAhead(private val capacity: Int, private val chunk: Int = minOf(64 * 1024, capacity)) {
    private val buf = ByteArray(capacity)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()
    private var head = 0
    private var count = 0
    private var eof = false
    private var closed = false
    private var error: IOException? = null

    /**
     * Copies up to [limit] bytes from [from] to [to]. The reader runs on [executor], which may throw
     * RejectedExecutionException before anything is read or written. Blocks until the body has been
     * written; a failure on either side is rethrown here once the other side is told to stop.
     */
    fun copy(from: InputStream, to: OutputStream, limit: Long, stats: StreamStats, executor: Executor) {
        executor.execute { fill(from, limit, stats) }
        try {
            drain(to, stats)
        } finally {
            lock.withLock {
                closed = true
                notFull.signal()
            }
        }
    }

    private fun fill(from: InputStream, limit: Long, stats: StreamStats) {
        var remaining = limit
        try {
            while (remaining > 0) {
                val tail: Int
                val space: Int
                lock.withLock {
                    while (capacity - count < chunk && !closed) notFull.await()
                    if (closed) return
                    tail = (head + count) % capacity
                    space = minOf(capacity - count, capacity - tail)
                }
                // The free region is only touched by this thread, so it is filled outside the lock.
                val started = stats.now()
                val n = from.read(buf, tail, minOf(space.toLong(), remaining).toInt())
                stats.recordRead(n, stats.now() - started)
                if (n < 0) break
                remaining -= n
                lock.withLock {
                    count += n
                    notEmpty.signal()
                }
            }
        } catch (e: IOException) {
            lock.withLock { error = e }
        } catch (e: InterruptedException) {
            lock.withLock { error = InterruptedIOException() }
        } finally {
            lock.withLock {
                eof = true
                notEmpty.signal()
            }
        }
    }

    private fun drain(to: OutputStream, stats: StreamStats) {
        while (true) {
            val start: Int
            val n: Int
            lock.withLock {
                if (count == 0 && !eof) {
                    val waitStarted = stats.now()
                    try {
                        while (count == 0 && !eof) notEmpty.await()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException()
                    }
                    stats.recordEmpty(stats.now() - waitStarted)
                }
                if (count == 0) {
                    error?.let { throw it }
                    return
                }
                start = head
                n = minOf(count, capacity - head)
            }
            val started = stats.now()
            to.write(buf, start, n)
            stats.recordWrite(n, stats.now() - started)
            lock.withLock {
                head = (head + n) % capacity
                count -= n
                if (capacity - count >= chunk) notFull.signal()
            }
        }
    }
}
