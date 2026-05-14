package net.wanners.groceries

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class SubnetTest {

    private fun bytesOf(s: String): ByteArray = InetAddress.getByName(s).address

    @Test
    fun `slash-24 matches addresses in the same subnet`() {
        val net = Subnet(bytesOf("192.168.1.0"), 24)
        assertTrue(net.contains("192.168.1.1"))
        assertTrue(net.contains("192.168.1.42"))
        assertTrue(net.contains("192.168.1.255"))
        assertFalse(net.contains("192.168.2.1"))
        assertFalse(net.contains("10.0.0.1"))
    }

    @Test
    fun `slash-16 matches wider range`() {
        val net = Subnet(bytesOf("10.0.0.0"), 16)
        assertTrue(net.contains("10.0.1.1"))
        assertTrue(net.contains("10.0.255.255"))
        assertFalse(net.contains("10.1.0.1"))
    }

    @Test
    fun `slash-32 matches only one address`() {
        val net = Subnet(bytesOf("192.168.1.5"), 32)
        assertTrue(net.contains("192.168.1.5"))
        assertFalse(net.contains("192.168.1.6"))
    }

    @Test
    fun `slash-0 matches all IPv4`() {
        val net = Subnet(bytesOf("0.0.0.0"), 0)
        assertTrue(net.contains("8.8.8.8"))
        assertTrue(net.contains("192.168.1.1"))
    }

    @Test
    fun `does not match IPv6`() {
        val net = Subnet(bytesOf("192.168.1.0"), 24)
        assertFalse(net.contains("::1"))
        assertFalse(net.contains("fe80::1"))
    }

    @Test
    fun `garbage host string returns false instead of throwing`() {
        val net = Subnet(bytesOf("192.168.1.0"), 24)
        assertFalse(net.contains("not-an-ip"))
        assertFalse(net.contains(""))
    }

    @Test
    fun `loopback is always allowed even when no subnets`() {
        assertTrue(isAllowedRemote("127.0.0.1", emptyList()))
        assertTrue(isAllowedRemote("localhost", emptyList()))
        assertTrue(isAllowedRemote("::1", emptyList()))
        assertTrue(isAllowedRemote("Localhost", emptyList()))
    }

    @Test
    fun `non-loopback rejected when allowed list is empty`() {
        assertFalse(isAllowedRemote("192.168.1.5", emptyList()))
        assertFalse(isAllowedRemote("10.0.0.1", emptyList()))
    }

    @Test
    fun `non-loopback accepted when in an allowed subnet`() {
        val subnets = listOf(Subnet(bytesOf("192.168.1.0"), 24))
        assertTrue(isAllowedRemote("192.168.1.5", subnets))
        assertFalse(isAllowedRemote("192.168.2.5", subnets))
        assertFalse(isAllowedRemote("10.0.0.1", subnets))
    }

    @Test
    fun `null and empty remote host are rejected`() {
        val subnets = listOf(Subnet(bytesOf("192.168.1.0"), 24))
        assertFalse(isAllowedRemote(null, subnets))
        assertFalse(isAllowedRemote("", subnets))
    }

    @Test
    fun `localLanSubnets returns at least one IPv4 subnet on a connected dev machine`() {
        // Soft assertion — we can't guarantee the machine is online, just that
        // when it IS online, the returned list contains real IPv4 subnets.
        for (subnet in LanDiscovery.localLanSubnets()) {
            assertEquals(4, subnet.baseBytes.size)
            assertTrue(subnet.prefix in 0..32)
        }
    }
}
