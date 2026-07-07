package one.globalconnect.xtmsagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import one.globalconnect.xtmsagent.params.ParamConstants
import one.globalconnect.xtmsagent.params.ParamManager

private const val TAG = "ParamReceiver"

/**
 * Receives parameter requests broadcast by the payment application.
 */
class ParamReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ParamConstants.ACTION_REQUEST_PARAMS) return
        val applicationId = intent.getStringExtra(ParamConstants.EXTRA_APPLICATION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val extras = intent.extras?.keySet()
            ?.joinToString(prefix = "[", postfix = "]") { key -> "$key=${intent.extras?.get(key)}" }
            ?: "[]"
        Log.i(
            TAG,
            "Parameter request received action=${intent.action} senderPackage=${intent.`package` ?: "(implicit)"} " +
                "targetHomePackage=${context.packageName} applicationId=${applicationId ?: "(none)"} extras=$extras"
        )
        ParamManager.requestParamDownload(context.applicationContext, applicationId)
    }
}
