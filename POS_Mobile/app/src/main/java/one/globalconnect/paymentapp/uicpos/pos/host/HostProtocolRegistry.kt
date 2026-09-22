package one.globalconnect.paymentapp.uicpos.pos.host

import one.globalconnect.paymentapp.uicpos.pos.host.protocol.Isswitch
import one.globalconnect.paymentapp.uicpos.pos.host.protocol.BanpaisGl
import one.globalconnect.paymentapp.uicpos.pos.host.protocol.BanpaisIo
import java.util.Locale

/**
 * Centralises the mapping between the host protocol identifier declared in the
 * TMS configuration and the implementation capable of generating ISO8583
 * requests for that host.
 */
object HostProtocolRegistry {

    const val ISSWITCH = 12L
    const val BANPAIS_GL = 13L
    const val BANPAIS_IO = 14L

    private val protocols: Map<Long, HostProtocol> = mapOf(
        ISSWITCH to Isswitch(),
        BANPAIS_GL to BanpaisGl(),
        BANPAIS_IO to BanpaisIo(),
    )

    fun resolveId(value: String): Long = value.trim().toLongOrNull() ?: when (value.trim().lowercase(Locale.ROOT)) {
        "isswitch" -> ISSWITCH
        "banpaisgl" -> BANPAIS_GL
        "banpaisio" -> BANPAIS_IO
        else -> 0L
    }

    fun protocolFor(hostProtocolId: Long): HostProtocol? = protocols[hostProtocolId]
}
