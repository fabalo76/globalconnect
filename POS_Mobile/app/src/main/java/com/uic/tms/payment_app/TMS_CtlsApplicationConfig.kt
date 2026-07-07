package com.uic.tms.payment_app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_CtlsApplicationConfig(
    var emvCtlsConfigRef: String = ""
) {


    companion object {
        fun fromJson(json: JSONObject): TMS_CtlsApplicationConfig {
            return TMS_CtlsApplicationConfig(
                emvCtlsConfigRef = TMS_Json.readString(json, "emvCtlsConfigRef")
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_CtlsApplicationConfig> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_CtlsApplicationConfig>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }
    }
}
