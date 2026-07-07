package one.globalconnect.xtmsagent

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

private const val TAG = "TmsDeviceAdmin"

/**
 * Device admin receiver for xTMSAgent.
 *
 * When this app is set as **device owner** (required — device admin alone is not sufficient),
 * [applyKioskRestrictions] applies policy restrictions appropriate for a POS terminal:
 *
 *   - [UserManager.DISALLOW_CONFIG_TETHERING] — greys out "Hotspot & tethering" in
 *     the Network & internet settings screen so operators cannot enable the hotspot.
 *
 * To set this app as device owner (factory-reset device, no accounts added yet):
 *   adb shell dpm set-device-owner one.globalconnect.xtmsagent/.TmsDeviceAdminReceiver
 *
 * To remove device owner (for development):
 *   adb shell dpm remove-active-admin one.globalconnect.xtmsagent/.TmsDeviceAdminReceiver
 */
class TmsDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        Log.i(TAG, "Device admin enabled")
        applyKioskRestrictions(context)
    }

    companion object {

        fun componentName(context: Context) =
            ComponentName(context, TmsDeviceAdminReceiver::class.java)

        /**
         * Applies POS-terminal restrictions if this app is the device owner.
         * Safe to call on every startup — restrictions are idempotent.
         * Does nothing and logs a warning if the app is not the device owner.
         */
        fun applyKioskRestrictions(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = componentName(context)

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.d(TAG, "Not device owner — kiosk restrictions not applied. " +
                    "Run: adb shell dpm set-device-owner one.globalconnect.xtmsagent/.TmsDeviceAdminReceiver")
                return
            }

            // Prevent operators from enabling the Wi-Fi hotspot / USB tethering.
            // The "Hotspot & tethering" row in Network & internet settings becomes greyed out.
            try {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
                Log.i(TAG, "DISALLOW_CONFIG_TETHERING applied — hotspot is locked off")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply DISALLOW_CONFIG_TETHERING: ${e.message}")
            }

            // Prevent operators from switching USB connection to file transfer (MTP/PTP).
            // The USB mode selector in the notification shade / Settings becomes unavailable.
            try {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                Log.i(TAG, "DISALLOW_USB_FILE_TRANSFER applied — USB file transfer locked off")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply DISALLOW_USB_FILE_TRANSFER: ${e.message}")
            }
        }

        /**
         * Returns true if this app is the device owner.
         * Use this to show/hide device-owner-only UI (e.g. a "Remove device owner" debug option).
         */
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }
    }
}
