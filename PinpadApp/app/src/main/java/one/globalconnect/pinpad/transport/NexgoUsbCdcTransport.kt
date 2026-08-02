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
    private var hostDisconnected = false
    private var lastHostDisconnectedLogAt = 0L

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
        val result = connectWithRetry(config)
        if (result != SdkResult.Success) {
            throw IllegalStateException("USB CDC serial port $portNo connect failed: $result")
        }
        driver.clrBuffer()
        running.set(true)
        executor = Executors.newSingleThreadExecutor()
        executor?.execute(::readLoop)
    }

    private fun connectWithRetry(config: SerialCfgEntity): Int {
        var result = SdkResult.SerialPort_Connect_Fail
        repeat(CONNECT_ATTEMPTS) { attempt ->
            result = driver.connect(config)
            if (result == SdkResult.Success) {
                if (attempt > 0) {
                    PinpadTraceLog.transport("USB_CDC connected after ${attempt + 1} attempts")
                }
                return result
            }
            if (result != SdkResult.SerialPort_Connect_Fail &&
                result != SdkResult.SerialPort_DisConnected &&
                result != SdkResult.SerialPort_Port_Not_Open
            ) {
                return result
            }
            if (attempt < CONNECT_ATTEMPTS - 1) {
                Log.i(
                    TAG,
                    "USB CDC connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS returned $result; retrying",
                )
                PinpadTraceLog.transport(
                    "USB_CDC connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS returned $result; retrying",
                )
                runCatching { driver.disconnect() }
                Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }
        return result
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
        val result = runCatching { driver.send(bytes, bytes.size) }
            .onFailure { listener?.onTransportError(it) }
            .getOrNull()
            ?: return
        if (result == SdkResult.SerialPort_DisConnected) {
            logHostDisconnected()
        } else if (result != SdkResult.Success) {
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
                .getOrElse { error ->
                    listener?.onTransportError(error)
                    running.set(false)
                    break
                }
            if (read > 0) {
                if (hostDisconnected) {
                    hostDisconnected = false
                    Log.i(TAG, "USB CDC host connected; receive resumed")
                    PinpadTraceLog.transport("USB_CDC host connected; receive resumed")
                }
                val bytes = buffer.copyOf(read)
                PinpadTraceLog.serialRx(SOURCE, bytes)
                listener?.onBytesReceived(bytes)
            } else if (read == SdkResult.SerialPort_DisConnected) {
                // The CT20P returns this while the PC-side virtual COM port is closed.
                // Keep the device endpoint alive so communication resumes when the host
                // opens or reopens the COM port.
                logHostDisconnected()
                Thread.sleep(HOST_DISCONNECTED_RETRY_MS)
            } else if (read != 0 && read != SdkResult.SerialPort_Timeout_Receiving_Data) {
                Log.w(TAG, "USB CDC recv returned $read")
                PinpadTraceLog.transport("USB_CDC recv returned $read")
                listener?.onTransportError(
                    IllegalStateException("USB CDC receive failed on port $portNo: $read"),
                )
                running.set(false)
            }
        }
    }

    private fun logHostDisconnected() {
        hostDisconnected = true
        val now = System.currentTimeMillis()
        if (now - lastHostDisconnectedLogAt < HOST_DISCONNECTED_LOG_INTERVAL_MS) return

        lastHostDisconnectedLogAt = now
        Log.i(TAG, "USB CDC host is not connected; waiting for PC COM port")
        PinpadTraceLog.transport("USB_CDC waiting for PC COM port")
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
        private const val CONNECT_ATTEMPTS = 8
        private const val CONNECT_RETRY_DELAY_MS = 250L
        private const val HOST_DISCONNECTED_RETRY_MS = 250L
        private const val HOST_DISCONNECTED_LOG_INTERVAL_MS = 30_000L
    }
}
