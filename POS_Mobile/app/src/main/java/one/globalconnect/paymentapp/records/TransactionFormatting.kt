package one.globalconnect.paymentapp.records

import one.globalconnect.paymentapp.transaction.Transaction
import java.time.LocalDateTime
import java.time.LocalTime

val defaultFormattedTime: String = LocalTime.MIDNIGHT.format(timeFormatter)
val defaultFormattedVerboseDateTime: String = LocalDateTime.MIN.format(verboseDateTimeFormatter)

fun Transaction.applyFormattedTimes(parsedDateTime: LocalDateTime? = null): Transaction {
    val safeDateTime = parsedDateTime ?: runCatching {
        LocalDateTime.parse(localDateTime, dateTimeFormatter)
    }.getOrNull()

    formattedTime = safeDateTime?.toLocalTime()?.format(timeFormatter) ?: defaultFormattedTime
    formattedVerboseDateTime =
        safeDateTime?.format(verboseDateTimeFormatter) ?: defaultFormattedVerboseDateTime

    return this
}
