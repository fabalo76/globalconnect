package one.globalconnect.paymentapp.uicpos.pos.host

import one.globalconnect.paymentapp.uicpos.pos.host.protocol.Isswitch

/**
 * Centralises the mapping between the host protocol identifier declared in the
 * TMS configuration and the implementation capable of generating ISO8583
 * requests for that host.
 */
object HostProtocolRegistry {

    private val protocols: Map<Long, HostProtocol> = mapOf(
        12L to Isswitch()
    )

    fun protocolFor(hostProtocolId: Long): HostProtocol? = protocols[hostProtocolId]
}
