package one.globalconnect.keyinjection.comm

import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.serialport.SerialCfgEntity
import com.nexgo.oaf.apiv3.device.serialport.SerialPortDriver
import com.nexgo.oaf.apiv3.device.usbserial.OnUsbSerialReadListener
import com.nexgo.oaf.apiv3.device.usbserial.UsbSerial
import com.nexgo.oaf.apiv3.device.usbserial.UsbSerialCfgEntity
import one.globalconnect.keyinjection.App
import one.globalconnect.keyinjection.config.DeviceModelConfig
import one.globalconnect.keyinjection.config.SerialPortConfig
import one.globalconnect.keyinjection.util.Logger
import java.util.Arrays

/**
 * Kotlin wrapper around Nexgo Serial Port Commands.
 */
class UartManager private constructor() {
    private val tAG = "uartComm"
    private var connected = false
    private var portNum = 0
    private var deviceSupportsUsbCdc = false
    private val serialCfgEntity: SerialCfgEntity = SerialCfgEntity()
    private var port: SerialPortDriver? = null
    private var usbSerialPort: UsbSerial? = null
    private val usbBufferLock = Any()
    private val usbSerialBuffer: ArrayDeque<Byte> = ArrayDeque()
    private val pl2303SerialCfgEntity: UsbSerialCfgEntity = UsbSerialCfgEntity().apply {
        vid = PL2303_USB_VENDOR_ID
        pid = PL2303_USB_PRODUCT_ID
        baudRate = 9600
        dataBits = 8
        parity = 0
        stopBits = 1
    }
    private val ftdiSerialCfgEntity: UsbSerialCfgEntity = UsbSerialCfgEntity().apply {
        vid = FTDI_USB_VENDOR_ID
        pid = FTDI_USB_PRODUCT_ID
        baudRate = 9600
        dataBits = 8
        parity = 0
        stopBits = 1
    }
    @Volatile
    private var usbSerialOpened = false
    @Volatile
    private var activeConnectionType: ConnectionType? = null
    private val usbSerialReadListener = object : OnUsbSerialReadListener {
        override fun onReadResult(bytes: ByteArray) {
            if (bytes.isEmpty()) {
                return
            }
            Logger.d(tAG, "USB serial received ${bytes.size} bytes")
            synchronized(usbBufferLock) {
                for (b in bytes) {
                    usbSerialBuffer.addLast(b)
                }
            }
        }
    }

    init {
        // Use centralized serial configuration
        val config = SerialPortConfig.StandardConfig()
        serialCfgEntity.baudRate = config.baudRate
        serialCfgEntity.dataBits = config.dataBits
        serialCfgEntity.parity = config.parity
        serialCfgEntity.stopBits = config.stopBits

        // Get model and determine port using unified config
        val model = App.deviceEngine.deviceInfo.getModel()
        val deviceSpec = DeviceModelConfig.getDeviceSpec(model)
        portNum = deviceSpec.defaultSerialPort
        deviceSupportsUsbCdc = deviceSpec.usbCdcSupported

        // Log device configuration for debugging
        Logger.d(tAG, "Device Model: ${deviceSpec.modelName}")
        Logger.d(tAG, "Serial Port: $portNum")
        Logger.d(tAG, "USB-CDC Supported: ${deviceSpec.usbCdcSupported}")
        Logger.d(tAG, "RS232 Supported: ${deviceSpec.rs232SerialSupported}")
        if (deviceSpec.notes.isNotEmpty()) {
            Logger.d(tAG, "Notes: ${deviceSpec.notes}")
        }

        port = App.deviceEngine.getSerialPortDriver(portNum)
        port?.disconnect()

        usbSerialPort = App.deviceEngine.usbSerial
        usbSerialPort?.close()
    }

    /** Transport types supported by the UART manager. */
    enum class ConnectionType {
        /** External USB-to-serial device connected via OTG. */
        USB_SERIAL,

        /** Built-in USB serial interface exposed by the terminal. */
        USB_CDC,

        /** Internal RS232 serial port. */
        INTERNAL_SERIAL,
    }

    /**
     * Indicates which transport is currently in use or `null` if no
     * connection is active.
     */
    val connectionType: ConnectionType?
        get() = activeConnectionType

    /** Flag indicating whether the USB serial transport is currently open. */
    val isUsbSerialOpen: Boolean
        get() = usbSerialOpened

    companion object {
        const val PL2303_USB_VENDOR_ID = 1659
        const val PL2303_USB_PRODUCT_ID = 8963
        const val FTDI_USB_VENDOR_ID = 1027
        const val FTDI_USB_PRODUCT_ID = 24577

        val instance: UartManager by lazy { UartManager() }
    }

    /**
     * Attempt to open the external USB serial device if one is attached.
     *
     * @return `true` when the USB transport is available and opened
     */
    private fun openUsbSerialIfPresent(): Boolean {
        if (usbSerialOpened) {
            return true
        }
        synchronized(this) {
            if (usbSerialOpened) {
                return true
            }
            val usbSerial = App.deviceEngine.usbSerial.also { usbSerialPort = it }
            val pl2303SerialOpenResult = usbSerial.open(pl2303SerialCfgEntity, usbSerialReadListener)
            Logger.i(tAG, "PL2303 USB SERIAL OPEN RESULT: $pl2303SerialOpenResult")
            usbSerialOpened = pl2303SerialOpenResult == SdkResult.Success
            if (!usbSerialOpened) {
                Logger.e(tAG, "PL2303 USB SERIAL OPEN FAILED: $pl2303SerialOpenResult")
                //FRS 2025-09-22 NOT TRY TO OPEN WITH FTDI VID AND PID
                val ftdiSerialOpenResult = usbSerial.open(ftdiSerialCfgEntity, usbSerialReadListener)
                Logger.i(tAG, "FTDI USB SERIAL OPEN RESULT: $ftdiSerialOpenResult")
                usbSerialOpened = ftdiSerialOpenResult == SdkResult.Success
                if (!usbSerialOpened) {
                    Logger.e(tAG, "FTDI USB SERIAL OPEN FAILED: $ftdiSerialOpenResult")
                }
            }
            return usbSerialOpened
        }
    }

