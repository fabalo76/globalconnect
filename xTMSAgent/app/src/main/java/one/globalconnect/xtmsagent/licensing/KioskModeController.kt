package one.globalconnect.xtmsagent.licensing

import android.content.Context
import android.util.Log
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.SystemServiceHelper
import com.nexgo.oaf.apiv3.platform.OnPlatformInitListener

object KioskModeController {
    private const val TAG = "KioskModeController"

    fun setLocked(context: Context, locked: Boolean, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        runCatching {
            SystemServiceHelper.getInstance().init(context, object : OnPlatformInitListener {
                override fun onPlatformInitResult(resultCode: Int) {
                    runCatching {
                        val ui = SystemServiceHelper.getInstance().getSystemUIManager()
                        ui?.enableControlBar(!locked)
                        ui?.enableMessageBar(!locked)
                        ui?.enableHome(!locked)
                        ui?.enableRecv(!locked)
                        val platform = APIProxy.getDeviceEngine(context).platform
                        if (locked) platform.hideNavigationBar() else platform.showNavigationBar()
                        Log.i(TAG, "Nexgo application kiosk mode locked=$locked result=$resultCode")
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
