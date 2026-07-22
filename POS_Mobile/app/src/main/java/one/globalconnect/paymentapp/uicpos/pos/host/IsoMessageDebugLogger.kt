package one.globalconnect.paymentapp.uicpos.pos.host

import android.util.Log
import com.uic.pos.iso8583.IsoMessage
import one.globalconnect.paymentapp.BuildConfig
import one.globalconnect.paymentapp.util.LogSanitizer

/**
 * Helper methods to surface ISO8583 message details through logcat.
 */
object IsoMessageDebugLogger {

    /**
     * Logs the map of ISO8583 fields that are about to be placed in a message.
     */
    fun logConfiguredFields(tag: String, label: String, fieldValues: Map<Int, String>) {
        if (!BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) return

        if (fieldValues.isEmpty()) {
            Log.d(tag, "$label - no ISO8583 fields configured")
            return
        }

        fieldValues.toSortedMap().forEach { (field, value) ->
            val sanitized = LogSanitizer.sanitizeIsoField(field, value)
            Log.d(tag, String.format("%s field %03d = %s", label, field, sanitized))
        }
    }

    /**
     * Logs the populated fields, header and bitmap contained in [message].
     */
    fun logMessage(tag: String, label: String, message: IsoMessage, encodedPayload: ByteArray? = null) {
        if (!BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) return

        val header = message.header ?: "(none)"
        val messageType = message.messageType ?: "(unknown)"
        val bitmap = runCatching { message.bitmap }
            .onFailure { error ->
                Log.w(tag, "$label - unable to compute bitmap", error)
            }
            .getOrNull()
            ?: "(unavailable)"

        Log.d(tag, "$label header=$header mti=$messageType bitmap=$bitmap")
        logPopulatedFields(tag, label, message)

        val payload = encodedPayload ?: runCatching { message.toByteArray() }.getOrNull()
        if (payload != null) {
            val payloadText = LogSanitizer.sanitizeHexPayload(
                clearPan = message.getFieldValueOrNull(2),
                track1 = message.getFieldValueOrNull(45),
                track2 = message.getFieldValueOrNull(35),
                track3 = message.getFieldValueOrNull(36) ?: message.getFieldValueOrNull(57),
            ) { payload.toHexString() }
            Log.d(tag, "$label payload (${payload.size} bytes)=$payloadText")
        } else {
            Log.d(tag, "$label payload unavailable")
        }
    }

    /**
     * Logs each populated ISO8583 data element.
     */
    fun logPopulatedFields(tag: String, label: String, message: IsoMessage) {
        if (!BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) return

        var loggedAny = false
        for (field in 2..128) {
            if (message.hasField(field)) {
                val value = message.getFieldValue(field)
                val sanitized = LogSanitizer.sanitizeIsoField(field, value)
                Log.d(tag, String.format("%s field %03d = %s", label, field, sanitized))
                loggedAny = true
            }
        }

        if (!loggedAny) {
            Log.d(tag, "$label message contains no data fields")
        }
    }
}

private fun IsoMessage.getFieldValueOrNull(field: Int): String? {
    return if (hasField(field)) getFieldValue(field) else null
}

/**
 * Converts the byte array into an hexadecimal string. When [groupSize] is
 * greater than zero, a space is inserted every [groupSize] bytes to improve
 * readability.
 */
fun ByteArray.toHexString(groupSize: Int = 0): String {
    if (isEmpty()) return ""

    val builder = StringBuilder(size * 2 + size / (if (groupSize > 0) groupSize else size + 1))
    for (index in indices) {
        if (groupSize > 0 && index > 0 && index % groupSize == 0) {
            builder.append(' ')
        }
        builder.append(String.format("%02X", this[index]))
    }
    return builder.toString()
}

