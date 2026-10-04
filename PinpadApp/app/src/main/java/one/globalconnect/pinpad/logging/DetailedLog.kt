package one.globalconnect.pinpad.logging

import android.content.Context
import one.globalconnect.logging.DeviceLogStore

/** The portal owns capture; the terminal preference controls verbosity only. */
object DetailedLog {
    @Volatile private var context: Context? = null
    @Volatile private var recorder: DetailedLogRecorder? = null
    @Volatile private var verbose = false

    fun initialize(value: Context) {
        val app = value.applicationContext
        context = app
        verbose = app.getSharedPreferences("diagnostic-detail", Context.MODE_PRIVATE).getBoolean("verbose", false)
        recorder = DetailedLogRecorder(
            enabled = { DeviceLogStore.enabled(app) },
            verbose = { verbose },
            write = { DeviceLogStore.record(app, it) },
            onFailure = { android.util.Log.w("PinpadDiagnostics", "Detailed logging unavailable: ${it.javaClass.simpleName}") },
        )
    }

    fun expiresAt(): Long = runCatching { context?.let(DeviceLogStore::expiresAt) ?: 0L }.getOrDefault(0L)

    fun verboseEnabled(): Boolean = verbose

    fun setVerbose(enabled: Boolean) {
        val app = checkNotNull(context)
        check(app.getSharedPreferences("diagnostic-detail", Context.MODE_PRIVATE).edit()
            .putBoolean("verbose", enabled).commit()) { "Unable to save diagnostic verbosity" }
        verbose = enabled
        normal("DIAGNOSTICS extraDetail=$enabled")
    }

    fun record(message: String) { recorder?.record(message) }
    fun normal(message: String) { recorder?.recordNormal(message) }
}
