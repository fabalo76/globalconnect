package one.globalconnect.xtmsagent.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log
import one.globalconnect.xtmsagent.BuildConfig
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver

private const val TAG = "UsbFileTransfer"
private const val PREFS_NAME = "device_owner_policy"
private const val KEY_ENABLED = "usb_file_transfer_enabled"

data class UsbFileTransferResult(
    val success: Boolean,
    val enabled: Boolean,
    val code: String
)

/**
 * Controls Android's Device Owner USB file-transfer restriction.
 *
 * Development builds allow MTP/PTP by default. Production builds deny it by default,
 * but an authenticated operator can change the setting from Config > USB Config.
 */
object UsbFileTransferManager {

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, BuildConfig.DEBUG)

    fun applyStoredPolicy(context: Context): UsbFileTransferResult =
        setEnabled(context, isEnabled(context), persist = false)

    fun setEnabled(context: Context, enabled: Boolean): UsbFileTransferResult =
        setEnabled(context, enabled, persist = true)

    private fun setEnabled(
        context: Context,
        enabled: Boolean,
        persist: Boolean
    ): UsbFileTransferResult {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) {
            Log.w(TAG, "USB file-transfer policy not changed: xTMSAgent is not Device Owner")
            return UsbFileTransferResult(false, isEnabled(appContext), "DEVICE_OWNER_REQUIRED")
        }

        return try {
            val admin = TmsDeviceAdminReceiver.componentName(appContext)
            if (enabled) {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            } else {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            }

            if (persist) {
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .apply()
            }

            Log.i(
                TAG,
                "USB file transfer ${if (enabled) "enabled" else "disabled"} " +
                    "(restriction ${if (enabled) "cleared" else "applied"})"
            )
            UsbFileTransferResult(true, enabled, "OK")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to change USB file-transfer policy: ${e.message}", e)
            UsbFileTransferResult(false, isEnabled(appContext), e.javaClass.simpleName)
        }
    }
}
