package one.globalconnect.paymentapp.uicpos.pos.host

import android.util.Log
import com.uic.pos.iso8583.IsoMessage
import com.uic.pos.iso8583.IsoMessageFactory
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.util.LogSanitizer

/**
 * Helper that ties together the acquirer configuration and the host protocol
 * implementation to produce an ISO8583 request message.
 */
class HostMessageBuilder(
    private val registry: HostProtocolRegistry = HostProtocolRegistry
) {

    @Throws(HostProtocolException::class)
    fun build(
        acquirer: TMS_Acquirer,
        terminal: TMS_Terminal,
        procInfo: ProcInfo,
        isoFactory: IsoMessageFactory? = null,
        stanSupplier: () -> String = { StanProvider.nextStan() },
        timestampSupplier: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() },
    ): IsoMessage {
        Log.d(TAG, "Preparing host message for acquirer=${acquirer.AcquirerName} (${acquirer.AcqID})")
        Log.d(
            TAG,
            "Acquirer configuration: NII=${acquirer.NII} terminalId=${acquirer.AcqTermID} merchantId=${acquirer.MerchID} hostProtocol=${acquirer.HostProtocol}"
        )
        Log.d(TAG, "Transaction log snapshot: ${LogSanitizer.sanitizeTransLog(procInfo.TransLog)}")

        val protocol = registry.protocolFor(acquirer.HostProtocol)
            ?: throw HostProtocolException("Unsupported host protocol: ${acquirer.HostProtocol}")
        Log.d(TAG, "Using host protocol implementation: ${protocol::class.java.simpleName}")

        val context = HostProtocolContext(
            procInfo = procInfo,
            acquirer = acquirer,
            terminal = terminal,
            isoFactory = isoFactory,
            stanSupplier = stanSupplier,
            timestampSupplier = timestampSupplier,
        )
        val message = protocol.buildIsoMessage(context)
        Log.d(TAG, "ISO8583 request generated successfully for acquirer=${acquirer.AcquirerName}")
        return message
    }

    companion object {
        private const val TAG = "HostMessageBuilder"
    }
}
