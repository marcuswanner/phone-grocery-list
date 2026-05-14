package net.wanners.groceries

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * IPv4 CIDR subnet. Matches by ANDing both addresses with the prefix mask.
 * IPv6 is intentionally not represented: typical home LANs are IPv4 and we allow ::1 separately.
 */
data class Subnet(val baseBytes: ByteArray, val prefix: Int) {
    init {
        require(baseBytes.size == 4) { "only IPv4 supported" }
        require(prefix in 0..32) { "prefix must be 0..32" }
        // Reject overly broad subnets. A real home/office interface has prefix >= 8
        // (most are /16 or /24); anything below that is almost certainly user-supplied
        // garbage that would accept the entire IPv4 space as "local".
        require(prefix >= 8) { "prefix too broad" }
    }

    fun contains(addr: InetAddress): Boolean {
        if (addr !is Inet4Address) return false
        val ip = addr.address
        var bitsLeft = prefix
        for (i in baseBytes.indices) {
            if (bitsLeft <= 0) return true
            val maskByte =
                if (bitsLeft >= 8) 0xFF
                else (0xFF shl (8 - bitsLeft)) and 0xFF
            if ((baseBytes[i].toInt() and maskByte) != (ip[i].toInt() and maskByte)) return false
            bitsLeft -= 8
        }
        return true
    }

    fun contains(addr: String): Boolean = runCatching { contains(InetAddress.getByName(addr)) }.getOrDefault(false)

    override fun toString(): String {
        val str = (0..3).joinToString(".") { (baseBytes[it].toInt() and 0xFF).toString() }
        return "$str/$prefix"
    }

    override fun equals(other: Any?): Boolean =
        other is Subnet && other.prefix == prefix && other.baseBytes.contentEquals(baseBytes)

    override fun hashCode(): Int = baseBytes.contentHashCode() * 31 + prefix
}

object LanDiscovery {
    /**
     * IPv4 subnets attached to the current "real" network interfaces — excludes loopback,
     * virtual interfaces, and link-local (169.254/16). Caller is expected to always allow
     * loopback separately.
     */
    fun localLanSubnets(): List<Subnet> = buildList {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return@buildList
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            for (ifaceAddr in iface.interfaceAddresses) {
                val addr = ifaceAddr.address ?: continue
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val prefix = ifaceAddr.networkPrefixLength.toInt()
                if (prefix !in 0..32) continue
                add(Subnet(networkBase(addr.address, prefix), prefix))
            }
        }
    }

    private fun networkBase(ip: ByteArray, prefix: Int): ByteArray {
        val out = ByteArray(ip.size)
        var bitsLeft = prefix
        for (i in ip.indices) {
            val maskByte =
                if (bitsLeft >= 8) 0xFF
                else if (bitsLeft <= 0) 0
                else (0xFF shl (8 - bitsLeft)) and 0xFF
            out[i] = (ip[i].toInt() and maskByte).toByte()
            bitsLeft -= 8
        }
        return out
    }
}

fun isLoopbackHost(host: String?): Boolean {
    if (host.isNullOrEmpty()) return false
    if (host.equals("localhost", ignoreCase = true)) return true
    return runCatching {
        val addr = InetAddress.getByName(host)
        addr.isLoopbackAddress ||
            (addr is Inet6Address && addr.isLoopbackAddress)
    }.getOrDefault(false)
}

/**
 * Returns true if a request from [remoteHost] should be allowed. Loopback is always allowed
 * (so the phone hitting itself via the PWA works regardless of LAN state). Otherwise the
 * caller must be on one of [allowedSubnets].
 */
fun isAllowedRemote(remoteHost: String?, allowedSubnets: List<Subnet>): Boolean {
    if (remoteHost.isNullOrEmpty()) return false
    if (isLoopbackHost(remoteHost)) return true
    return allowedSubnets.any { it.contains(remoteHost) }
}
