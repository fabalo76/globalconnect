package one.globalconnect.xtmsagent.debug

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import one.globalconnect.xtmsagent.policy.FactoryTmsManager

class DebugDeviceOwnerControlReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CLEAR_DEVICE_OWNER_ACTION -> {
                val devicePolicyManager =
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (devicePolicyManager.isDeviceOwnerApp(context.packageName)) {
                    devicePolicyManager.clearDeviceOwnerApp(context.packageName)
                    Log.i(TAG, "Debug build relinquished device ownership")
                }
            }
            SET_FACTORY_TMS_ENABLED_ACTION -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val result = FactoryTmsManager.setEnabled(context, enabled)
                        Log.i(
                            TAG,
                            "Factory TMS debug request enabled=$enabled success=${result.success} " +
                                "changed=${result.changed} state=${result.status.state} " +
                                "code=${result.code}",
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val CLEAR_DEVICE_OWNER_ACTION =
            "one.globalconnect.xtmsagent.DEBUG_CLEAR_DEVICE_OWNER"
        const val SET_FACTORY_TMS_ENABLED_ACTION =
            "one.globalconnect.xtmsagent.DEBUG_SET_FACTORY_TMS_ENABLED"
        const val EXTRA_ENABLED = "enabled"
        const val TAG = "DebugDeviceOwner"
    }
}
