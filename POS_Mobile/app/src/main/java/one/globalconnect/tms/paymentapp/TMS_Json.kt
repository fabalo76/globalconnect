package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject

object TMS_Json {
    fun readString(json: JSONObject, name: String): String {
        if (!json.has(name) || json.isNull(name)) return ""
        return json.optString(name, "")
    }

    fun readStringAny(json: JSONObject, vararg names: String): String {
        for (name in names) {
            val value = readString(json, name)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    fun readInt(json: JSONObject, name: String): Int {
        if (!json.has(name) || json.isNull(name)) return 0
        return json.optInt(name, 0)
    }

    fun readIntDefault(json: JSONObject, name: String, defaultValue: Int): Int {
        if (!json.has(name) || json.isNull(name)) return defaultValue
        return json.optInt(name, defaultValue)
    }

    fun readBinaryFlagDefault(json: JSONObject, name: String, defaultValue: Int): Int {
        if (!json.has(name) || json.isNull(name)) return defaultValue
        return when (val value = json.opt(name)) {
            is Boolean -> if (value) 1 else 0
            is Number -> if (value.toInt() == 0) 0 else 1
            is String -> when (value.trim().lowercase()) {
                "true", "1" -> 1
                "false", "0" -> 0
                else -> defaultValue
            }
            else -> defaultValue
        }
    }

    fun readLong(json: JSONObject, name: String): Long {
        if (!json.has(name) || json.isNull(name)) return 0L
        return when (val value = json.opt(name)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    fun readLongAny(json: JSONObject, vararg names: String): Long {
        for (name in names) {
            val value = readLong(json, name)
            if (value != 0L) return value
        }
        return 0L
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
