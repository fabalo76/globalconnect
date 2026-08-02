package one.globalconnect.pinpad.transport

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanNetworkAddressesTest {
    @Test
    fun subnetMatchingUsesPrefixLength() {
        val address = LanAddress(ipv4("192.168.20.10"), 24)

        assertTrue(address.isInSameSubnet(ipv4("192.168.20.200")))
        assertFalse(address.isInSameSubnet(ipv4("192.168.21.10")))
    }

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByName(value) as Inet4Address
}
