package one.globalconnect.keyinjection.comm

import one.globalconnect.keyinjection.BuildConfig
import one.globalconnect.keyinjection.util.Logger

/**
 * Handles packet framing over the UART connection using STX/ETX or SI/SO
 * wrappers and exposes helpers for common control characters.
 */
class SerialPacketManager(private val uart: UartManager = UartManager.instance) {

    private val tAG = "SerialPacketMgr"
    private val rxBuffer: ArrayDeque<Byte> = ArrayDeque()

    companion object {
        private const val STX: Byte = 0x02
        private const val ETX: Byte = 0x03
        private const val SI: Byte = 0x0F
        private const val SO: Byte = 0x0E
        private const val ACK: Byte = 0x06
        private const val NACK: Byte = 0x15
        private const val EOT: Byte = 0x04
    }

    /**
     * Open the underlying UART connection and immediately send an ACK to the
     * host.
     *
     * @return `true` if the port was opened successfully
     */

    fun open(): Boolean {
        val connected = uart.connect()
        if (connected) {
            sendACK()
        } else {
            Logger.e(tAG, "COM PORT open failed")
        }
        return connected
    }

    /**
     * Close the UART connection.
     *
     */

    fun close() {
        uart.disconnect()
    }

    /**
     * Send [data] framed with STX/ETX characters.
     *
     * @param data payload to send
     */

    fun sendSTX(data: ByteArray, timeoutMs: Long = 3_000): AckCode {
        val packet = buildPacket(STX, ETX, data)
        return try {
            uart.send(packet)
            Logger.d(tAG, "TX STX packet (${packet.size} bytes)")
            tracePacket("TX", "STX", STX, ETX, data, packet.last())
            readAck(timeoutMs)
        } catch (e: Exception) {
            Logger.e(tAG, "sendSTX failed", e)
            throw e
        }
    }

    /**
     * Send [data] framed with SI/SO characters.
     *
     * @param data payload to send
     */

    fun sendSI(data: ByteArray, timeoutMs: Long = 3_000): AckCode {
        val packet = buildPacket(SI, SO, data)
        return try {
            uart.send(packet)
            Logger.d(tAG, "TX SI packet (${packet.size} bytes)")
            tracePacket("TX", "SI", SI, SO, data, packet.last())
            readAck(timeoutMs)
        } catch (e: Exception) {
            Logger.e(tAG, "sendSI failed", e)
            throw e
        }
    }

    /**
     * Wait for a packet wrapped in STX/ETX framing.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the received payload or `null` on timeout or error
     */

    fun receiveSTX(timeoutMs: Long = 3_000): ByteArray? {
        Logger.d(tAG, "receiveSTX timeout: $timeoutMs")
        val data = receivePacket(STX, ETX, timeoutMs)
        if (data != null) {
            sendACK();
            Logger.d(tAG, "RX STX packet (${data.size} payload bytes)")
            tracePacket("RX", "STX", STX, ETX, data, calculateLrc(data + byteArrayOf(ETX)))
        } else {
            Logger.w(tAG, "RX STX timeout or invalid packet")
        }
        return data
    }

    /**
     * Wait for a packet wrapped in SI/SO framing.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the received payload or `null` on timeout or error
     */

    fun receiveSI(timeoutMs: Long = 3_000): ByteArray? {
        Logger.d(tAG, "receiveSI timeout: $timeoutMs")
        val data = receivePacket(SI, SO, timeoutMs)
        if (data != null) {
            Logger.d(tAG, "RX SI packet (${data.size} payload bytes)")
            tracePacket("RX", "SI", SI, SO, data, calculateLrc(data + byteArrayOf(SO)))
            sendACK()
        } else {
            Logger.w(tAG, "RX SI timeout or invalid packet")
        }
        return data
    }

    /**
     * Construct a framed packet including an LRC.
     *
     * @param start start-of-packet delimiter
     * @param end end-of-packet delimiter
     * @param data payload bytes
     * @return fully framed packet ready for transmission
     */
    private fun buildPacket(start: Byte, end: Byte, data: ByteArray): ByteArray {
        val body =   data + byteArrayOf(end)
        val lrc = calculateLrc(body)
        return byteArrayOf(start) + body  + byteArrayOf(lrc)
    }

    /**
     * Calculate the longitudinal redundancy check (LRC) for [bytes].
     *
     * @param bytes data over which to compute the LRC
     * @return calculated LRC byte
     */
    private fun calculateLrc(bytes: ByteArray): Byte {
        var lrc: Byte = 0
        for (b in bytes) {
            lrc = (lrc.toInt() xor b.toInt()).toByte()
        }
        return lrc
    }

    private fun tracePacket(
        direction: String,
        framing: String,
        start: Byte,
        end: Byte,
        payload: ByteArray,
        lrc: Byte
    ) {
        if (!BuildConfig.DEBUG) return
        Logger.d(
            tAG,
            SerialPacketDebugFormatter.format(direction, framing, start, end, payload, lrc)
        )
    }

