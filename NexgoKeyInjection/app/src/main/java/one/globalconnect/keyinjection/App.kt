package one.globalconnect.keyinjection
import android.app.Application
import android.widget.Toast
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.platform.Platform
import one.globalconnect.keyinjection.util.Logger

/**
 * Application subclass that initializes the Nexgo device abstraction layer
 * and configures global logging.
 */
class App : Application() {

    /** Lazily created instance of the Nexgo device abstraction layer. */
    val deviceEngine: DeviceEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            APIProxy.getDeviceEngine(this)
        } catch (t: Throwable) {
            Toast.makeText(
                applicationContext,
                getString(R.string.nexgo_device_engined_init_failed, t.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            throw t
        }
    }
    /** Lazily created instance of the platform abstraction. */
    val platform: Platform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            deviceEngine.platform
        } catch (t: Throwable) {
            Toast.makeText(
                applicationContext,
                getString(R.string.nexgo_platform_init_failed, t.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            throw t
        }
    }
    /**
     * Initialize global state and enable file logging when the application is
     * created.
     */
    override fun onCreate() {
        super.onCreate()
        _instance = this
        if (BuildConfig.DEBUG) {
            Logger.enableFileLogging(this)
        } else {
            Logger.disableFileLogging()
        }
        Logger.i(TAG, "Starting GlobalConnectKeyInjection version ${BuildConfig.VERSION_NAME}")
        // If you *really* want to warm it up early, do it off the main thread:
        // CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { val _ = dal }
    }

    companion object {
        private const val TAG = "App"
        @Volatile
        private var _instance: App? = null
        val instance: App
            get() = _instance ?: error("App not created yet")

        // Convenient top-level access
        val deviceEngine: DeviceEngine get() = instance.deviceEngine

        /** Device model reported by the terminal. */
        val model: String by lazy { deviceEngine.deviceInfo.model }

        /** Device serial number reported by the terminal. */
        val serialNumber: String by lazy { deviceEngine.deviceInfo.sn }
    }
}
