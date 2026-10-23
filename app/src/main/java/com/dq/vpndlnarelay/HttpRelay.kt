package com.dq.vpndlnarelay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * HTTP reverse proxy in front of the upstream media server. XML responses are rewritten so that
 * every URL points back at this relay; everything else (media, artwork) is streamed through.
 */
class HttpRelay(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val upstreamUuid: String,
    private val relayUuid: String,
    private val phoneOnly: Boolean,
    private val log: (String) -> Unit,
) {
    private val upstreamAuthority = "$upstreamHost:$upstreamPort"
    private val server = ServerSocket()
    private val pool: ExecutorService =
        ThreadPoolExecutor(0, MAX_CLIENTS, 60, TimeUnit.SECONDS, SynchronousQueue())

    val port: Int get() = server.localPort

    fun start() {
        server.reuseAddress = true
        // Phone-only mode listens on loopback, so nothing off the device can even connect.
        val address = if (phoneOnly) InetAddress.getByName(LanInterfaces.LOOPBACK) else null
        try {
            server.bind(InetSocketAddress(address, PREFERRED_PORT))
        } catch (e: IOException) {
            server.bind(InetSocketAddress(address, 0))
        }
        thread(name = "relay-accept") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                try {
                    pool.execute { serve(client) }
                } catch (e: RejectedExecutionException) {
                    client.close()
                }
            }
        }
    }

    fun stop() {
        try {
            server.close()
        } catch (_: IOException) {
        }
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                // The LAN listener is on every interface; only serve peers on a local network.
                val allowed = if (phoneOnly) client.inetAddress.isLoopbackAddress
                else LanInterfaces.sameLan(client.localAddress, client.inetAddress)
                if (!allowed) return
                client.soTimeout = CLIENT_HEAD_TIMEOUT_MS
                client.tcpNoDelay = true
                val input = BufferedInputStream(client.getInputStream(), BUFFER)
                val output = client.getOutputStream()
                val head = readHead(input) ?: return
                client.soTimeout = 0
                val requestLine = head.first().split(" ", limit = 3)
                if (requestLine.size < 3) return
                val relayAuthority = "${client.localAddress.hostAddress}:${client.localPort}"
                handle(requestLine, head.drop(1), input, output, relayAuthority)
            }
        } catch (e: IOException) {
            // Clients routinely drop connections mid-stream (seeking, stopping playback).
        }
    }

    private fun handle(
        requestLine: List<String>,
        headers: List<String>,
        clientIn: InputStream,
        clientOut: OutputStream,
        relayAuthority: String,
    ) {
        val method = requestLine[0].uppercase()
        // Eventing is answered locally: the upstream server could not call back to LAN clients.
        if (method == "SUBSCRIBE" || method == "UNSUBSCRIBE") {
            val sid = header(headers, "SID") ?: "uuid:${UUID.randomUUID()}"
            val extra = if (method == "SUBSCRIBE") "SID: $sid\r\nTIMEOUT: Second-1800\r\n" else ""
            clientOut.write(
                "HTTP/1.1 200 OK\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toLatin1()
            )
            return
        }
        if (header(headers, "Expect")?.contains("100-continue", ignoreCase = true) == true) {
            clientOut.write("HTTP/1.1 100 Continue\r\n\r\n".toLatin1())
            clientOut.flush()
        }

        val path = requestLine[1].replace(relayUuid, upstreamUuid)
        val requestStarted = System.nanoTime()
        Socket().use { upstream ->
            try {
                upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), UPSTREAM_CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                log("Upstream connect failed: ${e.message}")
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toLatin1())
                return
            }
            upstream.soTimeout = UPSTREAM_READ_TIMEOUT_MS
            upstream.tcpNoDelay = true
            val upstreamOut = upstream.getOutputStream()
            val request = StringBuilder("${requestLine[0]} $path ${requestLine[2]}\r\n")
            for (line in headers) {
                if (!isHeader(line, "Host", "Connection", "Proxy-Connection", "Keep-Alive", "Expect")) {
                    request.append(line).append("\r\n")
                }
            }
            request.append("Host: $upstreamAuthority\r\nConnection: close\r\n\r\n")
            upstreamOut.write(request.toString().toLatin1())
            header(headers, "Content-Length")?.trim()?.toLongOrNull()?.let { copy(clientIn, upstreamOut, it) }
            upstreamOut.flush()

            val upstreamIn = BufferedInputStream(upstream.getInputStream(), BUFFER)
            val responseHead = readHead(upstreamIn) ?: return
            val statusLine = responseHead.first()
            val responseHeaders = responseHead.drop(1).map { it.replace(upstreamAuthority, relayAuthority) }
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val hasBody = method != "HEAD" && status >= 200 && status != 204 && status != 304
            val length = header(responseHeaders, "Content-Length")?.trim()?.toLongOrNull()
            val isXml = header(responseHeaders, "Content-Type")?.contains("xml", ignoreCase = true) == true

            if (hasBody && isXml && (length ?: 0) <= MAX_REWRITE_BYTES) {
                val chunked = header(responseHeaders, "Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
                val raw = when {
                    chunked -> readChunked(upstreamIn)
                    length != null -> readExactly(upstreamIn, length.toInt())
                    else -> readExactly(upstreamIn, MAX_REWRITE_BYTES)
                }
                val body = rewrite(raw, relayAuthority)
                val out = StringBuilder(statusLine).append("\r\n")
                for (line in responseHeaders) {
                    if (!isHeader(line, "Content-Length", "Transfer-Encoding", "Connection")) out.append(line).append("\r\n")
                }
                out.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                clientOut.write(out.toString().toLatin1())
                clientOut.write(body)
            } else {
                val out = StringBuilder(statusLine).append("\r\n")
                for (line in responseHeaders) {
                    if (!isHeader(line, "Connection")) out.append(line).append("\r\n")
                }
                out.append("Connection: close\r\n\r\n")
                clientOut.write(out.toString().toLatin1())
                if (hasBody) stream(upstreamIn, clientOut, length ?: Long.MAX_VALUE, System.nanoTime() - requestStarted)
            }
            clientOut.flush()
        }
    }

    /** Streams a response body; large ones (media) get one line of timings logged at the end. */
    private fun stream(from: InputStream, to: OutputStream, limit: Long, firstByteNanos: Long) {
        val stats = StreamStats(firstByteNanos)
        try {
            copy(from, to, limit, stats)
        } finally {
            if (stats.bytesWritten >= MIN_LOGGED_BYTES) log(stats.summary())
        }
    }

    // Latin-1 round-trips every byte, so non-ASCII text in the XML survives the string replace.
    private fun rewrite(body: ByteArray, relayAuthority: String): ByteArray =
        String(body, Charsets.ISO_8859_1)
            .replace(upstreamAuthority, relayAuthority)
            .replace(upstreamUuid, relayUuid)
            .replace(FRIENDLY_NAME) { "<friendlyName>${it.groupValues[1]}$NAME_SUFFIX</friendlyName>" }
            .toLatin1()

    private fun readHead(input: InputStream): List<String>? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = if (b == HEAD_END[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
            if (out.size() > MAX_HEAD_BYTES) throw IOException("HTTP head too large")
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd().split("\r\n")
    }

    private fun readExactly(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        copy(input, out, limit.toLong())
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            copy(input, out, size.toLong())
            readLine(input)
            if (out.size() > MAX_REWRITE_BYTES) throw IOException("XML body too large")
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    private fun copy(from: InputStream, to: OutputStream, limit: Long, stats: StreamStats? = null) {
        val buf = ByteArray(BUFFER)
        var remaining = limit
        while (remaining > 0) {
            val readStarted = System.nanoTime()
            val n = from.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            val readDone = System.nanoTime()
            stats?.recordRead(readDone - readStarted)
            if (n < 0) break
            to.write(buf, 0, n)
            stats?.recordWrite(n, System.nanoTime() - readDone)
            remaining -= n
        }
    }

    private fun isHeader(line: String, vararg names: String): Boolean =
        names.any { line.startsWith("$it:", ignoreCase = true) }

    private fun header(headers: List<String>, name: String): String? =
        headers.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    private fun String.toLatin1() = toByteArray(Charsets.ISO_8859_1)

    companion object {
        const val PREFERRED_PORT = 49200
        const val NAME_SUFFIX = " (VPN)"
        private const val HEAD_END = "\r\n\r\n"
        private const val BUFFER = 64 * 1024
        private const val MAX_CLIENTS = 64
        // Smaller bodies are artwork, or clients probing a file before playing or after seeking.
        private const val MIN_LOGGED_BYTES = 16 * 1024 * 1024
        private const val MAX_HEAD_BYTES = 64 * 1024
        private const val MAX_REWRITE_BYTES = 32 * 1024 * 1024
        private const val CLIENT_HEAD_TIMEOUT_MS = 30_000
        private const val UPSTREAM_CONNECT_TIMEOUT_MS = 8_000
        private const val UPSTREAM_READ_TIMEOUT_MS = 60_000
        private val FRIENDLY_NAME = Regex("<friendlyName>([^<]*)</friendlyName>")
    }
}
