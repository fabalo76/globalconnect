package one.globalconnect.paymentapp.cardreader.nexgo

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nexgo.oaf.apiv3.emv.AidEntity
import com.nexgo.oaf.apiv3.emv.AidEntryModeEnum
import com.nexgo.oaf.apiv3.emv.CapkEntity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

enum class CapkEnvironment { PRODUCTION, TESTING, PRODUCTION_AND_TESTING }

/**
 * Helper used for loading the CAPK and AID configuration files that ship with
 * the Nexgo SDK samples.  The JSON payloads are identical to those consumed by
 * the legacy .NET implementation so we simply mirror the behaviour here.
 */
internal object EmvUtils {
    private const val TAG = "NexgoEmvUtils"
    private val gson = Gson()

    fun getCapkList(context: Context, env: CapkEnvironment = CapkEnvironment.PRODUCTION): List<CapkEntity>? {
        val lists = mutableListOf<List<CapkEntity>>()
        if (env == CapkEnvironment.PRODUCTION || env == CapkEnvironment.PRODUCTION_AND_TESTING) {
            parseEntityList(context, "emv_capk_production.json") { element ->
                gson.fromJson(element, CapkEntity::class.java)
            }?.let { lists += it }
        }
        if (env == CapkEnvironment.TESTING || env == CapkEnvironment.PRODUCTION_AND_TESTING) {
            parseEntityList(context, "emv_capk_test.json") { element ->
                gson.fromJson(element, CapkEntity::class.java)
            }?.let { lists += it }
        }
        return if (lists.isEmpty()) null else lists.flatten()
    }

    fun loadAidList(context: Context, onlinePinSupport: Boolean): List<AidEntity>? {
        val fileName = if (onlinePinSupport) {
            "emv_aid.json"
        } else {
            // Some deployments rely on a reduced AID list that disables online
            // PIN.  When that asset is not provided we simply fall back to the
            // default file to keep the transaction flow operational.
            if (assetExists(context, "emv_aid_no_online_pin.json")) {
                "emv_aid_no_online_pin.json"
            } else {
                Log.w(TAG, "Missing emv_aid_no_online_pin.json asset, using full list")
                "emv_aid.json"
            }
        }
        return parseEntityList(context, fileName) { element ->
            val aid = gson.fromJson(element, AidEntity::class.java)
            applyEntryMode(element, aid)
            aid
        }
    }

    private inline fun <reified T> parseEntityList(
        context: Context,
        assetName: String,
        crossinline mapper: (JsonElement) -> T,
    ): List<T>? {
        val jsonContent = readAsset(context, assetName) ?: return null
        val jsonArray: JsonArray = try {
            JsonParser.parseString(jsonContent).asJsonArray
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to parse $assetName", error)
            return null
        }

        val result = ArrayList<T>(jsonArray.size())
        for (element in jsonArray) {
            result += mapper(element)
        }
        return result
    }

    private fun applyEntryMode(element: JsonElement, aid: AidEntity) {
        if (!element.isJsonObject) {
            return
        }
        val jsonObject = element.asJsonObject
        if (!jsonObject.has("emvEntryMode")) {
            return
        }
        val modeValue = jsonObject.get("emvEntryMode").asInt
        val modeEnum = AidEntryModeEnum.values().getOrNull(modeValue)
        if (modeEnum != null) {
            aid.aidEntryModeEnum = modeEnum
            Log.d(TAG, "AID ${aid.aid} entryMode=${modeEnum.name}")
        }
    }

    private fun readAsset(context: Context, assetName: String): String? = try {
        context.assets.open(assetName).use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }
    } catch (error: Throwable) {
        Log.e(TAG, "Unable to read asset $assetName", error)
        null
    }

    private fun assetExists(context: Context, assetName: String): Boolean = try {
        context.assets.list("")?.contains(assetName) == true
    } catch (_: Throwable) {
        false
    }
}
