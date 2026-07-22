package one.globalconnect.paymentapp.ui.preview

import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.records.AcquirerSummary
import one.globalconnect.paymentapp.records.EndOfDayUiState
import one.globalconnect.paymentapp.records.TotalsReportUiState
import one.globalconnect.paymentapp.records.UiMessage
import one.globalconnect.paymentapp.settlement.SettlementAcquirerOption
import one.globalconnect.paymentapp.settlement.SettlementConstants
import one.globalconnect.paymentapp.ui.components.AcquirerSelectionOption
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals
import java.math.BigDecimal

/**
 * Centralised sample data for Compose previews used across the records module.
 */
object RecordsPreviewData {

    val acquirerSummaries = listOf(
        AcquirerSummary(
            id = "VENTAS",
            name = "Ventas",
            merchantId = "1234567890",
            terminalId = "12345678",
        ),
        AcquirerSummary(
            id = "CLAVE",
            name = "Clave",
            merchantId = "0987654321",
            terminalId = "87654321",
        ),
        AcquirerSummary(
            id = "ALPHA",
            name = "Alpha Payments",
            merchantId = "1122334455",
            terminalId = "55667788",
        ),
    )

    val acquirerOptions: List<AcquirerSelectionOption> = buildList {
        add(
            AcquirerSelectionOption(
                id = "",
                title = "All Acquirers",
            ),
        )
        acquirerSummaries.forEachIndexed { index, summary ->
            add(
                AcquirerSelectionOption(
                    id = summary.id,
                    title = summary.name,
                ),
            )
        }
    }

    val totalsReportUiState = TotalsReportUiState(
        acquirers = acquirerSummaries,
        isPrinting = false,
        message = UiMessage(R.string.totals_message_success),
    )

    val totalsPrintingUiState = totalsReportUiState.copy(
        isPrinting = true,
        message = null,
    )

    val summaryUiState = ReconciliationTotals().apply {
        creditSales.count = 2
        creditSales.amount = BigDecimal("150.00")
        refunds.count = 1
        refunds.amount = BigDecimal("25.00")
        payments.count = 1
        payments.amount = BigDecimal("10.00")
    }

    private val sampleSettlementTotals = ReconciliationTotals().apply {
        creditSales.count = 2
        creditSales.amount = BigDecimal("150.00")
        refunds.count = 1
        refunds.amount = BigDecimal("25.00")
        payments.count = 1
        payments.amount = BigDecimal("10.00")
    }

    val settlementUiState = EndOfDayUiState(
        acquirerOptions = listOf(
            SettlementAcquirerOption(
                id = SettlementConstants.ALL_ACQUIRERS_ID,
                name = "",
                merchantId = null,
                terminalId = null,
                currencySymbol = "\$",
                isAll = true,
            ),
            SettlementAcquirerOption(
                id = "VENTAS",
                name = "Ventas",
                merchantId = "1234567890",
                terminalId = "12345678",
                currencySymbol = "\$",
            ),
        ),
        selectedAcquirerId = "VENTAS",
        totalsByAcquirer = mapOf(
            SettlementConstants.ALL_ACQUIRERS_ID to sampleSettlementTotals,
            "VENTAS" to sampleSettlementTotals,
        ),
        totals = sampleSettlementTotals,
        selectedCurrencySymbol = "\$",
        hasTransactions = true,
    )
}
