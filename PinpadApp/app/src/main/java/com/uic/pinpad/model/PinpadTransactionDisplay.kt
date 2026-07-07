package com.uic.pinpad.model

import java.util.Currency
import java.util.Locale

data class PinpadTransactionDisplay(
    val transactionTypeCode: String,
    val currencyCode: String,
    val amountMinor: Long,
    val currencySymbol: String? = null,
) {
    val amountText: String
        get() = "${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"

    val displayCurrency: String
        get() = currencySymbol?.takeIf { it.isNotBlank() } ?: standardCurrencySymbol(currencyCode)

    val emvAmount: String
        get() = amountMinor.toString().padStart(12, '0')

    val transactionTypeByte: Byte
        get() = transactionTypeCode.toInt(16).toByte()

    companion object {
        private const val FS = '\u001C'
        private const val SUB = '\u001A'
        private const val MAX_SYMBOL_CHARS = 8

        fun parseZaPayload(value: String): PinpadTransactionDisplay? {
            val fields = value.split(FS, SUB)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (fields.size >= 3) {
                val symbol = fields.getOrNull(3)?.take(MAX_SYMBOL_CHARS)
                return fromParts(
                    type = fields[0],
                    amount = fields[1],
                    currency = fields[2],
                    symbol = symbol,
                )
            }
            return parseCompact(value)
        }

        fun parseCompact(value: String): PinpadTransactionDisplay? {
            val normalized = value.trim().replace(" ", "").uppercase(Locale.US)
            if (normalized.length !in 7..18) return null
            val type = normalized.take(2)
            val currency = normalized.drop(2).take(4)
            val amount = normalized.drop(6)
            if (!type.all { it.digitToIntOrNull(16) != null }) return null
            if (currency.length != 4 || !currency.all(Char::isDigit)) return null
            if (currency == "0000") return null
            if (amount.isBlank() || amount.length > 12 || !amount.all(Char::isDigit)) return null
            val amountMinor = amount.toLongOrNull() ?: return null
            if (amountMinor > 999_999_999_999L) return null
            return PinpadTransactionDisplay(
                transactionTypeCode = type,
                currencyCode = currency,
                amountMinor = amountMinor,
            )
        }

        fun fromEmv(
            transactionType: Byte,
            currencyCode: String,
            amountAuthorized: String,
        ): PinpadTransactionDisplay? {
            val amount = amountAuthorized.takeIf { it.length <= 12 && it.all(Char::isDigit) } ?: return null
            val currency = currencyCode.takeLast(4).padStart(4, '0').takeIf { it.all(Char::isDigit) } ?: return null
            return PinpadTransactionDisplay(
                transactionTypeCode = "%02X".format(transactionType.toInt() and 0xFF),
                currencyCode = currency,
                amountMinor = amount.toLongOrNull() ?: return null,
            )
        }

        private fun fromParts(
            type: String,
            amount: String,
            currency: String,
            symbol: String?,
        ): PinpadTransactionDisplay? {
            val normalizedType = type.trim().uppercase(Locale.US)
            val normalizedAmount = amount.trim()
            val normalizedCurrency = currency.trim().padStart(4, '0')
            if (normalizedType.length != 2 || !normalizedType.all { it.digitToIntOrNull(16) != null }) return null
            if (normalizedAmount.isBlank() || normalizedAmount.length > 12 || !normalizedAmount.all(Char::isDigit)) return null
            if (normalizedCurrency.length != 4 || !normalizedCurrency.all(Char::isDigit)) return null
            if (normalizedCurrency == "0000") return null
            val amountMinor = normalizedAmount.toLongOrNull() ?: return null
            if (amountMinor > 999_999_999_999L) return null
            return PinpadTransactionDisplay(
                transactionTypeCode = normalizedType,
                currencyCode = normalizedCurrency,
                amountMinor = amountMinor,
                currencySymbol = symbol?.takeIf { it.isNotBlank() },
            )
        }

        private fun standardCurrencySymbol(currencyCode: String): String {
            val numericCode = currencyCode.toIntOrNull() ?: return currencyCode
            return runCatching {
                Currency.getAvailableCurrencies()
                    .firstOrNull { it.numericCode == numericCode }
                    ?.getSymbol(Locale.getDefault())
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: currencyCode
        }
    }
}
