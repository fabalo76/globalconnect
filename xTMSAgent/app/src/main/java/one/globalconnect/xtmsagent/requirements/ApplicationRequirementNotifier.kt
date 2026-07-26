package one.globalconnect.xtmsagent.requirements

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

object ApplicationRequirementNotifier {
    fun notifyInstalled(context: Context, payload: JSONObject?) {
        val requestId = payload?.optString(KEY_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return
        val capability = payload.optString(KEY_CAPABILITY).takeIf { it.isNotBlank() } ?: return
        val requesterPackage = payload.optString(KEY_REQUESTER_PACKAGE).takeIf { it.isNotBlank() } ?: return
        val installedPackage = payload.optString(KEY_PACKAGE_NAME).takeIf { it.isNotBlank() } ?: return
        if (!isInstalled(context, installedPackage)) {
            Log.w(TAG, "Requirement task completed but $installedPackage is not installed")
            return
        }

        context.sendBroadcast(
            Intent(ACTION_APPLICATION_REQUIREMENT_READY)
                .setPackage(requesterPackage)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_CAPABILITY, capability)
                .putExtra(EXTRA_PACKAGE_NAME, installedPackage),
        )
        Log.i(
            TAG,
            "Notified $requesterPackage that $capability is ready via $installedPackage",
        )
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    const val ACTION_APPLICATION_REQUIREMENT_READY =
        "one.globalconnect.xtmsagent.APPLICATION_REQUIREMENT_READY"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_CAPABILITY = "capability"
    const val EXTRA_PACKAGE_NAME = "packageName"
    private const val KEY_REQUEST_ID = "requirementRequestId"
    private const val KEY_CAPABILITY = "requirementCapability"
    private const val KEY_REQUESTER_PACKAGE = "requesterPackage"
    private const val KEY_PACKAGE_NAME = "packageName"
    private const val TAG = "AppRequirementNotify"
}
