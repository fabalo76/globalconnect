package one.globalconnect.xtmsagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import one.globalconnect.xtmsagent.profiles.DeviceProfileWorker
import org.json.JSONObject
import java.io.File
import one.globalconnect.xtmsagent.licensing.KioskModeController

/** Shell-only lab entry point; exercises the production apply path without sending cloud ACKs. */
class DebugDeviceProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf("one.globalconnect.xtmsagent.DEBUG_APPLY_DEVICE_PROFILE", "one.globalconnect.xtmsagent.DEBUG_SET_KIOSK")) return
        val payload = intent.getStringExtra("payload") ?: return
        val requestId = intent.getStringExtra("requestId")
        val pending = goAsync()
        fun complete(result: JSONObject) {
            try {
                result.put("requestId", requestId)
                File(context.filesDir, "diagnostics/device-profile-test.json").apply {
                    parentFile?.mkdirs()
                    writeText(result.toString(2))
                }
            } finally { pending.finish() }
        }
        if (intent.action == "one.globalconnect.xtmsagent.DEBUG_SET_KIOSK") {
            val locked = runCatching { JSONObject(payload).getBoolean("locked") }.getOrElse {
                complete(JSONObject().put("success", false)); return
            }
            KioskModeController.setLocked(context, locked) { success, error ->
                complete(JSONObject().put("success", success).put("error", error))
            }
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val result = runCatching {
                    DeviceProfileWorker.applyProfile(context.applicationContext, "debug-local-profile", JSONObject(payload))
                }.getOrElse { JSONObject().put("success", false).put("error", it.javaClass.simpleName) }
                complete(result)
        }
    }
}
