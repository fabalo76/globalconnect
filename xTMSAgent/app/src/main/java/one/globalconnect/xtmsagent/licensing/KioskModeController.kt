package one.globalconnect.xtmsagent.licensing

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.SystemServiceHelper
import com.nexgo.oaf.apiv3.platform.OnPlatformInitListener
import java.util.concurrent.atomic.AtomicLong
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver

object KioskModeController {
    private const val TAG = "KioskModeController"
    private val requestGeneration = AtomicLong(0L)

    fun setLocked(context: Context, locked: Boolean, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        val appContext = context.applicationContext
        val generation = requestGeneration.incrementAndGet()
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val statusBarDisabled = runCatching {
            dpm.isDeviceOwnerApp(appContext.packageName) &&
                dpm.setStatusBarDisabled(TmsDeviceAdminReceiver.componentName(appContext), locked)
        }.onFailure { exception ->
            Log.w(TAG, "Unable to apply Device Owner status-bar policy locked=$locked", exception)
        }.getOrDefault(false)

        runCatching {
            SystemServiceHelper.getInstance().init(appContext, object : OnPlatformInitListener {
                override fun onPlatformInitResult(resultCode: Int) {
                    if (requestGeneration.get() != generation) {
                        Log.i(TAG, "Ignored superseded kiosk request locked=$locked generation=$generation")
                        return
                    }
                    runCatching {
                        check(resultCode == SystemServiceHelper.RETURN_SUCC) { "system_service_rejected" }
                        val ui = requireNotNull(SystemServiceHelper.getInstance().getSystemUIManager())
                        val argument = nexgoUiArgument(android.os.Build.MODEL, locked)
                        val controlBar = ui?.enableControlBar(argument)
                        val messageBar = ui?.enableMessageBar(argument)
                        val home = ui?.enableHome(argument)
                        val recents = ui?.enableRecv(argument)
                        val platform = APIProxy.getDeviceEngine(appContext).platform
                        if (locked) platform.hideNavigationBar() else platform.showNavigationBar()
                        if (usesNexgoDisableFlags(android.os.Build.MODEL)) {
                            check(listOf("sys.xgd.home.disable", "sys.xgd.appswitch.disable", "sys.xgd.swipe.disable").all {
                                one.globalconnect.xtmsagent.nexgo.AndroidSystemProperties.get(it) == locked.toString()
                            }) { "kiosk_readback_mismatch" }
                        }
                        Log.i(
                            TAG,
                            "Nexgo application kiosk mode locked=$locked result=$resultCode " +
                                "statusBarDisabled=$statusBarDisabled controlBar=$controlBar " +
                                "messageBar=$messageBar home=$home recents=$recents",
                        )
                    }.onSuccess {
                        onComplete(true, null)
                    }.onFailure { exception ->
                        Log.w(TAG, "Unable to update Nexgo kiosk mode", exception)
                        onComplete(false, exception.message)
                    }
                }
            })
        }.onFailure { exception ->
            Log.w(TAG, "Unable to initialize Nexgo kiosk service", exception)
            onComplete(false, exception.message)
        }
    }
}
