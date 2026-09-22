package one.globalconnect.xtmsagent.nexgo

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import android.hardware.display.DisplayManager
import android.view.Display
import com.nexgo.oaf.apiv3.SystemServiceHelper
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "NexgoRuntimeInspector"
private const val PSS_PACKAGE = "com.xgd.possystemservice"

data class NexgoPssSnapshot(
    val installed: Boolean,
    val versionName: String?,
    val versionCode: Long?,
    val apkSha256: String?,
    val permissionGateEnabled: Boolean?,
)

data class NexgoRuntimeSnapshot(
    val profile: NexgoDeviceProfile,
    val firmware: String?,
    val securityPatch: String?,
    val pss: NexgoPssSnapshot,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("modelKey", profile.modelKey)
        put("reportedModel", profile.reportedModel)
        put("commandProfileVerified", profile.commandProfileVerified)
        put("expectedCommandBase", profile.expectedCommandBase ?: JSONObject.NULL)
        put("detectedCommandBase", profile.detectedCommandBase ?: JSONObject.NULL)
        profile.display?.let { display ->
            put("display", JSONObject().put("width", display.width).put("height", display.height))
        }
        firmware?.let { put("firmware", it) }
        securityPatch?.let { put("securityPatch", it) }
        put("capabilities", JSONObject().apply {
            profile.capabilities.forEach { (capability, state) ->
                put(capability.wireName, state.wireValue)
            }
        })
        put("firmwareProfileSettings", org.json.JSONArray(
            one.globalconnect.xtmsagent.profiles.NexgoProfileCommands.forFirmware(
                profile.modelKey, profile.detectedCommandBase, pss.apkSha256,
            ).keys.toList(),
        ))
        put("pss", JSONObject().apply {
            put("installed", pss.installed)
            pss.versionName?.let { put("versionName", it) }
            pss.versionCode?.let { put("versionCode", it) }
            pss.apkSha256?.let { put("apkSha256", it) }
            pss.permissionGateEnabled?.let { put("permissionGateEnabled", it) }
        })
        put("securityFindings", org.json.JSONArray().apply {
            when (pss.permissionGateEnabled) {
                false -> put("pss_permission_gate_open")
                null -> put("pss_permission_gate_unknown")
                true -> Unit
            }
            if (!profile.isKnownModel) put("nexgo_model_unsupported")
            if (profile.isKnownModel && !profile.commandProfileVerified) {
                put(if (profile.expectedCommandBase == null || profile.detectedCommandBase == null)
                    "nexgo_command_profile_unverified" else "nexgo_command_profile_mismatch")
            }
        })
    }
}

object NexgoRuntimeInspector {
    private val apkHashCache = ConcurrentHashMap<String, String>()

    fun inspect(context: Context): NexgoRuntimeSnapshot {
        val modelProperty = AndroidSystemProperties.get("ro.xgd.type")
        val commandBase = firstNonBlank(
            AndroidSystemProperties.get("ro.xgd.pss.basecmd"),
            AndroidSystemProperties.get("ro.xgd.cmd.base"),
        )
        val resolved = NexgoProfileResolver.resolve(modelProperty, Build.MODEL, commandBase)
        val profile = if (resolved.modelKey == "N6ProLite") {
            resolved.copy(display = physicalDisplay(context), capabilities = resolved.capabilities +
                (NexgoCapability.DEVICE_OWNER to if (TmsDeviceAdminReceiver.isDeviceOwner(context))
                    NexgoCapabilityState.SUPPORTED else NexgoCapabilityState.RUNTIME_PROBE_REQUIRED))
        } else resolved
        val permissionGate = AndroidSystemProperties.get("sys.xgd.pss.perms")?.isNotBlank()
        val packageInfo = readPackageInfo(context)

        return NexgoRuntimeSnapshot(
            profile = profile,
            firmware = firstNonBlank(
                AndroidSystemProperties.get("ro.product.firmware"),
                AndroidSystemProperties.get("ro.build.display.id"),
            ),
            securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
            pss = NexgoPssSnapshot(
                installed = packageInfo != null,
                versionName = packageInfo?.versionName,
                versionCode = packageInfo?.longVersionCode,
                apkSha256 = packageInfo?.applicationInfo?.sourceDir?.let(::sha256),
                permissionGateEnabled = permissionGate,
            ),
        )
    }

    fun physicalDisplay(context: Context): NexgoDisplaySpec? = runCatching {
        val mode = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.mode ?: return@runCatching null
        NexgoDisplaySpec(minOf(mode.physicalWidth, mode.physicalHeight),
            maxOf(mode.physicalWidth, mode.physicalHeight))
    }.getOrNull()

    fun commandDiagnostics(): JSONObject = JSONObject().apply {
        listOf("ro.xgd.type", "ro.xgd.pss.basecmd", "ro.xgd.cmd.base").forEach {
            put(it, AndroidSystemProperties.get(it) ?: JSONObject.NULL)
        }
        put("sdkCommandBase", runCatching { SystemServiceHelper.getCMDBASE() }.getOrNull()
            ?: JSONObject.NULL)
    }

    @Suppress("DEPRECATION")
    private fun readPackageInfo(context: Context): PackageInfo? = try {
        context.packageManager.getPackageInfo(PSS_PACKAGE, 0)
    } catch (exception: Exception) {
        Log.w(TAG, "Nexgo PSS package metadata unavailable: ${exception.message}")
        null
    }

    private fun sha256(path: String): String? {
        val file = File(path)
        if (!file.isFile) return null
        val cacheKey = "$path:${file.length()}:${file.lastModified()}"
        return apkHashCache[cacheKey] ?: try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02X".format(it) }
                .also { apkHashCache[cacheKey] = it }
        } catch (exception: Exception) {
            Log.w(TAG, "Nexgo PSS fingerprint unavailable: ${exception.message}")
            null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal object AndroidSystemProperties {
    private val getMethod by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        }.getOrNull()
    }

    fun get(name: String): String? {
        val method = getMethod ?: return null
        return runCatching { method.invoke(null, name) as? String }.getOrNull()
    }
}
