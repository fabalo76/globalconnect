package one.globalconnect.xtmsagent.diagnostics

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.globalconnect.xtmsagent.BuildConfig
import one.globalconnect.xtmsagent.nexgo.NexgoDeviceOwnerProvisioner
import one.globalconnect.xtmsagent.remote.RemoteControlAccessibilityProvisioner
import java.util.UUID

object DeviceSetupRetry {
    private val mutex = Mutex()

    suspend fun run(context: Context): DiagnosticsExport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            val id = UUID.randomUUID().toString().take(8)
            val started = SystemClock.elapsedRealtime()
            var export: DiagnosticsExport? = null
            NexgoDiagnosticsManager.record(app, "retry=$id begin version=${BuildConfig.VERSION_NAME}")
            NexgoDiagnosticsManager.recordSetupState(app, "retry=$id before")
            try {
                val owner = NexgoDeviceOwnerProvisioner.ensureDeviceOwner(app)
                NexgoDiagnosticsManager.record(app,
                    "retry=$id owner success=${owner.success} code=${owner.code} sdkResult=${owner.sdkResultCode}")
                val accessibility = RemoteControlAccessibilityProvisioner.ensureEnabled(app)
                NexgoDiagnosticsManager.record(app,
                    "retry=$id accessibility success=${accessibility.success} code=${accessibility.code}")
            } catch (error: CancellationException) {
                NexgoDiagnosticsManager.record(app, "retry=$id cancelled")
                throw error
            } catch (error: Exception) {
                NexgoDiagnosticsManager.recordException(app, "retry=$id", error)
            } finally {
                // Retain and export the attempt even if the user leaves the screen during setup.
                withContext(NonCancellable) {
                    runCatching { SetupLogcatCapture.capture(app, id) }
                        .onFailure { NexgoDiagnosticsManager.recordException(app, "retry=$id logcat", it) }
                    NexgoDiagnosticsManager.recordSetupState(app, "retry=$id after")
                    NexgoDiagnosticsManager.record(app,
                        "retry=$id end elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    export = runCatching { NexgoDiagnosticsManager.exportToPublicDownloads(app) }
                        .onFailure { NexgoDiagnosticsManager.recordException(app, "retry=$id export", it) }
                        .getOrNull()
                }
            }
            checkNotNull(export) { "Retry recorded internally, but Downloads export failed. Use Export to Downloads to retry." }
        }
    }
}
