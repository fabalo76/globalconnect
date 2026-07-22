package one.globalconnect.paymentapp.transaction

import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.paymentapp.R

data class CurrencyInfo(
    val currencyCode: Long,
    val countryCode: Long,
    val symbol: String,
    val nameResId: Int?,
)

object CurrencyTable {

    private val nameResIds: Map<Long, Int> = mapOf(
        170L to R.string.currency_170,
        188L to R.string.currency_188,
        320L to R.string.currency_320,
        340L to R.string.currency_340,
        484L to R.string.currency_484,
        558L to R.string.currency_558,
        590L to R.string.currency_590,
        604L to R.string.currency_604,
        840L to R.string.currency_840,
    )

    fun build(acquirers: List<TMS_Acquirer>): List<CurrencyInfo> =
        acquirers
            .distinctBy { it.CurrencyCode }
            .map { acquirer ->
                CurrencyInfo(
                    currencyCode = acquirer.CurrencyCode,
                    countryCode = acquirer.CountryCode,
                    symbol = acquirer.Currency,
                    nameResId = nameResIds[acquirer.CurrencyCode],
                )
            }

    fun formatEmvCode(code: Long): String = code.toString().padStart(4, '0')
}
