package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_Issuer(
    var issuer_id: String = "",
    var issuerName: String = "",
    var mod10Check: Boolean = false,
    var mod11Check: Boolean = false,
    var cardRangeRefs: List<String> = emptyList(),
    var acquirerId: String = ""
) {
    val IssuID: String get() = issuer_id
    @get:JvmName("getLegacyIssuerName")
    val IssuerName: String get() = issuerName
    val AcqID: String get() = acquirerId
    val CardRange1: String get() = cardRangeRefs.getOrNull(0).orEmpty()
    val CardRange2: String get() = cardRangeRefs.getOrNull(1).orEmpty()
    val CardRange3: String get() = cardRangeRefs.getOrNull(2).orEmpty()
    val CardRange4: String get() = cardRangeRefs.getOrNull(3).orEmpty()
    val CardRange5: String get() = cardRangeRefs.getOrNull(4).orEmpty()
    val CardRange6: String get() = cardRangeRefs.getOrNull(5).orEmpty()
    val CardRange7: String get() = cardRangeRefs.getOrNull(6).orEmpty()
    val CardRange8: String get() = cardRangeRefs.getOrNull(7).orEmpty()
    val CardRange9: String get() = cardRangeRefs.getOrNull(8).orEmpty()
    val CardRange10: String get() = cardRangeRefs.getOrNull(9).orEmpty()
    val CardRange11: String get() = cardRangeRefs.getOrNull(10).orEmpty()
    val CardRange12: String get() = cardRangeRefs.getOrNull(11).orEmpty()
    val CardRange13: String get() = cardRangeRefs.getOrNull(12).orEmpty()
    val CardRange14: String get() = cardRangeRefs.getOrNull(13).orEmpty()
    val CardRange15: String get() = cardRangeRefs.getOrNull(14).orEmpty()
    val CardRange16: String get() = cardRangeRefs.getOrNull(15).orEmpty()
    val CardRange17: String get() = cardRangeRefs.getOrNull(16).orEmpty()
    val CardRange18: String get() = cardRangeRefs.getOrNull(17).orEmpty()
    val CardRange19: String get() = cardRangeRefs.getOrNull(18).orEmpty()
    val CardRange20: String get() = cardRangeRefs.getOrNull(19).orEmpty()
    val Refund: Boolean get() = true
    val Void: Boolean get() = true
    val Payment: Boolean get() = true
    val chkInOut: Boolean get() = true

    companion object {
        fun fromJson(json: JSONObject): TMS_Issuer {
            return TMS_Issuer(
                issuer_id = TMS_Json.readString(json, "issuer_id"),
                issuerName = TMS_Json.readString(json, "issuerName"),
                mod10Check = TMS_Json.readBoolean(json, "mod10Check"),
                mod11Check = TMS_Json.readBoolean(json, "mod11Check"),
                cardRangeRefs = TMS_Json.readStringList(json.optJSONArray("cardRangeRefs")),
                acquirerId = TMS_Json.readString(json, "acquirerId")
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_Issuer> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_Issuer>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }
    }
}
