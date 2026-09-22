package one.globalconnect.pinpad

import android.app.Application
import android.util.Log
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.DeviceEngine
import one.globalconnect.pinpad.device.NexgoDeviceInfoProvider
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.config.PinpadTmsConfigClient

class PinpadApplication : Application() {
    @Volatile var audioForeground: ((Boolean) -> Unit)? = null
    val audioRecordings by lazy {
        one.globalconnect.pinpad.audio.AudioRecordingController(
            supported = { one.globalconnect.pinpad.audio.AudioRecordingPolicy.supports(deviceInfoProvider.modelName()) },
            permissionGranted = { androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            storeFactory = { one.globalconnect.pinpad.audio.AudioRecordingStore(java.io.File(filesDir, "audio-recordings")) },
            capture = { file -> one.globalconnect.pinpad.audio.AndroidAudioCapture(this, file) { enabled ->
                (audioForeground ?: error("Pinpad service is unavailable"))(enabled)
            } },
        )
    }
    val deviceEngine: DeviceEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        APIProxy.getDeviceEngine(this)
    }

    val deviceInfoProvider: NexgoDeviceInfoProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NexgoDeviceInfoProvider(deviceEngine)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        one.globalconnect.pinpad.logging.ProductionLog.initialize(this)
        one.globalconnect.pinpad.logging.ConnectionLog.initialize(this)
        PinpadTraceLog.device(
            "app start versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE} " +
                "buildType=${BuildConfig.BUILD_TYPE}",
        )
        runCatching { deviceEngine.deviceInfo }
            .onFailure { Log.w(TAG, "Nexgo device engine not ready yet", it) }
        PinpadTmsConfigClient.request(this)
    }

    companion object {
        private const val TAG = "PinpadApplication"

        lateinit var instance: PinpadApplication
            private set
    }
}
