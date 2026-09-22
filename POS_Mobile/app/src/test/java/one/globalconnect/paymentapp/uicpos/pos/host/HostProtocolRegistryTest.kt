package one.globalconnect.paymentapp.uicpos.pos.host

import one.globalconnect.paymentapp.uicpos.pos.host.protocol.BanpaisGl
import one.globalconnect.paymentapp.uicpos.pos.host.protocol.BanpaisIo
import one.globalconnect.paymentapp.uicpos.pos.host.protocol.Isswitch
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostProtocolRegistryTest {
    @Test
    fun schemaValuesSelectDistinctProtocolsWithIdenticalFraming() {
        val expected = mapOf("isswitch" to Isswitch::class.java,
            "banpaisgl" to BanpaisGl::class.java, "banpaisio" to BanpaisIo::class.java)
        val ids = expected.map { (name, implementation) ->
            val id = TMS_Acquirer(hostProtocol = name).HostProtocol
            assertEquals(implementation, HostProtocolRegistry.protocolFor(id)?.javaClass)
            assertEquals(LengthPrefixRegistry.resolve(12L, null), LengthPrefixRegistry.resolve(id, null))
            assertEquals(id, TMS_Acquirer(hostProtocol = " ${name.uppercase()} ").HostProtocol)
            assertEquals(id, TMS_Acquirer(hostProtocol = id.toString()).HostProtocol)
            id
        }
        assertEquals(3, ids.distinct().size)
        assertEquals(12L, TMS_Acquirer(hostProtocol = "isswitch").HostProtocol)
    }

    @Test
    fun unsupportedValuesDoNotSilentlyUseIsswitch() {
        for (value in listOf("", "unknown", "999")) {
            assertNull(HostProtocolRegistry.protocolFor(TMS_Acquirer(hostProtocol = value).HostProtocol))
        }
    }
}
