package one.globalconnect.pinpad.transport

import android.util.Log

class NoOpTransport(
    private val startupError: Throwable,
) : PINPADTransport {
    private var listener: PINPADTransport.Listener? = null

    override fun start(listener: PINPADTransport.Listener) {
        this.listener = listener
        listener.onTransportError(startupError)
        Log.w(TAG, "PINPAD transport disabled after startup failure", startupError)
    }

    override fun send(bytes: ByteArray) {
        Log.w(TAG, "Dropping ${bytes.size} byte(s); PINPAD transport is not open")
    }

    override fun stop() {
        listener = null
    }

    companion object {
        private const val TAG = "NoOpTransport"
    }
}
