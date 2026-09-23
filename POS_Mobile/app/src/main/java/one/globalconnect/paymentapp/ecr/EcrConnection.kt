package one.globalconnect.paymentapp.ecr

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.serialport.SerialCfgEntity

internal interface EcrConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    val closed: Boolean
    val description: String
}

internal class EcrTcpConnection(private val socket: Socket) : EcrConnection {
    init { socket.tcpNoDelay = true; socket.soTimeout = 1000 }
    override val input get() = socket.getInputStream()
    override val output get() = socket.getOutputStream()
    override val closed get() = socket.isClosed
    override val description get() = "TCP ${socket.remoteSocketAddress}"
    override fun close() = socket.close()
}

internal class EcrSerialConnection(engine: DeviceEngine, config: EcrSettings) : EcrConnection {
    private val driver by lazy { engine.getSerialPortDriver(config.serialPort) }
    @Volatile override var closed = false
        private set
    override val description = "${config.transport} ${config.serialPort} @ ${config.baudRate}"
    init {
        val usbBase = android.os.Build.MODEL.replace(" ", "").uppercase() in setOf("N6PRO", "N6PROLITE")
        // N6 Pro models use the dedicated USB base serial adapter instead of USB CDC.
        // Enable CDC before obtaining/opening the built-in USB serial driver.
        if (config.transport == "USB" && !usbBase) {
            val platform = engine.platform
            val enabled = runCatching { platform.usbCdcStatus }
                .onFailure { EcrDebugLog.event { "USB CDC status unavailable; attempting enable" } }
                .getOrDefault(false)
            if (!enabled) {
                val result = platform.enableUsbCdc()
                EcrDebugLog.event { "USB CDC enable result=$result" }
                check(result == SdkResult.Success) { "USB CDC enable failed: $result" }
            } else {
                EcrDebugLog.event { "USB CDC already enabled" }
            }
        }
        val parameters = SerialCfgEntity().apply {
            setBaudRate(config.baudRate); setDataBits(8); setStopBits(1); setParity('n')
        }
        var result = SdkResult.SerialPort_Connect_Fail
        for (attempt in 1..8) {
            result = driver.connect(parameters)
            if (result == SdkResult.Success) break
            if (result !in setOf(SdkResult.SerialPort_Connect_Fail,
                    SdkResult.SerialPort_DisConnected, SdkResult.SerialPort_Port_Not_Open)) break
            driver.disconnect()
            if (attempt < 8) Thread.sleep(250)
        }
        EcrDebugLog.event { "Serial open $description result=$result" }
        check(result == SdkResult.Success) { "Serial port open failed: $result" }
        // Do not clear RX here: the PC may already have sent its first ECR frame
        // while the CDC endpoint was reopening.
    }
    override val input = object : InputStream() {
        // Nexgo SerialPortDriver rejects receive lengths above 2048.
        private val buffer = ByteArray(2048)
        private var offset = 0
        private var count = 0
        override fun read(): Int {
            if (closed) return -1
            if (offset == count) {
                count = driver.recv(buffer, buffer.size, 1000L)
                offset = 0
                // This SDK closes the native port on DisConnected. Let the runtime
                // reopen it rather than repeatedly reading the closed descriptor.
                if (count == 0 || count == SdkResult.SerialPort_Timeout_Receiving_Data) { count = 0; throw SocketTimeoutException() }
                if (count < 0) {
                    EcrDebugLog.event { "Serial receive failed $description result=$count" }
                    throw java.io.IOException("Serial receive error $count")
                }
            }
            return buffer[offset++].toInt() and 255
        }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            val first = read()
            if (first < 0) return -1
            bytes[start] = first.toByte()
            val available = minOf(length - 1, count - offset)
            buffer.copyInto(bytes, start + 1, offset, offset + available)
            offset += available
            return available + 1
        }
    }
    override val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) = write(bytes.copyOfRange(offset, offset + length))
        override fun write(bytes: ByteArray) {
            check(!closed)
            // Reports can exceed the SDK's per-call limit; preserve the framed byte stream.
            for (start in bytes.indices step 2048) {
                val chunk = bytes.copyOfRange(start, minOf(start + 2048, bytes.size))
                val result = driver.send(chunk, chunk.size)
                if (result != SdkResult.Success) {
                    EcrDebugLog.event { "Serial write failed $description result=$result" }
                    throw java.io.IOException("Serial write failed: $result")
                }
            }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { driver.disconnect() }
    }
}
