package com.uic.pinpad.transport

interface PINPADTransport {
    fun start(listener: Listener)
    fun send(bytes: ByteArray)
    fun stop()

    interface Listener {
        fun onBytesReceived(bytes: ByteArray)
        fun onTransportError(error: Throwable)
    }
}
