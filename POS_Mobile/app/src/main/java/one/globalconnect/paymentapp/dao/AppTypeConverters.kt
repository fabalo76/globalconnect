package one.globalconnect.paymentapp.dao

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import one.globalconnect.paymentapp.settlement.storage.SettlementSnapshot
import one.globalconnect.paymentapp.transaction.ReversalReason
import org.json.JSONObject

class AppTypeConverters {

    private val gson = Gson()
    private val settlementSnapshotType = object : TypeToken<SettlementSnapshot>() {}.type

    @TypeConverter
    fun mapToJson(value: Map<Int, String>?): String? {
        if (value == null) return null
        val json = JSONObject()
        value.toSortedMap().forEach { (key, fieldValue) ->
            json.put(key.toString(), fieldValue)
        }
        return json.toString()
    }

    @TypeConverter
    fun jsonToMap(value: String?): Map<Int, String> {
        if (value.isNullOrBlank()) return emptyMap()
        val json = JSONObject(value)
        val iterator = json.keys()
        val result = mutableMapOf<Int, String>()
        while (iterator.hasNext()) {
            val key = iterator.next()
            result[key.toInt()] = json.optString(key)
        }
        return result.toSortedMap()
    }

    @TypeConverter
    fun reasonToString(reason: ReversalReason?): String? = reason?.name

    @TypeConverter
    fun stringToReason(value: String?): ReversalReason {
        if (value.isNullOrBlank()) return ReversalReason.NO_RESPONSE
        return runCatching { ReversalReason.valueOf(value) }.getOrDefault(ReversalReason.UNKNOWN)
    }

    @TypeConverter
    fun snapshotToJson(snapshot: SettlementSnapshot?): String? {
        if (snapshot == null) return null
        return gson.toJson(snapshot, settlementSnapshotType)
    }

    @TypeConverter
    fun jsonToSnapshot(value: String?): SettlementSnapshot? {
        if (value.isNullOrBlank()) return null
        return gson.fromJson(value, settlementSnapshotType)
    }
}
