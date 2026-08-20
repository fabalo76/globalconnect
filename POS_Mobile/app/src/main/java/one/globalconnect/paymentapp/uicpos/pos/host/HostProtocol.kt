package one.globalconnect.paymentapp.uicpos.pos.host

import com.uic.pos.iso8583.IsoMessage
import com.uic.pos.iso8583.IsoMessageFactory
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo

/**
 * Describes a component capable of translating the in-memory transaction
 * representation into an ISO8583 message for a specific host.
 */
interface HostProtocol {

    /**
     * Builds the ISO8583 request that should be transmitted to the remote host
     * using the information contained in the supplied [context].
     *
     * @throws HostProtocolException when the request cannot be generated.
     */
    @Throws(HostProtocolException::class)
    fun buildIsoMessage(context: HostProtocolContext): IsoMessage
}

/**
 * Bundles together the data required by a host protocol implementation to
 * produce an ISO8583 request message.
 *
 * The ISO message factory can be supplied when tests or callers need a
 * specialised configuration; otherwise each protocol will lazily load its
 * preferred asset.
 */
data class HostProtocolContext(
    val procInfo: ProcInfo,
    val acquirer: TMS_Acquirer,
    val terminal: TMS_Terminal,
    val isoFactory: IsoMessageFactory? = null,
    val stanSupplier: () -> String = { StanProvider.nextStan() },
    val timestampSupplier: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() }
)

/**
 * Exception raised when the host protocol is unable to create a valid ISO8583
 * request message.
 */
class HostProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
