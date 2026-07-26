package one.globalconnect.pinpad.transport

import android.util.Log
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.serialport.SerialCfgEntity
import com.nexgo.oaf.apiv3.device.serialport.SerialPortDriver
import com.nexgo.oaf.apiv3.platform.Platform
import one.globalconnect.pinpad.logging.PinpadTraceLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NexgoUsbCdcTransport(
    deviceEngine: DeviceEngine,
    private val portNo: Int,
    private val baudRate: Int,
    private val dataBits: Int,
    private val stopBits: Int,
    private val parity: String,
) : PINPADTransport {
    private val driver: SerialPortDriver = deviceEngine.getSerialPortDriver(portNo)
    private val platform: Platform = deviceEngine.platform
    private val running = AtomicBoolean(false)
    private var listener: PINPADTransport.Listener? = null
    private var executor: ExecutorService? = null

    override fun start(listener: PINPADTransport.Listener) {
        this.listener = listener
        enableUsbCdc()
        runCatching { driver.disconnect() }
        val config = SerialCfgEntity().apply {
            setBaudRate(this@NexgoUsbCdcTransport.baudRate)
            setDataBits(this@NexgoUsbCdcTransport.dataBits)
            setParity(this@NexgoUsbCdcTransport.parity.lowercase().firstOrNull() ?: 'n')
            setStopBits(this@NexgoUsbCdcTransport.stopBits)
        }
        PinpadTraceLog.transport(
            "USB_CDC opening Nexgo serial port=$portNo baud=$baudRate " +
                "dataBits=$dataBits parity=$parity stopBits=$stopBits",
        )
        val result = driver.connect(config)
        if (result != SdkResult.Success) {
            throw IllegalStateException("USB CDC serial port $portNo connect failed: $result")
        }
        driver.clrBuffer()
        running.set(true)
        executor = Executors.newSingleThreadExecutor()
        executor?.execute(::readLoop)
    }

    private fun enableUsbCdc() {
        if (isUsbCdcEnabled()) return

        PinpadTraceLog.transport("USB_CDC enabling through Nexgo platform")
        val result = platform.enableUsbCdc()
        Log.i(TAG, "USB CDC enable result=$result")
        PinpadTraceLog.transport("USB_CDC enable result=$result")
        if (result != SdkResult.Success) {
            throw IllegalStateException("USB CDC enable failed: $result")
        }
    }

    override fun send(bytes: ByteArray) {
        if (!isUsbCdcEnabled()) {
            listener?.onTransportError(IllegalStateException("USB CDC write skipped because CDC is disabled"))
            return
        }
        PinpadTraceLog.serialTx(SOURCE, bytes)
        val result = driver.send(bytes, bytes.size)
        if (result != SdkResult.Success) {
            listener?.onTransportError(IllegalStateException("USB CDC write failed: $result"))
        }
    }

    override fun stop() {
        running.set(false)
        executor?.shutdownNow()
        executor = null
        runCatching { driver.disconnect() }
        listener = null
    }

    private fun readLoop() {
        val buffer = ByteArray(MAX_READ)
        while (running.get()) {
            val read = runCatching { driver.recv(buffer, buffer.size, READ_TIMEOUT_MS) }
                .onFailure { listener?.onTransportError(it) }
                .getOrDefault(0)
            if (read > 0) {
                val bytes = buffer.copyOf(read)
                PinpadTraceLog.serialRx(SOURCE, bytes)
                listener?.onBytesReceived(bytes)
            } else if (read != 0 && read != SdkResult.SerialPort_Timeout_Receiving_Data) {
                Log.w(TAG, "USB CDC recv returned $read")
                PinpadTraceLog.transport("USB_CDC recv returned $read")
            }
        }
    }

    private fun isUsbCdcEnabled(): Boolean {
        return runCatching { platform.usbCdcStatus }
            .onSuccess { Log.i(TAG, "USB CDC status enabled=$it") }
            .onFailure { Log.w(TAG, "Unable to read USB CDC status", it) }
            .getOrDefault(false)
    }

    companion object {
        private const val TAG = "NexgoUsbCdcTransport"
        private const val SOURCE = "USB_CDC"
        private const val MAX_READ = 2048
        private const val READ_TIMEOUT_MS = 250L
    }
}
