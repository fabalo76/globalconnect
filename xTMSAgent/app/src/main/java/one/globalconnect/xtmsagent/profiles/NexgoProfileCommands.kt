package one.globalconnect.xtmsagent.profiles

import android.content.Context
import android.provider.Settings
import com.nexgo.oaf.apiv3.SystemServiceHelper
import one.globalconnect.xtmsagent.nexgo.AndroidSystemProperties
import one.globalconnect.xtmsagent.nexgo.NexgoRuntimeInspector
import one.globalconnect.xtmsagent.nexgo.NexgoSystemServiceInitializer
import org.json.JSONObject

/** Exact inspected PSS binaries: versionName alone does not identify available commands. */
internal object NexgoProfileCommands {
    const val CT20_PSS = "8EEBD367738001E6F04974CCB7B78DBDDF7297DF74DE6CA029B7583147D2DA3A"
    const val N96_PSS = "B8CB51C1CF3D722EA6753519DB28A3B9F528CA8FECFF4EF809DB11A996F49753"
    val networkSettings = setOf("wifiEnabled", "bluetoothEnabled", "ethernetEnabled", "mobileDataEnabled", "airplaneModeEnabled")

    fun forFirmware(model: String, base: Int?, hash: String?): Map<String, Int> {
        val prefix = when {
            model in setOf("CT20", "CT20P") && base == 70_000_000 && hash.equals(CT20_PSS, true) -> 700_000_000
            model == "N96" && base == 90_000_000 && hash.equals(N96_PSS, true) -> 900_000_000
            else -> return emptyMap()
        }
        return mapOf(
            "automaticTime" to prefix + 2_306_151,
            "automaticTimeZone" to prefix + 2_306_152,
            "timeZone" to prefix + 2_306_153,
            "locationEnabled" to prefix + 2_310_194,
            "wifiEnabled" to prefix + 2_310_192,
            "bluetoothEnabled" to prefix + 2_310_193,
            "ethernetEnabled" to prefix + 2_210_181,
        ) + if (model == "N96") mapOf(
            "mobileDataEnabled" to 902_506_171,
            "airplaneModeEnabled" to 902_506_172,
        ) else emptyMap()
    }

    fun payload(key: String, settings: JSONObject): ByteArray =
        if (key == "timeZone") settings.getString(key).toByteArray(Charsets.UTF_8)
        else byteArrayOf(if (settings.getBoolean(key)) 1 else 0)
}

internal class NexgoProfileBackend(private val context: Context) {
    private val snapshot = NexgoRuntimeInspector.inspect(context)
    private val commands = NexgoProfileCommands.forFirmware(
        snapshot.profile.modelKey, snapshot.profile.detectedCommandBase, snapshot.pss.apkSha256,
    ).takeIf { runCatching { SystemServiceHelper.getCMDBASE() }.getOrNull() == snapshot.profile.detectedCommandBase }.orEmpty()

    fun supports(key: String): Boolean = commands.containsKey(key)

    suspend fun initialize(): Boolean = NexgoSystemServiceInitializer.await(context) == SystemServiceHelper.RETURN_SUCC

    fun apply(key: String, settings: JSONObject): () -> Boolean {
        val command = commands.getValue(key)
        val code = SystemServiceHelper.getInstance().executeGeneralMethod(
            command, NexgoProfileCommands.payload(key, settings), ByteArray(0), ByteArray(0),
        )
        if (code != SystemServiceHelper.RETURN_SUCC) throw NexgoProfileCommandException(code)
        // The setter can echo the requested value before the radio actually changes.
        // Always perform a separate query/readback; dispatch success is insufficient.
        return {
            when (key) {
                "automaticTime", "automaticTimeZone", "ethernetEnabled" -> {
                    val field = when (key) {
                        "automaticTime" -> Settings.Global.AUTO_TIME
                        "automaticTimeZone" -> Settings.Global.AUTO_TIME_ZONE
                        else -> "ethernet_on"
                    }
                    Settings.Global.getInt(context.contentResolver, field, -1) == if (settings.getBoolean(key)) 1 else 0
                }
                "timeZone" -> AndroidSystemProperties.get("persist.sys.timezone") == settings.getString(key)
                else -> {
                    val output = byteArrayOf(-127)
                    // SDK order is input, other, output; Binder order is input, output, other.
                    val result = SystemServiceHelper.getInstance().executeGeneralMethod(command, ByteArray(0), ByteArray(0), output)
                    result == SystemServiceHelper.RETURN_SUCC && output[0].toInt() == if (settings.getBoolean(key)) 1 else 0
                }
            }
        }
    }
}

internal class NexgoProfileCommandException(val resultCode: Int) : Exception("Nexgo profile command rejected")
