package com.uic.uicpaymentapp.uicpos.pos.host

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Utility methods required to format ISO8583 field values. */
object IsoFieldFormatter {

    private val dateFormatter = DateTimeFormatter.ofPattern("MMdd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    private val transmissionFormatter = DateTimeFormatter.ofPattern("MMddHHmmss")

    fun amount(value: String): String {
        val normalised = value.replace(",", "").trim()
        val numeric = if (normalised.isBlank()) BigDecimal.ZERO else normalised.toBigDecimalOrNull()
        val scaled = (numeric ?: BigDecimal.ZERO).movePointRight(2).setScale(0, RoundingMode.HALF_UP)
        return scaled.toPlainString().padStart(12, '0')
    }

    fun numeric(value: Long, length: Int): String =
        value.toString().padStart(length, '0').takeLast(length)

    fun numeric(value: String, length: Int): String =
        value.filter { it.isDigit() }.padStart(length, '0').takeLast(length)

    fun alphaNumeric(value: String, length: Int): String =
        value.trim().padEnd(length, ' ').take(length)

    fun transmissionDateTime(timestamp: LocalDateTime): String =
        timestamp.format(transmissionFormatter)

    fun localDate(timestamp: LocalDateTime): String = timestamp.format(dateFormatter)

    fun localTime(timestamp: LocalDateTime): String = timestamp.format(timeFormatter)
}
