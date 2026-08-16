package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_EmvContactConfig(
    var config_id: String = "",
    var description: String = "",
    var aid: String = "",
    var applicationVersionNumber: String = "",
    var floorLimit: String = "",
    var partial_selection: Boolean = false,
    var max_percentage_for_selection: String = "",
    var percentage_for_selection: String = "",
    var threshold_for_bias_selection: String = "",
    var onlinePinCap: Int = 1,
    var tacDenial: String = "",
    var tacOnline: String = "",
    var tacDefault: String = ""
) {
    val AID: String get() = aid
    val PartSel: String get() = if (partial_selection) "01" else "00"
    val AppVerNo: String get() = applicationVersionNumber
    val Floor_Limit: Long get() = floorLimit.toLongOrNull() ?: 0L
    val max_percent: String get() = max_percentage_for_selection
    val Percentagesel: String get() = percentage_for_selection
    val Thresholdbiassel: Long get() = threshold_for_bias_selection.toLongOrNull() ?: 0L
    val TAC_Defaul: String get() = tacDefault
    val TAC_Denial: String get() = tacDenial
    val TAC_Online: String get() = tacOnline
    val Default_DDOL: String get() = ""
    val Default_DDOL_Len: String get() = "00"

    companion object {
        fun fromJson(json: JSONObject): TMS_EmvContactConfig {
            return TMS_EmvContactConfig(
                config_id = TMS_Json.readString(json, "config_id"),
                description = TMS_Json.readString(json, "description"),
                aid = TMS_Json.readString(json, "aid"),
                applicationVersionNumber = TMS_Json.readString(json, "applicationVersionNumber"),
                floorLimit = TMS_Json.readString(json, "floorLimit"),
                partial_selection = TMS_Json.readBoolean(json, "partial_selection"),
                max_percentage_for_selection = TMS_Json.readString(json, "max_percentage_for_selection"),
                percentage_for_selection = TMS_Json.readString(json, "percentage_for_selection"),
                threshold_for_bias_selection = TMS_Json.readString(json, "threshold_for_bias_selection"),
                onlinePinCap = TMS_Json.readBinaryFlagDefault(json, "onlinePinCap", 1),
                tacDenial = TMS_Json.readString(json, "tacDenial"),
                tacOnline = TMS_Json.readString(json, "tacOnline"),
                tacDefault = TMS_Json.readString(json, "tacDefault")
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_EmvContactConfig> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_EmvContactConfig>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }
    }
}
