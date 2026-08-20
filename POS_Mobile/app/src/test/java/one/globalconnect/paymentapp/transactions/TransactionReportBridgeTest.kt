package one.globalconnect.paymentapp.transactions

import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionReportBridgeTest {
    @Test
    fun disabledMethodTurnsTransactionReportingOff() {
        val database = TMSDATA(
            terminal = listOf(TMS_Terminal(tranReportingMethod = "Disabled"))
        )

        val policy = TransactionReportingPolicy.from(database)

        assertEquals(false, policy.isEnabled)
        assertEquals(false, policy.isBatching)
    }

    @Test
    fun disabledMethodIsCaseInsensitiveAndTrimmed() {
        val database = TMSDATA(
            terminal = listOf(TMS_Terminal(tranReportingMethod = "  disabled  "))
        )

        assertEquals(false, TransactionReportingPolicy.from(database).isEnabled)
    }

    @Test
    fun transactionReportUsesAcquirerCurrencyCode() {
        val tmsDatabase = TMSDATA(
            terminal = listOf(
                TMS_Terminal(
                    acquirer = listOf(
                        TMS_Acquirer(
                            acquirer_id = "VENTAS",
                            currencyCode = 340,
                            currencySymbol = "Lps",
                        )
                    )
                )
            )
        )
        val transaction = Transaction(
            transactionId = "33c29fb6-ef14-3f93-8640-146bd922d649",
            acquirerId = "VENTAS",
            totalAmount = "25.00",
            localDateTime = "2026-07-07 11:47:17",
            type = TransactionType.SALE,
            authCode = "123456",
        )

        val report = transaction.toReportJson(TransactionReportEvent.Auto, tmsDatabase)

        assertEquals("340", report.getString("currencyCode"))
        assertEquals("340", report.getJSONObject("rawData").getString("currencyCode"))
    }
}
