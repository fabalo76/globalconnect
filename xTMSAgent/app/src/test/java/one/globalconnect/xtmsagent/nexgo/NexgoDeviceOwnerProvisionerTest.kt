package one.globalconnect.xtmsagent.nexgo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NexgoDeviceOwnerProvisionerTest {
    @Test
    fun `N6ProLite candidate requires agreement between firmware and SDK`() {
        assertEquals(902_108_171,
            NexgoDeviceOwnerProvisioner.candidateOwnerCommand("N6ProLite", 90_000_000, 90_000_000))
        for (base in listOf(null, 0, 70_000_000, 80_000_000)) {
            assertEquals(null, NexgoDeviceOwnerProvisioner.candidateOwnerCommand("N6ProLite", base, 90_000_000))
            assertEquals(null, NexgoDeviceOwnerProvisioner.candidateOwnerCommand("N6ProLite", 90_000_000, base))
        }
    }

    @Test
    fun `candidate command does not expand to other models sharing the base`() {
        for (model in listOf("UNKNOWN", "N6", "N6S", "N96", "N6Pro")) {
            assertEquals(null, NexgoDeviceOwnerProvisioner.candidateOwnerCommand(model, 90_000_000, 90_000_000))
        }
    }

    @Test
    fun `device owner command matches verified Nexgo model command table`() {
        assertEquals(702_108_171, NexgoDeviceOwnerProvisioner.deviceOwnerCommand("CT20P"))
        assertEquals(702_108_171, NexgoDeviceOwnerProvisioner.deviceOwnerCommand("CT20"))
        assertEquals(802_108_171, NexgoDeviceOwnerProvisioner.deviceOwnerCommand("N6S"))
        assertEquals(802_108_171, NexgoDeviceOwnerProvisioner.deviceOwnerCommand("N82"))
        assertEquals(902_108_171, NexgoDeviceOwnerProvisioner.deviceOwnerCommand("N96"))
        assertEquals(null, NexgoDeviceOwnerProvisioner.deviceOwnerCommand("N6ProLite"))
        assertEquals(null, NexgoDeviceOwnerProvisioner.deviceOwnerCommand("UNKNOWN"))
    }

    @Test
    fun `device owner command payload uses two length-prefixed UTF-8 parameters`() {
        val packageName = "one.globalconnect.xtmsagent.globalconnect"
        val receiverClass = "one.globalconnect.xtmsagent.TmsDeviceAdminReceiver"

        val payload = NexgoDeviceOwnerProvisioner.encodeDeviceOwnerParameters(
            packageName,
            receiverClass,
        )
        val packageLength = payload[1].toInt() and 0xff
        val receiverLengthOffset = packageLength + 2
        val receiverLength = payload[receiverLengthOffset].toInt() and 0xff

        assertEquals(2, payload[0].toInt())
        assertEquals(packageName.toByteArray().size, packageLength)
        assertEquals(receiverClass.toByteArray().size, receiverLength)
        assertArrayEquals(
            packageName.toByteArray(),
            payload.copyOfRange(2, receiverLengthOffset),
        )
        assertArrayEquals(
            receiverClass.toByteArray(),
            payload.copyOfRange(receiverLengthOffset + 1, payload.size),
        )
    }
}
