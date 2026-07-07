package com.uic.pinpad.transport

import android.util.Log
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.usbserial.OnUsbSerialReadListener
import com.nexgo.oaf.apiv3.device.usbserial.UsbSerial
import com.nexgo.oaf.apiv3.device.usbserial.UsbSerialCfgEntity
import com.nexgo.oaf.apiv3.platform.Platform
import com.uic.pinpad.logging.PinpadTraceLog

class NexgoUsbCdcTransport(
    deviceEngine: DeviceEngine,
    private val vid: Int,
    private val pid: Int,
    private val baudRate: Int,
    private val dataBits: Int,
    private val stopBits: Int,
    private val parity: String,
) : PINPADTransport {
    private val usbSerial: UsbSerial = deviceEngine.usbSerial
    private val platform: Platform = deviceEngine.platform
    private var listener: PINPADTransport.Listener? = null

    override fun start(listener: PINPADTransport.Listener) {
        this.listener = listener
        if (!isUsbCdcEnabled()) {
            throw IllegalStateException("USB CDC is disabled in Nexgo platform settings")
        }
        val config = UsbSerialCfgEntity().apply {
            setVid(this@NexgoUsbCdcTransport.vid)
            setPid(this@NexgoUsbCdcTransport.pid)
            setBaudRate(this@NexgoUsbCdcTransport.baudRate)
            setDataBits(this@NexgoUsbCdcTransport.dataBits)
            setParity(this@NexgoUsbCdcTransport.parity.toUsbParity())
            setStopBits(if (this@NexgoUsbCdcTransport.stopBits == 2) UsbSerial.STOPBITS_2 else UsbSerial.STOPBITS_1)
        }
        PinpadTraceLog.transport("USB_CDC opening vid=$vid pid=$pid baud=$baudRate dataBits=$dataBits parity=$parity stopBits=$stopBits")
        val result = usbSerial.open(
            config,
            OnUsbSerialReadListener { data ->
                if (data.isNotEmpty()) {
                    PinpadTraceLog.serialRx(SOURCE, data)
                    listener.onBytesReceived(data)
                }
            },
        )
        if (result != SdkResult.Success) {
            throw IllegalStateException("USB CDC open failed: $result")
        }
        usbSerial.clrBuffer()
    }

    override fun send(bytes: ByteArray) {
        if (!isUsbCdcEnabled()) {
            listener?.onTransportError(IllegalStateException("USB CDC write skipped because CDC is disabled"))
            return
        }
        PinpadTraceLog.serialTx(SOURCE, bytes)
        val result = usbSerial.write(bytes, bytes.size)
        if (result != SdkResult.Success) {
            listener?.onTransportError(IllegalStateException("USB CDC write failed: $result"))
        }
    }

    override fun stop() {
        usbSerial.close()
        listener = null
    }

    private fun String.toUsbParity(): Int {
        return when (uppercase()) {
            "O" -> UsbSerial.PARITY_ODD
            "E" -> UsbSerial.PARITY_EVEN
            else -> UsbSerial.PARITY_NONE
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
    }
}
