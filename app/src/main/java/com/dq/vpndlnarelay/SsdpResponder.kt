package com.dq.vpndlnarelay

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Advertises the relay as a UPnP MediaServer on every local (non-VPN, non-cellular) interface and
 * answers M-SEARCH probes, pointing clients at the HTTP relay.
 */
class SsdpResponder(
    private val uuid: String,
    private val httpPort: Int,
    private val log: (String) -> Unit,
    private val onAddressesChanged: (List<String>) -> Unit,
) {
    private val group = InetAddress.getByName(GROUP)
    private val groupSocketAddress = InetSocketAddress(group, PORT)
    private val socket = MulticastSocket(null as SocketAddress?)
    private var lans: List<LanInterfaces.Lan> = emptyList()
    @Volatile private var running = false
    private var worker: Thread? = null

    private val targets = listOf(
        "upnp:rootdevice",
        "uuid:$uuid",
        "urn:schemas-upnp-org:device:MediaServer:1",
        "urn:schemas-upnp-org:service:ContentDirectory:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1",
        "urn:microsoft.com:service:X_MS_MediaReceiverRegistrar:1",
    )

    fun start() {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(PORT))
        socket.soTimeout = 1000
        running = true
        worker = thread(name = "relay-ssdp") { loop() }
    }

    fun stop() {
        running = false
        try {
            worker?.join(3000)
            notifyAll("ssdp:byebye")
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        socket.close()
    }

    private fun loop() {
        val packet = DatagramPacket(ByteArray(2048), 2048)
        var lastRefresh = 0L
        var lastAlive = 0L
        while (running) {
            val now = System.nanoTime() / 1_000_000
            try {
                if (now - lastRefresh >= REFRESH_MS) {
                    lastRefresh = now
                    if (refreshInterfaces()) lastAlive = 0
                }
                if (now - lastAlive >= ALIVE_MS) {
                    lastAlive = now
                    notifyAll("ssdp:alive")
                }
                packet.length = packet.data.size
                socket.receive(packet)
                answer(packet)
            } catch (e: SocketTimeoutException) {
                // Poll tick.
            } catch (e: IOException) {
                if (running) log("SSDP error: ${e.message}")
            }
        }
    }

    /** Re-reads the interface list; returns true when it changed. */
    private fun refreshInterfaces(): Boolean {
        val current = LanInterfaces.current()
        val before = lans.map { "${it.nif.name}/${it.address.hostAddress}" }
        val after = current.map { "${it.nif.name}/${it.address.hostAddress}" }
        if (before == after) return false
        for (lan in lans) {
            try {
                socket.leaveGroup(groupSocketAddress, lan.nif)
            } catch (_: IOException) {
            }
        }
        lans = current.filter { lan ->
            try {
                socket.joinGroup(groupSocketAddress, lan.nif)
                true
            } catch (e: IOException) {
                log("Cannot join SSDP group on ${lan.nif.name}: ${e.message}")
                false
            }
        }
        log(if (lans.isEmpty()) "No Wi-Fi network to share on" else "Sharing on ${after.joinToString()}")
        onAddressesChanged(lans.map { "${it.address.hostAddress}" })
        return true
    }

    private fun answer(packet: DatagramPacket) {
        val lines = String(packet.data, 0, packet.length, Charsets.ISO_8859_1).split("\r\n")
        if (!lines[0].startsWith("M-SEARCH")) return
        val st = lines.firstOrNull { it.startsWith("ST:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: return
        val lan = lanFor(packet.address) ?: return
        val matches = if (st == "ssdp:all") targets else targets.filter { it == st }
        for (target in matches) {
            val reply = "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=$MAX_AGE\r\n" +
                "DATE: ${httpDate()}\r\n" +
                "EXT:\r\n" +
                "LOCATION: ${location(lan)}\r\n" +
                "SERVER: $SERVER\r\n" +
                "ST: $target\r\n" +
                "USN: ${usn(target)}\r\n\r\n"
            val bytes = reply.toByteArray(Charsets.ISO_8859_1)
            socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
        }
    }

    private fun notifyAll(nts: String) {
        for (lan in lans) {
            MulticastSocket().use { out ->
                out.networkInterface = lan.nif
                out.timeToLive = 2
                for (target in targets) {
                    val message = "NOTIFY * HTTP/1.1\r\n" +
                        "HOST: $GROUP:$PORT\r\n" +
                        "CACHE-CONTROL: max-age=$MAX_AGE\r\n" +
                        "LOCATION: ${location(lan)}\r\n" +
                        "NT: $target\r\n" +
                        "NTS: $nts\r\n" +
                        "SERVER: $SERVER\r\n" +
                        "USN: ${usn(target)}\r\n\r\n"
                    val bytes = message.toByteArray(Charsets.ISO_8859_1)
                    out.send(DatagramPacket(bytes, bytes.size, groupSocketAddress))
                }
            }
        }
    }

    private fun lanFor(peer: InetAddress): LanInterfaces.Lan? = lans.firstOrNull { it.contains(peer) }

    private fun location(lan: LanInterfaces.Lan) = "http://${lan.address.hostAddress}:$httpPort${Upstream.DESCRIPTION_PATH}"

    private fun usn(target: String) = if (target.startsWith("uuid:")) target else "uuid:$uuid::$target"

    private fun httpDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())

    companion object {
        private const val GROUP = "239.255.255.250"
        private const val PORT = 1900
        private const val MAX_AGE = 1800
        private const val REFRESH_MS = 5_000L
        private const val ALIVE_MS = 60_000L
        private const val SERVER = "Android/1.0 UPnP/1.0 DLNADOC/1.50 VPNDLNARelay/1.0"
    }
}
