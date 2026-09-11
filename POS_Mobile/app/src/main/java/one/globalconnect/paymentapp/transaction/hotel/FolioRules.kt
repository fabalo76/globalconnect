package one.globalconnect.paymentapp.transaction.hotel

import java.util.Locale

enum class HotelCheckInType(val tmsValue: String) {
    ByFolio("01"),
    ByAuthorizationId("02"),
    ByCentralFolio("03");

    val requiresFolio: Boolean
        get() = this != ByAuthorizationId

    companion object {
        fun fromTms(value: String): HotelCheckInType =
            entries.firstOrNull { it.tmsValue == value.trim().padStart(2, '0') } ?: ByFolio
    }
}

enum class FolioInputMode {
    Numeric,
    Alphanumeric;

    companion object {
        fun fromTms(value: String): FolioInputMode = when (value.trim().lowercase(Locale.ROOT)) {
            "alphanumeric", "alpha", "text" -> Alphanumeric
            else -> Numeric
        }
    }
}

object FolioRules {
    const val MAX_LENGTH = 15

    fun sanitize(value: String, inputMode: FolioInputMode): String {
        val allowed = when (inputMode) {
            FolioInputMode.Numeric -> value.filter { it in '0'..'9' }
            FolioInputMode.Alphanumeric -> value.filter {
                it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z'
            }
        }
        return allowed.uppercase(Locale.ROOT).take(MAX_LENGTH)
    }

    fun normalize(value: String, inputMode: FolioInputMode): String {
        val sanitized = sanitize(value, inputMode)
        if (sanitized.isEmpty()) return sanitized
        val minimumLength = when (inputMode) {
            FolioInputMode.Numeric -> 6
            FolioInputMode.Alphanumeric -> 10
        }
        return sanitized.padStart(minimumLength, '0')
    }
}
