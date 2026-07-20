package one.globalconnect.pinpad.transport

import android.util.Log
import one.globalconnect.pinpad.logging.PinpadTraceLog

class CompositeTransport(
    private val transports: List<PINPADTransport>,
) : PINPADTransport {
    private var active: PINPADTransport? = null

    override fun start(listener: PINPADTransport.Listener) {
        transports.forEach { transport ->
            runCatching {
                transport.start(
                    object : PINPADTransport.Listener {
                        override fun onBytesReceived(bytes: ByteArray) {
                            active = transport
                            PinpadTraceLog.transport("AUTO selected ${transport::class.java.simpleName} after RX len=${bytes.size}")
                            listener.onBytesReceived(bytes)
                        }

                        override fun onTransportError(error: Throwable) {
                            listener.onTransportError(error)
                        }
                    },
                )
            }.onFailure {
                Log.w(TAG, "Transport failed to start: ${transport::class.java.simpleName}", it)
            }
        }
    }

    override fun send(bytes: ByteArray) {
        val selected = active
        if (selected != null) {
            PinpadTraceLog.transport("AUTO TX using ${selected::class.java.simpleName} len=${bytes.size}")
            selected.send(bytes)
            return
        }
        PinpadTraceLog.transport("AUTO TX broadcasting len=${bytes.size}")
        transports.forEach { transport ->
            runCatching { transport.send(bytes) }
        }
    }

    override fun stop() {
        transports.forEach { transport ->
            runCatching { transport.stop() }
        }
        active = null
    }

    companion object {
        private const val TAG = "CompositeTransport"
    }
}
