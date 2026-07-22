package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_CardRanges(
    var config_id: String = "",
    var description: String = "",
    var binLow: String = "",
    var binHigh: String = "",
    var cardLength: Double = 0.0,
    var exclusive: Boolean = false,
    var processedAidRefs: List<String> = emptyList()
) {
    val CardRangeID: String get() = config_id
    val RangeName: String get() = description
    val CardRangeLow: Long get() = binLow.toLongOrNull() ?: 0L
    val CardRangeHigh: Long get() = binHigh.toLongOrNull() ?: 0L
    val Length: Long get() = cardLength.toLong()
    val ExclusiveBIN: Boolean get() = exclusive
    val AID1: String get() = processedAidRefs.getOrNull(0).orEmpty()
    val AID2: String get() = processedAidRefs.getOrNull(1).orEmpty()
    val AID3: String get() = processedAidRefs.getOrNull(2).orEmpty()
    val AID4: String get() = processedAidRefs.getOrNull(3).orEmpty()

    companion object {
        fun fromJson(json: JSONObject): TMS_CardRanges {
            return TMS_CardRanges(
                config_id = TMS_Json.readString(json, "config_id"),
                description = TMS_Json.readString(json, "description"),
                binLow = TMS_Json.readString(json, "binLow"),
                binHigh = TMS_Json.readString(json, "binHigh"),
                cardLength = TMS_Json.readDouble(json, "cardLength"),
                exclusive = TMS_Json.readBoolean(json, "exclusive"),
                processedAidRefs = TMS_Json.readStringList(json.optJSONArray("processedAidRefs"))
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_CardRanges> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_CardRanges>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }
    }
}
