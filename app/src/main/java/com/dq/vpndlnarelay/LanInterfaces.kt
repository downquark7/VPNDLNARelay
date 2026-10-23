package com.dq.vpndlnarelay

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Decides which interfaces the relay is offered on: local networks only, never VPN or cellular. */
object LanInterfaces {
    class Lan(val nif: NetworkInterface, val address: Inet4Address, val prefix: Int) {
        fun contains(peer: InetAddress): Boolean {
            val p = (peer as? Inet4Address)?.address ?: return false
            val a = address.address
            return (0 until prefix).all { bit ->
                val mask = 0x80 shr (bit % 8)
                (a[bit / 8].toInt() and mask) == (p[bit / 8].toInt() and mask)
            }
        }
    }

    const val LOOPBACK = "127.0.0.1"

    private val EXCLUDED_PREFIXES = listOf("tun", "ppp", "wg", "rmnet", "ccmni", "dummy", "epdg", "v4-", "p2p")

    fun current(): List<Lan> =
        NetworkInterface.getNetworkInterfaces().asSequence().mapNotNull { lanOf(it) }.toList()

    /** True when [local] is this device's address on a LAN interface and [peer] is on that same subnet. */
    fun sameLan(local: InetAddress, peer: InetAddress): Boolean {
        val lan = NetworkInterface.getByInetAddress(local)?.let { lanOf(it) } ?: return false
        return lan.address == local && lan.contains(peer)
    }

    /** True when [peer] is one of this device's own addresses, i.e. an app on the phone. */
    fun isOwnAddress(peer: InetAddress): Boolean =
        peer.isLoopbackAddress || NetworkInterface.getByInetAddress(peer) != null

    private fun lanOf(nif: NetworkInterface): Lan? {
        if (!nif.isUp || !nif.supportsMulticast() || nif.isLoopback || nif.isPointToPoint) return null
        if (EXCLUDED_PREFIXES.any { nif.name.startsWith(it) }) return null
        return nif.interfaceAddresses.firstOrNull { it.address is Inet4Address }
            ?.let { Lan(nif, it.address as Inet4Address, it.networkPrefixLength.toInt()) }
    }
}
