package com.dq.vpndlnarelay

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class ServerInfo(val port: Int, val uuid: String, val name: String)

object Upstream {
    const val DESCRIPTION_PATH = "/DeviceDescription.xml"

    private val UDN = Regex("<UDN>\\s*uuid:([^<\\s]+)\\s*</UDN>")
    private val NAME = Regex("<friendlyName>([^<]*)</friendlyName>")

    /** Returns the media server's details if [port] on [host] serves a DLNA MediaServer description. */
    fun probe(host: String, port: Int, timeoutMs: Int = 4000): ServerInfo? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().write(
                "GET $DESCRIPTION_PATH HTTP/1.0\r\nHost: $host:$port\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
            )
            val text = String(readUpTo(socket, 256 * 1024), Charsets.UTF_8)
            val uuid = UDN.find(text)?.groupValues?.get(1)
            if (uuid == null || !text.contains(":device:MediaServer:")) null
            else ServerInfo(port, uuid, NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { host })
        }
    } catch (e: IOException) {
        null
    }

    private fun readUpTo(socket: Socket, limit: Int): ByteArray {
        val input = socket.getInputStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

object PortScanner {
    class Result(val server: ServerInfo?, val hostResponded: Boolean)

    // Kodi's UPnP library picks its port from this range, so it is searched first.
    private val LIKELY = 1024..2047
    private const val THREADS = 64
    private const val CONNECT_TIMEOUT_MS = 1500

    /** Blocking; interrupt the calling thread to cancel. */
    @Throws(InterruptedException::class)
    fun scan(host: String, onProgress: (String) -> Unit = {}): Result {
        onProgress("Scanning ports ${LIKELY.first}-${LIKELY.last}")
        val first = scanRange(host, LIKELY.asSequence())
        if (first.server != null || !first.hostResponded) return first
        onProgress("Not in the usual range, scanning all ports")
        return scanRange(host, (1..65535).asSequence().filter { it !in LIKELY })
    }

    private fun scanRange(host: String, ports: Sequence<Int>): Result {
        val pool = Executors.newFixedThreadPool(THREADS)
        val found = AtomicReference<ServerInfo?>(null)
        val responded = AtomicInteger(0)
        try {
            for (port in ports) {
                pool.execute {
                    if (found.get() != null || Thread.currentThread().isInterrupted) return@execute
                    if (isOpen(host, port, responded)) {
                        Upstream.probe(host, port)?.let { found.compareAndSet(null, it) }
                    }
                }
            }
            pool.shutdown()
            while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                if (found.get() != null) break
            }
        } finally {
            pool.shutdownNow()
        }
        return Result(found.get(), responded.get() > 0)
    }

    private fun isOpen(host: String, port: Int, responded: AtomicInteger): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        responded.incrementAndGet()
        true
    } catch (e: ConnectException) {
        // An active refusal still proves the host is reachable.
        if (e.message?.contains("refused", ignoreCase = true) == true) responded.incrementAndGet()
        false
    } catch (e: IOException) {
        false
    }
}
