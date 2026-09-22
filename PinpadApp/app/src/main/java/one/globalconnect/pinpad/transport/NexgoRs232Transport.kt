package one.globalconnect.pinpad.transport

import android.util.Log
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.serialport.SerialCfgEntity
import com.nexgo.oaf.apiv3.device.serialport.SerialPortDriver
import one.globalconnect.pinpad.logging.PinpadTraceLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NexgoRs232Transport(
    deviceEngine: DeviceEngine,
    private val portNo: Int,
    private val baudRate: Int,
    private val dataBits: Int,
    private val stopBits: Int,
    private val parity: String,
    private val transportLabel: String = "RS232",
) : PINPADTransport {
    private val driver: SerialPortDriver = deviceEngine.getSerialPortDriver(portNo)
    private val running = AtomicBoolean(false)
    private var listener: PINPADTransport.Listener? = null
    private var executor: ExecutorService? = null

    override fun start(listener: PINPADTransport.Listener) {
        this.listener = listener
        runCatching { driver.disconnect() }
        val config = SerialCfgEntity().apply {
            setBaudRate(this@NexgoRs232Transport.baudRate)
            setDataBits(this@NexgoRs232Transport.dataBits)
            setParity(this@NexgoRs232Transport.parity.lowercase().firstOrNull() ?: 'n')
            setStopBits(this@NexgoRs232Transport.stopBits)
        }
        Log.i(TAG, "Opening $transportLabel port=$portNo baud=$baudRate dataBits=$dataBits parity=$parity stopBits=$stopBits")
        PinpadTraceLog.transport("$transportLabel opening port=$portNo baud=$baudRate dataBits=$dataBits parity=$parity stopBits=$stopBits")
        val result = driver.connect(config)
        PinpadTraceLog.transport("$transportLabel port=$portNo connect result=$result")
        if (result != SdkResult.Success) {
            throw IllegalStateException("$transportLabel port=$portNo connect failed: $result")
        }
        driver.clrBuffer()
        running.set(true)
        executor = Executors.newSingleThreadExecutor()
        executor?.execute(::readLoop)
    }

    override fun send(bytes: ByteArray) {
        PinpadTraceLog.serialTx(transportLabel, bytes)
        val result = runCatching { driver.send(bytes, bytes.size) }
            .onFailure { listener?.onTransportError(it) }
            .getOrNull()
            ?: return
        if (result != SdkResult.Success) {
            listener?.onTransportError(IllegalStateException("$transportLabel port=$portNo send failed: $result"))
        }
    }

    override fun stop() {
        running.set(false)
        executor?.shutdownNow()
        executor = null
        runCatching { driver.disconnect() }
    }

    private fun readLoop() {
        val buffer = ByteArray(MAX_READ)
        while (running.get()) {
            val read = runCatching { driver.recv(buffer, buffer.size, READ_TIMEOUT_MS) }
                .getOrElse { error ->
                    listener?.onTransportError(error)
                    running.set(false)
                    break
                }
            if (read > 0) {
                val bytes = buffer.copyOf(read)
                PinpadTraceLog.serialRx(transportLabel, bytes)
                listener?.onBytesReceived(bytes)
            } else if (read != 0 && read != SdkResult.SerialPort_Timeout_Receiving_Data) {
                Log.w(TAG, "$transportLabel port=$portNo recv returned $read")
                PinpadTraceLog.transport("$transportLabel port=$portNo recv returned $read")
                listener?.onTransportError(
                    IllegalStateException("$transportLabel port=$portNo receive failed: $read"),
                )
                running.set(false)
            }
        }
    }

    companion object {
        private const val TAG = "NexgoRs232Transport"
        private const val MAX_READ = 2048
        private const val READ_TIMEOUT_MS = 250L
    }
}
