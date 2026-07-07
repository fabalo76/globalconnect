package com.uic.uicpaymentapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.uic.uicpaymentapp.transaction.CheckStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "TmsAppUpdateReceiver"

/**
 * Receives [ACTION_PRE_INSTALL_CHECK] from UIC Home before it installs an APK update for this app.
 *
 * Responds with [ACTION_PRE_INSTALL_RESPONSE]:
 *  - `proceed = true`  → terminal is idle; UIC Home may install immediately.
 *  - `proceed = false` → terminal is busy; UIC Home must defer the install.
 *    The pending update info is persisted so we can re-send `proceed = true` once idle.
 *
 * "Busy" means either:
 *  - [PendingUpdateManager.isOperationInProgress] is true (active transaction or settlement), OR
 *  - There are unsettled transactions in the transaction log (Open or NeedTip status).
 */
class TmsAppUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PRE_INSTALL_DISMISS -> {
                val pkg = intent.getStringExtra(EXTRA_PRE_INSTALL_PKG) ?: return
                Log.i(TAG, "PRE_INSTALL_DISMISS received for $pkg — clearing stale pending flag")
                if (PendingUpdateManager.getPendingAppUpdatePackage(context) == pkg) {
                    PendingUpdateManager.clearPendingAppUpdate(context)
                }
                return
            }
            ACTION_PRE_INSTALL_CHECK -> { /* handled below */ }
            else -> return
        }

        val pkg       = intent.getStringExtra(EXTRA_PRE_INSTALL_PKG) ?: return
        val ver       = intent.getStringExtra(EXTRA_PRE_INSTALL_VER) ?: ""
        val verCode   = intent.getIntExtra(EXTRA_PRE_INSTALL_VER_CODE, 0)
        val senderPkg = intent.getStringExtra(EXTRA_PRE_INSTALL_SENDER) ?: ""

        Log.i(TAG, "PRE_INSTALL_CHECK received: pkg=$pkg ver=$ver verCode=$verCode from=$senderPkg")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inProgress = PendingUpdateManager.isOperationInProgress
                val unsettledCount = try {
                    UICApplication.instance.container.transactionRepository
                        .getOpenAndNeedTipTransactionNumber()
                        .first()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not query unsettled transactions: ${e.message}", e)
                    0
                }

                val canProceed = !inProgress && unsettledCount == 0
                Log.i(TAG, "PRE_INSTALL_CHECK response: pkg=$pkg proceed=$canProceed " +
                    "(inProgress=$inProgress unsettled=$unsettledCount)")

                if (!canProceed) {
                    PendingUpdateManager.storePendingAppUpdate(context, pkg, ver, verCode)
                }

                sendResponse(context, senderPkg, pkg, canProceed)

            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun sendResponse(context: Context, senderPkg: String, pkg: String, proceed: Boolean) {
        val response = Intent(ACTION_PRE_INSTALL_RESPONSE).apply {
            if (senderPkg.isNotBlank()) `package` = senderPkg
            putExtra(EXTRA_PRE_INSTALL_PKG,     pkg)
            putExtra(EXTRA_PRE_INSTALL_PROCEED, proceed)
        }
        context.sendBroadcast(response)
    }
}
