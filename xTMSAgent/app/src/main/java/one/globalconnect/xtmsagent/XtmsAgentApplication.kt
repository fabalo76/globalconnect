package one.globalconnect.xtmsagent

import android.app.Application
import android.util.Log
import com.nexgo.oaf.apiv3.APIProxy
import one.globalconnect.xtmsagent.mqtt.TmsMqttService
import one.globalconnect.xtmsagent.mqtt.persistence.TmsCredentialStore

private const val TAG = "XtmsAgentApplication"

/**
 * Application class for xTMSAgent.
 *
 * Responsibilities on first launch and on every restart:
 *   1. Obtain the device serial number from the Nexgo SmartPOS SDK.
 *   2. Persist the SN as the MQTT TermID (idempotent — safe to call every time).
 *   3. Persist the TMS broker host from TMSFunc defaults (can be overridden later
 *      by Launcher_Config.JSON via TMSFunc.ChkParamChange).
 *   4. Start TmsMqttService so it begins connecting before any Activity starts.
 *
 * If the Nexgo SDK is unavailable at Application.onCreate() time (rare), the service
 * start is deferred to MainActivity.Init() where vg_sSN is always set first.
 *
 * Registered in AndroidManifest.xml via android:name=".XtmsAgentApplication".
 */
class XtmsAgentApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application started")

        runSafely("credential provisioning") { provisionCredentials() }

        // Apply device-owner kiosk restrictions (e.g. DISALLOW_CONFIG_TETHERING).
        // Idempotent — safe on every restart. No-op if not device owner.
        runSafely("kiosk restriction initialization") {
            TmsDeviceAdminReceiver.applyKioskRestrictions(this)
        }

        try {
        val store = TmsCredentialStore(this)
        if (!store.loadTermId().isNullOrBlank() && !store.loadBrokerHost().isNullOrBlank()) {
            Log.i(TAG, "Terminal provisioned — starting MQTT service")
            TmsMqttService.start(this)
        } else {
            // Provisioning will be completed in MainActivity.Init() once the Nexgo SDK
            // returns the device SN, then the service is started from there.
            Log.w(TAG, "TermID/broker not yet available — MQTT service deferred to MainActivity")
        }
        } catch (e: Exception) {
            Log.e(TAG, "Application MQTT service bootstrap failed; startup will continue", e)
        }
    }

    private inline fun runSafely(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Application $operation failed; startup will continue", e)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun provisionCredentials() {
        val store = TmsCredentialStore(this)

        // Broker host is intentionally NOT saved here — the JSON config has not been
        // parsed yet at Application.onCreate() time. MainActivity.Init() calls
        // TMSFunc.ChkParamChange() first, then always persists the correct server_addr.

        // Try to get the device SN at Application level. This works on most Nexgo ROM
        // builds; if the SDK throws here, MainActivity.Init() is the safety net.
        if (store.loadTermId().isNullOrBlank()) {
            try {
                val sn = APIProxy.getDeviceEngine(this).deviceInfo.sn
                if (!sn.isNullOrBlank()) {
                    store.saveTermId(sn)
                    Log.i(TAG, "TermID provisioned from device SN: $sn")
                } else {
                    Log.w(TAG, "Device SN is blank — deferring TermID provisioning to MainActivity")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nexgo SDK unavailable in Application context: ${e.message}. " +
                    "TermID will be provisioned in MainActivity.Init().")
            }
        }
    }
}
