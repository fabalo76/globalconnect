package one.globalconnect.xtmsagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import one.globalconnect.xtmsagent.mqtt.versions.AppInstallPendingStore
import one.globalconnect.xtmsagent.mqtt.versions.AppUpdateManager

private const val TAG = "AppConsentReceiver"

private const val ACTION_PRE_INSTALL_RESPONSE = "one.globalconnect.paymentapp.ACTION_PRE_INSTALL_RESPONSE"
private const val ACTION_PRE_INSTALL_DISMISS  = "one.globalconnect.xtmsagent.ACTION_PRE_INSTALL_DISMISS"
private const val EXTRA_PKG                   = "pkg"
private const val EXTRA_PROCEED               = "proceed"

/**
 * Receives [ACTION_PRE_INSTALL_RESPONSE] from the target application (e.g. the payment app)
 * after xTMSAgent sent a [ACTION_PRE_INSTALL_CHECK] pre-install consent request.
 *
 * Two scenarios:
 *
 * 1. **In-flight response** — [AppUpdateManager.onConsentResponse] resolves the [kotlinx.coroutines.CompletableDeferred]
 *    that the active download coroutine is awaiting.  The coroutine then decides immediately.
 *
 * 2. **Deferred "proceed"** — The initial install was postponed and there is no waiting
 *    coroutine.  If a [AppInstallPendingStore.PendingInstallInfo] exists for the package,
 *    we double-check the installed version and trigger the install if still needed.
 *    If no pending install is found (stale flag in the target app), we send [ACTION_PRE_INSTALL_DISMISS].
 */
class AppConsentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            handleResponse(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Pre-install consent broadcast was rejected", e)
        }
    }

    private fun handleResponse(context: Context, intent: Intent) {
        if (intent.action != ACTION_PRE_INSTALL_RESPONSE) return

        val pkg     = intent.getStringExtra(EXTRA_PKG) ?: return
        val proceed = intent.getBooleanExtra(EXTRA_PROCEED, false)

        Log.i(TAG, "PRE_INSTALL_RESPONSE: pkg=$pkg proceed=$proceed")

        // Try to resolve an in-flight consent check first.
        val resolved = AppUpdateManager.onConsentResponse(pkg, proceed)
        if (resolved) return

        // No waiting coroutine — this is a deferred "proceed" after a previous postpone.
        if (!proceed) return  // Spurious postpone with no active check; ignore.

        val pending = AppInstallPendingStore.get(context, pkg)
        if (pending == null) {
            Log.i(TAG, "No pending install for $pkg — sending DISMISS to clear stale flag")
            sendDismiss(context, pkg)
            return
        }

        // Double-check: don't reinstall if already on the correct version.
        val installedCode = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).longVersionCode
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (installedCode != null && installedCode == pending.serverVersionCode.toLong()) {
            Log.i(TAG, "pkg=$pkg already at verCode=${pending.serverVersionCode} — no reinstall needed")
            AppInstallPendingStore.remove(context, pkg)
            return
        }

        Log.i(TAG, "Resuming deferred install for $pkg: installedCode=$installedCode serverCode=${pending.serverVersionCode}")
        AppUpdateManager.installFromPending(context, pending)
    }

    private fun sendDismiss(context: Context, pkg: String) {
        val probe     = Intent(ACTION_PRE_INSTALL_DISMISS)
        val receivers = context.packageManager.queryBroadcastReceivers(probe, 0)
        receivers.filter { it.activityInfo.packageName == pkg }.forEach { ri ->
            context.sendBroadcast(Intent(ACTION_PRE_INSTALL_DISMISS).apply {
                `package` = ri.activityInfo.packageName
                putExtra(EXTRA_PKG, pkg)
            })
        }
    }
}
