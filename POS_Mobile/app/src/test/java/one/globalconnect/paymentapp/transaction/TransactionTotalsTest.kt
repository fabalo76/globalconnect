package one.globalconnect.paymentapp.transaction

import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Issuer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TransactionTotalsTest {

    @Test
    fun calcTotals_allAcquirers_aggregatesTerminalAcquirerAndIssuerTotals() {
        val acquirer = createAcquirer("ACQ1", "Acquirer 1", 840, "$")
        val issuer = createIssuer("ACQ1", "ISS1", "Issuer 1")
        val database = createDatabase(listOf(acquirer), listOf(issuer))

        val transactions = listOf(
            Transaction(
                transactionId = "sale-1",
                totalAmount = "100.00",
                baseAmount = "100.00",
                tax1Amount = "10.00",
                tax1DiscountAmount = "2.00",
                tax2Amount = "5.00",
                tipAmount = "3.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 00:00:00",
                type = TransactionType.SALE,
                returnStatus = ReturnStatus.None,
            ),
            Transaction(
                transactionId = "sale-void",
                totalAmount = "50.00",
                baseAmount = "50.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 01:00:00",
                type = TransactionType.SALE,
                returnStatus = ReturnStatus.Voided,
            ),
            Transaction(
                transactionId = "cash-1",
                totalAmount = "20.00",
                baseAmount = "20.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 02:00:00",
                type = TransactionType.CASH,
            ),
            Transaction(
                transactionId = "cash-void",
                totalAmount = "10.00",
                baseAmount = "10.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 02:30:00",
                type = TransactionType.CASH,
                returnStatus = ReturnStatus.Voided,
            ),
            Transaction(
                transactionId = "extras-sale",
                totalAmount = "30.00",
                baseAmount = "30.00",
                tax1Amount = "3.00",
                tax1DiscountAmount = "0.50",
                tax2Amount = "0.30",
                tipAmount = "0.70",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 03:00:00",
                type = TransactionType.EXTRAS_SALE,
            ),
            Transaction(
                transactionId = "extras-void",
                totalAmount = "5.00",
                baseAmount = "5.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 03:15:00",
                type = TransactionType.EXTRAS_SALE,
                returnStatus = ReturnStatus.Voided,
            ),
            Transaction(
                transactionId = "quota-sale",
                totalAmount = "40.00",
                baseAmount = "40.00",
                tax1Amount = "4.00",
                tax1DiscountAmount = "0.40",
                tax2Amount = "0.20",
                tipAmount = "1.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 04:00:00",
                type = TransactionType.QUOTA_SALE,
            ),
            Transaction(
                transactionId = "loyalty-sale",
                totalAmount = "25.00",
                baseAmount = "25.00",
                tax1Amount = "2.50",
                tax1DiscountAmount = "0.25",
                tax2Amount = "0.10",
                tipAmount = "0.50",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 05:00:00",
                type = TransactionType.LOYALTY_SALE,
            ),
            Transaction(
                transactionId = "refund",
                totalAmount = "15.00",
                baseAmount = "15.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 06:00:00",
                type = TransactionType.REFUND,
            ),
            Transaction(
                transactionId = "refund-void",
                totalAmount = "7.00",
                baseAmount = "7.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 06:15:00",
                type = TransactionType.REFUND,
                returnStatus = ReturnStatus.Voided,
            ),
            Transaction(
                transactionId = "payment",
                totalAmount = "12.00",
                baseAmount = "12.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 07:00:00",
                type = TransactionType.PAYMENT,
            ),
            Transaction(
                transactionId = "payment-void",
                totalAmount = "4.00",
                baseAmount = "4.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-01-01 07:15:00",
                type = TransactionType.PAYMENT,
                returnStatus = ReturnStatus.Voided,
            ),
        )

        val totals = calcTotals(transactions, database)

        totals.terminal.let { terminal ->
            assertEquals("0", terminal.id)
            assertEquals("Terminal", terminal.name)
            assertEquals("000", terminal.currencyCode)
            assertEquals("", terminal.currencySymbol)

            assertEquals(1, terminal.sales.count)
            assertAmountEquals("100.00", terminal.sales.amount)
            assertEquals(1, terminal.voidedSales.count)
            assertAmountEquals("-50.00", terminal.voidedSales.amount)

            assertEquals(1, terminal.cash.count)
            assertAmountEquals("20.00", terminal.cash.amount)
            assertEquals(1, terminal.voidedCash.count)
            assertAmountEquals("-10.00", terminal.voidedCash.amount)

            assertEquals(1, terminal.extrasSale.count)
            assertAmountEquals("30.00", terminal.extrasSale.amount)
            assertEquals(1, terminal.voidedExtrasSale.count)
            assertAmountEquals("-5.00", terminal.voidedExtrasSale.amount)

            assertEquals(1, terminal.quotasSale.count)
            assertAmountEquals("40.00", terminal.quotasSale.amount)

            assertEquals(1, terminal.loyaltySale.count)
            assertAmountEquals("25.00", terminal.loyaltySale.amount)

            assertEquals(4, terminal.tax1.count)
            assertAmountEquals("19.50", terminal.tax1.amount)
            assertEquals(4, terminal.tax1Discount.count)
            assertAmountEquals("3.15", terminal.tax1Discount.amount)
            assertEquals(4, terminal.tax2.count)
            assertAmountEquals("5.60", terminal.tax2.amount)
            assertEquals(4, terminal.tip.count)
            assertAmountEquals("5.20", terminal.tip.amount)

            assertEquals(1, terminal.refund.count)
            assertAmountEquals("-15.00", terminal.refund.amount)
            assertEquals(1, terminal.voidedRefund.count)
            assertAmountEquals("7.00", terminal.voidedRefund.amount)

            assertEquals(1, terminal.payment.count)
            assertAmountEquals("-12.00", terminal.payment.amount)
            assertEquals(1, terminal.voidedPayment.count)
            assertAmountEquals("4.00", terminal.voidedPayment.amount)

            assertEquals(2, terminal.credits.count)
            assertAmountEquals("-27.00", terminal.credits.amount)

            assertEquals(5, terminal.debits.count)
            assertAmountEquals("215.00", terminal.debits.amount)
        }

        assertEquals(1, totals.acquirers.size)
        val acquirerTotals = totals.acquirers.first()
        assertEquals("ACQ1", acquirerTotals.acquirer.id)
        assertEquals("Acquirer 1", acquirerTotals.acquirer.name)
        assertEquals("840", acquirerTotals.acquirer.currencyCode)
        assertEquals("$", acquirerTotals.acquirer.currencySymbol)
        assertEquals(1, acquirerTotals.issuers.size)

        val issuerTotals = acquirerTotals.issuers.first()
        assertEquals("ISS1", issuerTotals.id)
        assertEquals("Issuer 1", issuerTotals.name)
        assertAmountEquals("100.00", issuerTotals.sales.amount)
        assertEquals(1, issuerTotals.sales.count)
        assertAmountEquals("215.00", issuerTotals.debits.amount)
        assertAmountEquals("-27.00", issuerTotals.credits.amount)
    }

    @Test
    fun calcTotals_singleAcquirer_aggregatesProvidedTransactions() {
        val acquirer1 = createAcquirer("ACQ1", "Acquirer 1", 840, "$")
        val acquirer2 = createAcquirer("ACQ2", "Acquirer 2", 840, "$")
        val database = createDatabase(listOf(acquirer1, acquirer2))

        val transactions = listOf(
            Transaction(
                transactionId = "sale-acq1",
                totalAmount = "100.00",
                baseAmount = "100.00",
                acquirerId = "ACQ1",
                issuerId = "ISS1",
                cardRangeName = "Issuer 1",
                localDateTime = "2024-02-01 00:00:00",
                type = TransactionType.SALE,
            ),
            Transaction(
                transactionId = "sale-acq2",
                totalAmount = "45.00",
                baseAmount = "45.00",
                acquirerId = "ACQ2",
                issuerId = "ISS2",
                cardRangeName = "Issuer 2",
                localDateTime = "2024-02-01 01:00:00",
                type = TransactionType.SALE,
            ),
            Transaction(
                transactionId = "payment-acq2",
                totalAmount = "20.00",
                baseAmount = "20.00",
                acquirerId = "ACQ2",
                issuerId = "ISS2",
                cardRangeName = "Issuer 2",
                localDateTime = "2024-02-01 02:00:00",
                type = TransactionType.PAYMENT,
            ),
        )

        val acquirer2Transactions = transactions.filter { it.acquirerId == "ACQ2" }

        val totals = calcTotals(acquirer2Transactions, database)

        totals.terminal.let { terminal ->
            assertEquals(1, terminal.sales.count)
            assertAmountEquals("45.00", terminal.sales.amount)
            assertEquals(1, terminal.payment.count)
            assertAmountEquals("-20.00", terminal.payment.amount)
            assertEquals(1, terminal.credits.count)
            assertAmountEquals("-20.00", terminal.credits.amount)
            assertEquals(1, terminal.debits.count)
            assertAmountEquals("45.00", terminal.debits.amount)
        }

        assertEquals(1, totals.acquirers.size)
        val acquirerTotals = totals.acquirers.first()
        assertEquals("ACQ2", acquirerTotals.acquirer.id)
        assertEquals("Acquirer 2", acquirerTotals.acquirer.name)
        assertTrue(acquirerTotals.issuers.isNotEmpty())
    }

    @Test
    fun calcTotals_createsFallbackIssuerWhenIssuerMissing() {
        val acquirer = createAcquirer("ACQ1", "Acquirer 1", 840, "$")
        val database = createDatabase(listOf(acquirer))

        val transactions = listOf(
            Transaction(
                transactionId = "sale-unknown-issuer",
                totalAmount = "30.00",
                baseAmount = "30.00",
                acquirerId = "ACQ1",
                issuerId = "",
                cardRangeName = "Fallback Issuer",
                localDateTime = "2024-03-01 00:00:00",
                type = TransactionType.SALE,
            ),
        )

        val totals = calcTotals(transactions, database)

        val acquirerTotals = totals.acquirers.first()
        val fallbackIssuer = acquirerTotals.issuers.firstOrNull { it.name == "Fallback Issuer" }
        assertNotNull(fallbackIssuer)
        fallbackIssuer?.let {
            assertEquals(UNKNOWN_ISSUER_ID, it.id)
            assertAmountEquals("30.00", it.sales.amount)
            assertEquals(1, it.sales.count)
        }
    }

    private fun assertAmountEquals(expected: String, actual: BigDecimal) {
        assertEquals(BigDecimal(expected), actual)
    }

    private fun createAcquirer(
        id: String,
        name: String,
        currencyCode: Long,
        currencySymbol: String,
    ): TMS_Acquirer = TMS_Acquirer(
        acquirer_id = id,
        acquirerName = name,
        terminalId = "TERM$id",
        merchantId = "MER$id",
        currencyCode = currencyCode,
        currencySymbol = currencySymbol,
        enableSale = true,
        enableCash = true,
        enablePayment = true,
        enableLoyalty = true,
    )

    private fun createIssuer(
        acquirerId: String,
        id: String,
        name: String,
    ): TMS_Issuer = TMS_Issuer(
        acquirerId = acquirerId,
        issuer_id = id,
        issuerName = name,
    )

    private fun createDatabase(
        acquirers: List<TMS_Acquirer>,
        issuers: List<TMS_Issuer> = emptyList(),
    ): TMSDATA {
        val acquirersWithIssuers = acquirers.map { acquirer ->
            acquirer.copy(
                issuer = issuers.filter { it.AcqID == acquirer.AcqID }
                    .map { it.copy(acquirerId = "") },
            )
        }
        return TMSDATA(
            applicationId = TMSDATA.APPLICATION_NAME,
            schemaVersion = 1,
            terminal = listOf(TMS_Terminal(acquirer = acquirersWithIssuers)),
        )
    }
}
