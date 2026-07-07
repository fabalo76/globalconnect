package com.uic.tms.payment_app

import org.json.JSONArray
import org.json.JSONObject

object TMS_Json {
    fun readString(json: JSONObject, name: String): String {
        if (!json.has(name) || json.isNull(name)) return ""
        return json.optString(name, "")
    }

    fun readInt(json: JSONObject, name: String): Int {
        if (!json.has(name) || json.isNull(name)) return 0
        return json.optInt(name, 0)
    }

    fun readLong(json: JSONObject, name: String): Long {
        if (!json.has(name) || json.isNull(name)) return 0L
        return json.optLong(name, 0L)
    }

    fun readDouble(json: JSONObject, name: String): Double {
        if (!json.has(name) || json.isNull(name)) return 0.0
        return json.optDouble(name, 0.0)
    }

    fun readBoolean(json: JSONObject, name: String): Boolean {
        if (!json.has(name) || json.isNull(name)) return false
        return json.optBoolean(name, false)
    }

    fun readBooleanDefault(json: JSONObject, name: String, defaultValue: Boolean): Boolean {
        if (!json.has(name) || json.isNull(name)) return defaultValue
        return json.optBoolean(name, defaultValue)
    }

    fun readStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val items = mutableListOf<String>()
        for (index in 0 until array.length()) {
            if (!array.isNull(index)) items.add(array.optString(index, ""))
        }
        return items
    }
}
