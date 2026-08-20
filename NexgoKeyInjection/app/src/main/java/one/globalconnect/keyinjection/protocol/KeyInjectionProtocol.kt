package one.globalconnect.keyinjection.protocol

import one.globalconnect.keyinjection.comm.SerialPacketManager
import one.globalconnect.keyinjection.util.Logger

/**
 * Base utilities for exchanging key injection messages over a serial link.
 * Provides generic send/receive helpers using either STX/ETX or SI/SO framing.
 * Higher level protocol implementations are responsible for formatting
 * and parsing messages.
 */
open class KeyInjectionProtocol(
    private val packetManagerFactory: () -> SerialPacketManager = { SerialPacketManager() },
    private val framing: Framing = Framing.STX
) {

    protected val packetManager: SerialPacketManager by lazy(
        LazyThreadSafetyMode.NONE,
        packetManagerFactory
    )

    /** Available packet framing types. */
    enum class Framing { STX, SI }

    /**
     * Open the underlying [SerialPacketManager].
     *
     * @return `true` if the UART connection was opened
     */

    fun open(): Boolean {
        val opened = packetManager.open()
        if (opened) {
            packetManager.clearBuffer()
        }
        return opened
    }

    /**
     * Close the [SerialPacketManager] connection.
     *
     */

    fun close() = packetManager.close()

    /**
     * Send a raw [body] using the configured framing.
     *
     * @param body payload bytes to transmit
     */

    fun send(body: ByteArray, timeoutMs: Long = 3_000): SerialPacketManager.AckCode {
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            val result = when (framing) {
                Framing.STX -> packetManager.sendSTX(body, timeoutMs)
                Framing.SI -> packetManager.sendSI(body, timeoutMs)
            }
            if (result != SerialPacketManager.AckCode.NACK) {
                return result
            }
            Logger.w(TAG, "Received NAK; retrying packet (${attempt + 1}/$MAX_SEND_ATTEMPTS)")
        }
        return SerialPacketManager.AckCode.NACK
    }

    /**
     * Sends a EOT Control Char.
     *
     */

    fun sendEOT() {
        packetManager.sendEOT()
    }

    /**
     * Receive a packet body using the configured framing.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return received payload or `null` if no packet arrives
     */

    fun receive(timeoutMs: Long = 3_000): ByteArray? = when (framing) {
        Framing.STX -> packetManager.receiveSTX(timeoutMs)
        Framing.SI -> packetManager.receiveSI(timeoutMs)
    }

    private companion object {
        const val TAG = "KeyInjectionProtocol"
        const val MAX_SEND_ATTEMPTS = 3
    }
}