    /**
     * Open the UART port defined by [serialCfgEntity], preferring the USB
     * serial transport and falling back to the internal port when necessary.
     *
     * @return `true` if any transport was opened successfully
     */
    fun connect(): Boolean {
        if (connected) {
            return true
        }
        Logger.d(tAG, "Opening COM PORT [$portNum] connected: [$connected]")
        connected = openUsbSerialIfPresent()
        if (!connected) {
            Logger.w(tAG, "USB SERIAL not available. Falling back to internal COM port")
            Logger.d(tAG, "Opening COM PORT [$portNum]")
            val driver = port
            if (driver == null) {
                Logger.e(tAG, "Serial port driver unavailable")
                activeConnectionType = null
                return false
            }
            val ret = driver.connect(serialCfgEntity)
            if (ret == SdkResult.Success) {
                port?.clrBuffer()
                connected = true
            } else {
                Logger.e(tAG, "Serial port connect failed: $ret")
            }
        }
        return if (connected) {
            activeConnectionType = when {
                usbSerialOpened -> ConnectionType.USB_SERIAL
                deviceSupportsUsbCdc -> ConnectionType.USB_CDC
                else -> ConnectionType.INTERNAL_SERIAL
            }
            Logger.d(tAG, "COM PORT opened using ${activeConnectionType}")
            true
        } else {
            activeConnectionType = null
            Logger.d(tAG, "COM PORT open failed")
            false
        }
    }

    /**
     * Send [data] over the UART connection.
     *
     * @param data bytes to transmit
     * @return `true` if the driver reported success
     */
    fun send(data: ByteArray) : Boolean {
        if (!connected) {
            Logger.e(tAG, "Send Error. COM PORT not opened")
            return false
        }
        return if (usbSerialOpened) {
            val res = usbSerialPort?.write(data, data.size)
            if (res == null) {
                Logger.e(tAG, "USB serial write returned null result")
                false
            } else {
                res == SdkResult.Success
            }
        } else {
            val res = port?.send(data, data.size) ?: -1
            res == SdkResult.Success
        }
    }

    /**
     * Receive up to [len] bytes, blocking until data is available.
     *
     * @param len number of bytes to read
     * @param rcvTimeout maximum time to wait in milliseconds when using the
     * internal serial port
     * @return received bytes or `null` on error
     */
    fun receive(len: Int, rcvTimeout: Long = 2000): ByteArray? {

        if (connected) {
            if (usbSerialOpened) {
                drainUsbSerialBuffer(len)?.let { return it }
                return null
            }
            val rev = ByteArray(len + 1)
            Arrays.fill(rev, 0.toByte())
            val res = port?.recv(rev, len, rcvTimeout) ?: -1
            return when {
                res > 0 -> rev.copyOfRange(0, res)
                res == 0 -> {
                    Logger.e(tAG, "Receive Error. COM PORT not opened")
                    null
                }
                else -> {
                    Logger.e(tAG, "Receive Error: $res")
                    null
                }
            }
        }
        Logger.e(tAG, "Receive Error. COM PORT not opened")
        return null
    }

    /**
     * Check if the UART connection is currently open.
     */
    fun isConnected(): Boolean = connected

    /**
     * Close the UART connection if it is open.
     */
    fun disconnect() {
        var shouldClearBuffers = false
        if (connected) {
            if (usbSerialOpened) {
                val res = usbSerialPort?.close()
                if (res != SdkResult.Success) {
                    Logger.e(tAG, "USB Serial Disconnect Error: $res")
                }
                usbSerialOpened = false
                shouldClearBuffers = true
            } else {
                val res = port?.disconnect()
                if (res != SdkResult.Success && res != SdkResult.SerialPort_Port_Not_Open) {
                    Logger.e(tAG, "COM Port Disconnect Error: $res")
                }
                shouldClearBuffers = true
            }
            connected = false
        }
        if (shouldClearBuffers) {
            clearBuffer()
        }
        activeConnectionType = null
    }

    /** Clear the UART device buffer for both USB and internal transports. */
    fun clearBuffer() {
        synchronized(usbBufferLock) {
            usbSerialBuffer.clear()
        }
        port?.clrBuffer()
    }

    /**
     * Remove up to [maxLen] bytes from the USB receive buffer.
     *
     * @param maxLen maximum number of bytes to drain
     * @return drained bytes or `null` if no data is available
     */
    private fun drainUsbSerialBuffer(maxLen: Int): ByteArray? {
        synchronized(usbBufferLock) {
            if (usbSerialBuffer.isEmpty()) {
                return null
            }
            val bytesToRead = minOf(maxLen, usbSerialBuffer.size)
            val drained = ByteArray(bytesToRead)
            for (i in 0 until bytesToRead) {
                drained[i] = usbSerialBuffer.removeFirst()
            }
            return drained
        }
    }
}
