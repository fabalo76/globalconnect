package com.uic.pinpad

import android.app.Application
import android.util.Log
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.DeviceEngine
import com.uic.pinpad.device.NexgoDeviceInfoProvider
import com.uic.pinpad.logging.PinpadTraceLog

class PinpadApplication : Application() {
    val deviceEngine: DeviceEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        APIProxy.getDeviceEngine(this)
    }

    val deviceInfoProvider: NexgoDeviceInfoProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NexgoDeviceInfoProvider(deviceEngine)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PinpadTraceLog.device(
            "app start versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE} " +
                "buildType=${BuildConfig.BUILD_TYPE}",
        )
        runCatching { deviceEngine.deviceInfo }
            .onFailure { Log.w(TAG, "Nexgo device engine not ready yet", it) }
    }

    companion object {
        private const val TAG = "PinpadApplication"

        lateinit var instance: PinpadApplication
            private set
    }
}
