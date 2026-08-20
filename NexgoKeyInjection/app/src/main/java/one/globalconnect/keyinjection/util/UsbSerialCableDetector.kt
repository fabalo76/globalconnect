package one.globalconnect.keyinjection.util

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import one.globalconnect.keyinjection.comm.UartManager

/** Identifies the type of USB-to-serial cable attached to the terminal, if any. */
enum class UsbSerialCableType {
    /** An FTDI USB-to-serial interface. */
    FTDI,

    /** A Prolific PL2303 USB-to-serial interface. */
    PL2303,
}

/**
 * Detects USB-to-serial cables connected to the terminal.
 */
object UsbSerialCableDetector {

    /** Returns the connected USB-to-serial cable, if any. */
    fun detectConnectedCable(context: Context): UsbSerialCableType? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return null
        return usbManager.deviceList.values.firstNotNullOfOrNull { device ->
            device.toCableType()
        }
    }

    private fun UsbDevice.toCableType(): UsbSerialCableType? {
        return when {
            vendorId == UartManager.FTDI_USB_VENDOR_ID &&
                productId == UartManager.FTDI_USB_PRODUCT_ID -> UsbSerialCableType.FTDI
            vendorId == UartManager.PL2303_USB_VENDOR_ID &&
                productId == UartManager.PL2303_USB_PRODUCT_ID -> UsbSerialCableType.PL2303
            else -> null
        }
    }
}
