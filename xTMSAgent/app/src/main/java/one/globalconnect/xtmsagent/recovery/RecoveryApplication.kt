package one.globalconnect.xtmsagent.recovery

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import java.io.File

/** Independent startup: no launcher, vendor SDK, credentials, MQTT or WorkManager. */
class RecoveryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(this, "uncaught ${thread.name}\n${error.stackTraceToString()}")
            previous?.uncaughtException(thread, error)
        }
        enterRecovery(this, false)
    }

    companion object {
    fun enterRecovery(context: Context, includeNormalComponents: Boolean) {
        with(context) {
        record(this, "Recovery startup version=${one.globalconnect.xtmsagent.BuildConfig.VERSION_NAME} automatic=$includeNormalComponents")
        runCatching {
            packageManager.clearPackagePreferredActivities(packageName)
            record(this, "Recovery: own normal defaults cleared")
        }.onFailure { record(this, "Clear normal defaults failed: $it") }
        // WorkManager can persist explicit enabled overrides across APK updates,
        // which take precedence over the recovery manifest's disabled defaults.
        runCatching {
            getSystemService(android.app.job.JobScheduler::class.java).cancelAll()
            val saved = getSharedPreferences("recovery_component_states", MODE_PRIVATE)
            val info = packageManager.getPackageInfo(packageName,
                android.content.pm.PackageManager.GET_SERVICES or
                    android.content.pm.PackageManager.GET_RECEIVERS or
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
            val names = info.services.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name } +
                if (includeNormalComponents) listOf("one.globalconnect.xtmsagent.MainActivity") else emptyList()
            for (name in names.filter { it.startsWith("androidx.work.") ||
                (includeNormalComponents && it.startsWith("one.globalconnect.xtmsagent.") &&
                    it !in listOf("one.globalconnect.xtmsagent.TmsDeviceAdminReceiver", "one.globalconnect.xtmsagent.mqtt.BootReceiver")) }) {
                val component = ComponentName(packageName, name)
                if (!saved.contains(name)) {
                    check(saved.edit().putInt(name, packageManager.getComponentEnabledSetting(component)).commit())
                }
                packageManager.setComponentEnabledSetting(component,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
            }
        }.onFailure { record(this, "Background isolation failed: ${it.stackTraceToString()}") }
        runCatching {
            val policy = getSystemService(DevicePolicyManager::class.java)
            if (policy.isDeviceOwnerApp(packageName)) {
                policy.clearPackagePersistentPreferredActivities(
                    ComponentName(packageName, "one.globalconnect.xtmsagent.TmsDeviceAdminReceiver"), packageName)
                record(this, "Persistent HOME preference cleared; device ownership retained")
            }
        }.onFailure { record(this, "HOME release failed: ${it.stackTraceToString()}") }
        if (includeNormalComponents) runCatching {
            startActivity(android.content.Intent(this, RecoveryActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { record(this, "Recovery UI will open on next launcher entry: $it") }
        }
    }

        fun restoreBackgroundComponents(context: Context) {
            val saved = context.getSharedPreferences("recovery_component_states", MODE_PRIVATE)
            for ((name, value) in saved.all) {
                if ((!name.startsWith("androidx.work.") && !name.startsWith("one.globalconnect.xtmsagent.")) || value !is Int) continue
                runCatching {
                    context.packageManager.setComponentEnabledSetting(ComponentName(context.packageName, name),
                        value, android.content.pm.PackageManager.DONT_KILL_APP)
                    saved.edit().remove(name).commit()
                }.onFailure { record(context, "Restore component $name failed: $it") }
            }
        }

        @Synchronized fun record(context: Context, text: String) {
            one.globalconnect.xtmsagent.diagnostics.DailyFileLog.record(context, text)
            runCatching {
                val file = File(context.filesDir, "diagnostics/recovery-events.log")
                file.parentFile?.mkdirs()
                if (file.length() > 256 * 1024) file.writeText(file.readText().takeLast(128 * 1024))
                file.appendText("${java.time.Instant.now()} ${text.take(32 * 1024)}\n")
            }
        }
    }
}
