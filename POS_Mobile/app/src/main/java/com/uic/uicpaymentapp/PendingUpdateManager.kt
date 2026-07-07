package com.uic.uicpaymentapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.uic.tms.payment_app.TMSDATA
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG                = "PendingUpdateManager"
private const val PREFS_NAME         = "tms_pending_update_prefs"
private const val KEY_PARAM_JSON     = "param_json"
private const val KEY_APP_PKG        = "app_update_pkg"
private const val KEY_APP_VER        = "app_update_ver"
private const val KEY_APP_VER_CODE   = "app_update_ver_code"

// Sent by UIC Home before installing an APK — payment app responds with ACTION_PRE_INSTALL_RESPONSE.
const val ACTION_PRE_INSTALL_CHECK    = "com.uic.home.ACTION_PRE_INSTALL_CHECK"
const val EXTRA_PRE_INSTALL_PKG       = "pkg"
const val EXTRA_PRE_INSTALL_VER       = "ver"
const val EXTRA_PRE_INSTALL_VER_CODE  = "verCode"
const val EXTRA_PRE_INSTALL_SENDER    = "senderPkg"

// Sent by payment app back to UIC Home.
const val ACTION_PRE_INSTALL_RESPONSE = "com.uic.uicpaymentapp.ACTION_PRE_INSTALL_RESPONSE"
const val EXTRA_PRE_INSTALL_PROCEED   = "proceed"

// Sent by UIC Home when it has no pending install task for a package (dismisses stale flag).
const val ACTION_PRE_INSTALL_DISMISS  = "com.uic.home.ACTION_PRE_INSTALL_DISMISS"

/**
 * Singleton that tracks deferred update state across the payment application.
 *
 * Two kinds of deferred update:
 *  1. **Param update** — TMS pushed new parameters while there were unsettled transactions.
 *     The new JSON is persisted and applied after the next successful settlement.
 *  2. **App update** — UIC Home wants to install a new APK but the terminal is busy.
 *     The install is postponed; when the terminal becomes idle UIC Home is notified to proceed.
 *
 * [isOperationInProgress] must be set to `true` by transaction/settlement code before starting
 * an operation and back to `false` when it ends so receivers can gate responses correctly.
 */
object PendingUpdateManager {

    /** True while a card transaction or settlement is in progress. Set by callers. */
    @Volatile
    var isOperationInProgress: Boolean = false

    // ── Param update ─────────────────────────────────────────────────────────

    private val _paramUpdatePending = MutableStateFlow(false)
    val paramUpdatePending: StateFlow<Boolean> = _paramUpdatePending.asStateFlow()

    fun storePendingParamUpdate(context: Context, jsonText: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PARAM_JSON, jsonText).apply()
        _paramUpdatePending.value = true
        Log.i(TAG, "Param update stored — pending settlement")
    }

    fun hasPendingParamUpdate(context: Context): Boolean {
        val json    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PARAM_JSON, null)
        val pending = !json.isNullOrBlank()
        _paramUpdatePending.value = pending
        return pending
    }

    /**
     * Applies the stored param JSON via [UICApplication.applyTmsUpdate] and clears the flag.
     * Must be called from a background thread (disk + parsing).
     */
    fun applyPendingParamUpdate(context: Context): Boolean {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonText = prefs.getString(KEY_PARAM_JSON, null) ?: return false
        return try {
            val db = TMSDATA.parse(jsonText)
            val app = UICApplication.instanceOrNull
            if (app == null) {
                Log.e(TAG, "UICApplication not ready — cannot apply deferred param update")
                return false
            }
            val applied = app.applyTmsUpdate(db)
            if (applied) {
                clearPendingParamUpdate(context)
                Log.i(TAG, "Deferred param update applied")
            } else {
                Log.e(TAG, "applyTmsUpdate rejected deferred PAYMENT_APP params")
            }
            applied
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply deferred param update: ${e.message}", e)
            false
        }
    }

    fun clearPendingParamUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PARAM_JSON).apply()
        _paramUpdatePending.value = false
    }

    // ── App update ────────────────────────────────────────────────────────────

    private val _appUpdatePending = MutableStateFlow(false)
    val appUpdatePending: StateFlow<Boolean> = _appUpdatePending.asStateFlow()

    fun storePendingAppUpdate(context: Context, pkg: String, ver: String, verCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_APP_PKG, pkg)
            .putString(KEY_APP_VER, ver)
            .putInt(KEY_APP_VER_CODE, verCode)
            .apply()
        _appUpdatePending.value = true
        Log.i(TAG, "App update postponed: pkg=$pkg ver=$ver verCode=$verCode")
    }

    fun hasPendingAppUpdate(context: Context): Boolean {
        val pkg     = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_PKG, null)
        val pending = !pkg.isNullOrBlank()
        _appUpdatePending.value = pending
        return pending
    }

    fun getPendingAppUpdatePackage(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_PKG, null)

    fun clearPendingAppUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_APP_PKG).remove(KEY_APP_VER).remove(KEY_APP_VER_CODE).apply()
        _appUpdatePending.value = false
    }

    /**
     * Broadcasts [ACTION_PRE_INSTALL_RESPONSE] with `proceed = true` to all installed UIC Home
     * flavors, signalling that the terminal is now idle and the pending APK install can proceed.
     */
    fun notifyUicHomeCanProceed(context: Context) {
        val pkg = getPendingAppUpdatePackage(context) ?: return
        val probe     = Intent(ACTION_PRE_INSTALL_CHECK)
        val receivers = context.packageManager.queryBroadcastReceivers(probe, PackageManager.GET_META_DATA)
        if (receivers.isEmpty()) {
            Log.w(TAG, "No UIC Home receivers found — clearing stale app update pending flag")
            clearPendingAppUpdate(context)
            return
        }
        receivers.forEach { ri ->
            val homePkg = ri.activityInfo.packageName
            Log.i(TAG, "Notifying UIC Home $homePkg: app update can proceed for $pkg")
            context.sendBroadcast(Intent(ACTION_PRE_INSTALL_RESPONSE).apply {
                `package`               = homePkg
                putExtra(EXTRA_PRE_INSTALL_PKG,     pkg)
                putExtra(EXTRA_PRE_INSTALL_PROCEED, true)
            })
        }
    }

    /** Restore in-memory StateFlow values from persisted state on app start. */
    fun restoreFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _paramUpdatePending.value = !prefs.getString(KEY_PARAM_JSON, null).isNullOrBlank()
        _appUpdatePending.value   = !prefs.getString(KEY_APP_PKG,    null).isNullOrBlank()
    }
}
