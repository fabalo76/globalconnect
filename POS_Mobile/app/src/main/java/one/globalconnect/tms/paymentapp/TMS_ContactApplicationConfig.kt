package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_ContactApplicationConfig(
    var emvContactConfigRef: String = ""
) {


    companion object {
        fun fromJson(json: JSONObject): TMS_ContactApplicationConfig {
            return TMS_ContactApplicationConfig(
                emvContactConfigRef = TMS_Json.readString(json, "emvContactConfigRef")
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_ContactApplicationConfig> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_ContactApplicationConfig>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }
    }
}
