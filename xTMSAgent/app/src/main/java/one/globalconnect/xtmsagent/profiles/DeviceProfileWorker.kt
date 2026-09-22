package one.globalconnect.xtmsagent.profiles

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import org.json.JSONObject
import java.util.TimeZone
import kotlin.math.roundToInt

class DeviceProfileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val context = applicationContext
        val cache = context.getSharedPreferences("device_profile_results", Context.MODE_PRIVATE)
        val cached = cache.getString(taskId, null)
        val result = if (cached != null) JSONObject(cached) else {
            val value = runCatching { applyProfile(context, taskId, JSONObject(inputData.getString("payload") ?: "{}")) }
                .getOrElse { if (it is CancellationException) throw it; JSONObject().put("success", false).put("results", "PROFILE_INVALID") }
            // Commit before ACK: duplicate retained delivery must never reapply an older profile.
            if (!cache.edit().putString(taskId, value.toString()).commit()) return Result.retry()
            value
        }
        val success = result.getBoolean("success")
        TmsMqttManager.publishExternalTaskAck(context, taskId, success,
            if (success) null else "DEVICE_PROFILE_APPLY_FAILED", result.optString("status", if (success) "applied" else "failed"),
            result.get("results").toString())
        return Result.success()
    }

    companion object {
        private val order = listOf("automaticTime", "automaticTimeZone", "timeZone", "automaticBrightness", "brightnessPercent",
            "screenTimeoutSeconds", "mediaVolumePercent", "alarmVolumePercent", "notificationVolumePercent", "locationEnabled",
            "ethernetEnabled", "bluetoothEnabled", "wifiEnabled", "mobileDataEnabled", "airplaneModeEnabled")
        private val applyMutex = Mutex()
        private val fallbackScreenTimeoutValues = setOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)

        fun enqueue(context: Context, taskId: String, payload: JSONObject?) {
            val request = OneTimeWorkRequestBuilder<DeviceProfileWorker>()
                .setInputData(workDataOf("taskId" to taskId, "payload" to (payload?.toString() ?: "{}"))).build()
            // A single persistent chain serializes profiles, including across process death and reboot.
            WorkManager.getInstance(context).enqueueUniqueWork("device-profile-apply", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context, taskId: String): Boolean = context.getSharedPreferences("device_profile_cancelled", Context.MODE_PRIVATE)
            .edit().putBoolean(taskId, true).commit()

        private fun cancelled(context: Context, taskId: String) = context.getSharedPreferences("device_profile_cancelled", Context.MODE_PRIVATE).getBoolean(taskId, false)

        internal fun validSettings(settings: JSONObject): Boolean {
            if (settings.length() == 0 || settings.keys().asSequence().any { it !in order }) return false
            for (key in settings.keys()) {
                val value = settings.get(key)
                val valid = when (key) {
                    "automaticBrightness", "automaticTime", "automaticTimeZone", "locationEnabled",
                    "wifiEnabled", "bluetoothEnabled", "ethernetEnabled", "mobileDataEnabled", "airplaneModeEnabled" -> value is Boolean
                    "timeZone" -> value is String && validTimeZone(value)
                    "screenTimeoutSeconds" -> value is Int && value in 15..1800
                    else -> value is Int && value in 0..100
                }
                if (!valid) return false
            }
            return (!settings.has("brightnessPercent") || settings.opt("automaticBrightness") == false)
                && (!settings.has("timeZone") || settings.opt("automaticTimeZone") == false)
                && (settings.opt("airplaneModeEnabled") != true ||
                    listOf("wifiEnabled", "bluetoothEnabled", "mobileDataEnabled").none { settings.opt(it) == true })
        }

        /**
         * Settings vendors occasionally ship mismatched localized entry/value arrays.  Reading
         * both arrays and accepting only their shared indices prevents us from selecting a
         * value that makes their Settings UI unable to render the current preference.
         */
        internal fun pairedTimeoutValues(entriesCount: Int, values: Array<String>): Set<Int> =
            values.take(entriesCount.coerceAtLeast(0)).mapNotNull { it.toIntOrNull() }.toSet()

        internal fun validTimeZone(value: String): Boolean = value == "UTC" || value in TimeZone.getAvailableIDs()

        internal fun orderedKeys(settings: JSONObject): List<String> {
            val keys = order.filter { settings.has(it) }
            // Leave airplane mode before enabling radios; enter it only after other settings.
            return if (settings.opt("airplaneModeEnabled") == false)
                listOf("airplaneModeEnabled") + keys.filterNot { it == "airplaneModeEnabled" }
            else keys
        }

        internal fun selectSupportedSettings(unsupported: Map<String, String?>, results: JSONObject): List<String> {
            unsupported.forEach { (key, reason) ->
                if (reason != null) results.put(key, "skipped_$reason")
            }
            return unsupported.filterValues { it == null }.keys.toList()
        }


        private fun supportedScreenTimeoutValues(context: Context): Set<Int> = runCatching {
            val settingsResources = context.createPackageContext("com.android.settings", Context.CONTEXT_IGNORE_SECURITY).resources
            val entriesId = settingsResources.getIdentifier("screen_timeout_entries", "array", "com.android.settings")
            val valuesId = settingsResources.getIdentifier("screen_timeout_values", "array", "com.android.settings")
            if (entriesId == 0 || valuesId == 0) fallbackScreenTimeoutValues else {
                pairedTimeoutValues(settingsResources.getTextArray(entriesId).size, settingsResources.getStringArray(valuesId))
                    .ifEmpty { fallbackScreenTimeoutValues }
            }
        }.getOrDefault(fallbackScreenTimeoutValues)

        internal suspend fun applyProfile(context: Context, taskId: String, payload: JSONObject): JSONObject =
            applyMutex.withLock { applyProfileLocked(context, taskId, payload) }

        private suspend fun applyProfileLocked(context: Context, taskId: String, payload: JSONObject): JSONObject {
            if (cancelled(context, taskId)) return failure("cancelled").put("status", "cancelled")
            val settings = payload.optJSONObject("settings") ?: return failure("PROFILE_INVALID")
            if (payload.optInt("schemaVersion") != 1 || !validSettings(settings)) return failure("PROFILE_INVALID")
            val manager = context.getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(context, TmsDeviceAdminReceiver::class.java)
            val audio = context.getSystemService(AudioManager::class.java)
            val nexgo = NexgoProfileBackend(context)
            val results = JSONObject()
            val keys = orderedKeys(settings)
            val unsupported = keys.associateWith { key -> when {
                key in NexgoProfileCommands.networkSettings && !nexgo.supports(key) -> "firmware_setting_unsupported"
                !key.endsWith("VolumePercent") && !nexgo.supports(key) && !manager.isDeviceOwnerApp(context.packageName) -> "device_owner_required"
                key == "locationEnabled" && Build.VERSION.SDK_INT < 30 -> "requires_android_11"
                key.endsWith("VolumePercent") && audio.isVolumeFixed -> "fixed_volume"
                key == "screenTimeoutSeconds" && settings.getInt(key) * 1000 !in supportedScreenTimeoutValues(context) -> "screen_timeout_not_supported"
                else -> null
            } }
            val supportedKeys = selectSupportedSettings(unsupported, results)
            if (supportedKeys.any(nexgo::supports)) {
                val ready = try { nexgo.initialize() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    false
                }
                if (!ready) {
                    supportedKeys.forEach { results.put(it, "system_service_unavailable") }
                    return JSONObject().put("success", false).put("results", results)
                }
            }
            var success = true
            for (key in supportedKeys) {
                if (cancelled(context, taskId)) { results.put(key, "cancelled"); success = false; continue }
                if (!success) { results.put(key, "not_applied_previous_failure"); continue }
                try {
                    val verify = if (nexgo.supports(key)) nexgo.apply(key, settings)
                        else write(context, manager, admin, audio, key, settings)
                    var verified = verify()
                    repeat(40) { if (!verified) { delay(250); verified = verify() } }
                    results.put(key, if (verified) "applied_verified" else "readback_mismatch")
                    if (!verified) success = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    results.put(key, when (error) {
                        is SecurityException -> "permission_denied"
                        is NexgoProfileCommandException -> "vendor_rejected_${error.resultCode}"
                        else -> "apply_failed"
                    })
                    success = false
                }
            }
            return JSONObject().put("success", success).put("results", results).put("status", if (cancelled(context, taskId)) "cancelled" else if (success) "applied" else "failed")
        }

        @Suppress("DEPRECATION")
        private fun write(context: Context, manager: DevicePolicyManager, admin: ComponentName, audio: AudioManager,
                          key: String, settings: JSONObject): () -> Boolean {
            val resolver = context.contentResolver
            return when (key) {
                "automaticBrightness", "brightnessPercent", "screenTimeoutSeconds" -> {
                    val field = when (key) {
                        "automaticBrightness" -> Settings.System.SCREEN_BRIGHTNESS_MODE
                        "brightnessPercent" -> Settings.System.SCREEN_BRIGHTNESS
                        else -> Settings.System.SCREEN_OFF_TIMEOUT
                    }
                    val value = when (key) {
                        "automaticBrightness" -> if (settings.getBoolean(key)) 1 else 0
                        "brightnessPercent" -> (settings.getInt(key) * 255.0 / 100).roundToInt()
                        else -> settings.getInt(key) * 1000
                    }
                    manager.setSystemSetting(admin, field, value.toString())
                    val read: () -> Boolean = { Settings.System.getInt(resolver, field, -1) == value }
                    read
                }
                "automaticTime", "automaticTimeZone" -> {
                    val enabled = settings.getBoolean(key)
                    val field = if (key == "automaticTime") Settings.Global.AUTO_TIME else Settings.Global.AUTO_TIME_ZONE
                    if (Build.VERSION.SDK_INT >= 30) {
                        if (key == "automaticTime") manager.setAutoTimeEnabled(admin, enabled) else manager.setAutoTimeZoneEnabled(admin, enabled)
                    } else manager.setGlobalSetting(admin, field, if (enabled) "1" else "0")
                    val read: () -> Boolean = { Settings.Global.getInt(resolver, field, -1) == if (enabled) 1 else 0 }
                    read
                }
                "timeZone" -> {
                    val zone = settings.getString(key)
                    check(manager.setTimeZone(admin, zone))
                    val read: () -> Boolean = { TimeZone.getDefault().id == zone }
                    read
                }
                "locationEnabled" -> {
                    if (Build.VERSION.SDK_INT < 30) error("requires_android_11")
                    val enabled = settings.getBoolean(key)
                    manager.setLocationEnabled(admin, enabled)
                    val read: () -> Boolean = { context.getSystemService(LocationManager::class.java).isLocationEnabled == enabled }
                    read
                }
                else -> {
                    val stream = when (key) {
                        "mediaVolumePercent" -> AudioManager.STREAM_MUSIC
                        "alarmVolumePercent" -> AudioManager.STREAM_ALARM
                        else -> AudioManager.STREAM_NOTIFICATION
                    }
                    val min = audio.getStreamMinVolume(stream)
                    val max = audio.getStreamMaxVolume(stream)
                    val value = min + ((max - min) * settings.getInt(key) / 100.0).roundToInt()
                    audio.setStreamVolume(stream, value, 0)
                    val read: () -> Boolean = { audio.getStreamVolume(stream) == value }
                    read
                }
            }
        }
        private fun failure(code: String) = JSONObject().put("success", false).put("results", code)
    }
}
