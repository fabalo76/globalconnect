package one.globalconnect.xtmsagent.debug

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DebugDeviceOwnerControlReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CLEAR_DEVICE_OWNER_ACTION) {
            return
        }

        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (devicePolicyManager.isDeviceOwnerApp(context.packageName)) {
            devicePolicyManager.clearDeviceOwnerApp(context.packageName)
            Log.i(TAG, "Debug build relinquished device ownership")
        }
    }

    private companion object {
        const val CLEAR_DEVICE_OWNER_ACTION =
            "one.globalconnect.xtmsagent.DEBUG_CLEAR_DEVICE_OWNER"
        const val TAG = "DebugDeviceOwner"
    }
}