    /**
     * Receive a packet delimited by [start] and [end] characters.
     *
     * @param start start-of-packet delimiter
     * @param end end-of-packet delimiter
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the packet payload or `null` on timeout or checksum failure
     */
    private fun receivePacket(start: Byte, end: Byte, timeoutMs: Long): ByteArray? {
        val startTime = System.currentTimeMillis()
        var startByte: Byte
        Logger.d(tAG, "receivePacket timeout: $timeoutMs start: 0x%02X end: 0x%02X".format(start.toInt() and 0xFF, end.toInt() and 0xFF))
        while (true) {
            // Check if connection is still open
            if (!uart.isConnected()) {
                Logger.d(tAG, "receivePacket: connection closed, aborting")
                return null
            }
            val remaining = timeoutMs - (System.currentTimeMillis() - startTime)
            if (remaining <= 0) return null
            startByte = readByte(remaining) ?: return null
            when (startByte) {
                ACK -> {
                    Logger.d(tAG, "Ignoring ACK while waiting for packet start")
                    continue
                }
                NACK -> {
                    Logger.w(tAG, "Received NACK while waiting for packet start")
                    return null
                }
                EOT -> {
                    Logger.w(tAG, "Received EOT while waiting for packet start")
                    return null
                }
                start -> {
                    Logger.w(tAG, "Received start byte 0x%02X".format(start.toInt() and 0xFF))
                    break
                }
            }
            break
        }
        if (startByte != start) {
            Logger.w(tAG, "Invalid start byte: ${String.format("%02X", startByte)}")
            return null
        }

        val data = mutableListOf<Byte>()
        while (true) {
            // Check if connection is still open
            if (!uart.isConnected()) {
                Logger.d(tAG, "receivePacket: connection closed while reading data, aborting")
                return null
            }
            val remaining = timeoutMs - (System.currentTimeMillis() - startTime)
            if (remaining <= 0) return null
            val b = readByte(remaining) ?: return null
            if (b == end) {
                Logger.w(tAG, "Received end byte 0x%02X".format(end.toInt() and 0xFF))
                break
            }
            data += b
        }
        val remaining = timeoutMs - (System.currentTimeMillis() - startTime)
        if (remaining <= 0) return null
        val lrc = readByte(remaining) ?: return null
        val calculated = calculateLrc(data.toByteArray() + byteArrayOf(end))
        return if (lrc == calculated) {
            data.toByteArray()
        } else {
            Logger.w(tAG, "LRC mismatch: expected ${String.format("%02X", calculated)} got ${String.format("%02X", lrc)}")
            try {
                sendNACK()
            } catch (error: Exception) {
                Logger.e(tAG, "Unable to send NAK after LRC mismatch", error)
            }
            null
        }
    }

    /**
     * Attempt to read a single byte from the UART within [timeoutMs].
     *
     * @param timeoutMs time to wait in milliseconds
     * @return the byte read or `null` if no data arrived or connection closed
     */
    private fun readByte(timeoutMs: Long): Byte? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Check if connection is still open
            if (!uart.isConnected()) {
                Logger.d(tAG, "readByte: connection closed, aborting read")
                return null
            }

            if (rxBuffer.isNotEmpty()) {
                return rxBuffer.removeFirst()
            }
            try {
                uart.receive(255,timeoutMs)?.let { bytes ->
                    for (b in bytes) {
                        rxBuffer.addLast(b)
                    }
                    if (rxBuffer.isNotEmpty()) {
                        return rxBuffer.removeFirst()
                    }
                }
            } catch (e: Exception) {
                Logger.e(tAG, "receiveNonBlocking failed", e)
                return null
            }
            Thread.sleep(10)
        }
        return null
    }

    /**
     * Transmit an ACK control byte.
     *
     * @throws CommException if transmission fails
     */

    fun sendACK() {
        try {
            uart.send(byteArrayOf(ACK))
            Logger.d(tAG, "TX: ACK")
        } catch (e: Exception) {
            Logger.e(tAG, "sendACK failed", e)
            throw e
        }
    }

    /**
     * Transmit a NACK control byte.
     *
     * @throws CommException if transmission fails
     */

    fun sendNACK() {
        try {
            uart.send(byteArrayOf(NACK))
            Logger.d(tAG, "TX: NACK")
        } catch (e: Exception) {
            Logger.e(tAG, "sendNACK failed", e)
            throw e
        }
    }

    /**
     * Transmit an EOT control byte.
     *
     * @throws CommException if transmission fails
     */

    fun sendEOT() {
        try {
            uart.send(byteArrayOf(EOT))
            Logger.d(tAG, "TX: EOT")
        } catch (e: Exception) {
            Logger.e(tAG, "sendEOT failed", e)
            throw e
        }
    }

    /**
     * Clear any pending bytes from both the internal buffer and the UART
     * receive buffer.
     */
    fun clearBuffer() {
        rxBuffer.clear()
        try {
            while (uart.receive(50)?.isNotEmpty() == true) {
                // discard bytes
            }
        } catch (e: Exception) {
            Logger.e(tAG, "receiveNonBlocking failed while clearing buffer", e)
        }
        Logger.d(tAG, "RX buffer cleared")
    }

    /**
     * Possible responses expected after transmitting a packet. These map to
     * standard control characters or represent timeout/error conditions.
     */
    enum class AckCode { ACK, NACK, EOT, TIMEOUT, WRONGCONTROLCHAR }

    /**
     * Wait for an ACK, NACK or EOT byte and return the corresponding
     * [AckCode].
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the received [AckCode] or `null` on timeout or unknown byte
     */
    fun readAck(timeoutMs: Long = 3_000): AckCode {
        val b = readByte(timeoutMs) ?: run {
            Logger.w(tAG, "Ack read timeout")
            return AckCode.TIMEOUT
        }
        var code = when (b) {
            ACK -> AckCode.ACK
            NACK -> AckCode.NACK
            EOT -> AckCode.EOT
            else -> null
        }
        if (code != null) {
            Logger.d(tAG, "RX: $code")
        } else {
            Logger.w(tAG, "Unexpected CONTROL byte: ${String.format("%02X", b)}")
            code = AckCode.WRONGCONTROLCHAR
        }
        return code
    }
}
