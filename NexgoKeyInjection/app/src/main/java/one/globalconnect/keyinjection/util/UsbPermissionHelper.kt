package one.globalconnect.keyinjection.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Utility helpers to request runtime permission to communicate with a USB
 * serial device.
 */
object UsbPermissionHelper {

    private const val ACTION_USB_PERMISSION = "one.globalconnect.keyinjection.USB_PERMISSION"

    /** Outcome of attempting to acquire permission to access the USB device. */
    enum class Result {
        /** Permission already granted or granted by the user. */
        GRANTED,

        /** The device is not attached to the terminal. */
        DEVICE_NOT_FOUND,

        /** User denied the permission request or a platform error occurred. */
        DENIED,
    }

    /**
     * Ensures the application has permission to communicate with the desired
     * USB device. If permission has not yet been granted a runtime dialog is
     * displayed and the coroutine suspends until the user makes a choice.
     */
    suspend fun ensurePermission(
        context: Context,
        vendorId: Int,
        productId: Int,
    ): Result {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return Result.DENIED
        val device = findDevice(usbManager, vendorId, productId) ?: return Result.DEVICE_NOT_FOUND
        if (usbManager.hasPermission(device)) {
            return Result.GRANTED
        }
        return requestPermission(appContext, usbManager, device)
    }

    /**
     * Locate the USB device matching the provided identifiers.
     *
     * @param usbManager system USB manager
     * @param vendorId USB vendor identifier to match
     * @param productId USB product identifier to match
     * @return matching [UsbDevice] instance or `null` if not attached
     */
    internal fun findDevice(
        usbManager: UsbManager,
        vendorId: Int,
        productId: Int,
    ): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == vendorId && device.productId == productId
        }
    }

    /**
     * Request permission to communicate with [device], suspending until the
     * user responds.
     *
     * @return [Result.GRANTED] if the user allows access, otherwise
     * [Result.DENIED]
     */
    private suspend fun requestPermission(
        context: Context,
        usbManager: UsbManager,
        device: UsbDevice,
    ): Result = suspendCancellableCoroutine { continuation ->
        val intent = Intent(ACTION_USB_PERMISSION)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) {
                    return
                }
                val permissionDevice = intent.deviceExtra
                if (permissionDevice?.deviceId != device.deviceId) {
                    return
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                try {
                    context.unregisterReceiver(this)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered because the coroutine was cancelled.
                }
                if (continuation.isActive) {
                    continuation.resume(if (granted) Result.GRANTED else Result.DENIED)
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        continuation.invokeOnCancellation {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Receiver already unregistered
            }
        }

        usbManager.requestPermission(device, pendingIntent)
    }

    /** Extract the [UsbDevice] from a permission broadcast intent. */
    private val Intent?.deviceExtra: UsbDevice?
        get() = when {
            this == null -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

            else -> @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
