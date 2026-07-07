package com.uic.uicpaymentapp.transaction

import android.content.Context
import android.content.res.Resources
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.printer.PrintFontSize
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime


data class PrintableTotalsReport(
    val title: String,
    val subtitle: String?,
    val sections: List<PrintableTotalsSection>,
    val generatedAt: LocalDateTime,
)

data class PrintableTotalsSection(
    val title: String,
    val lines: List<PrintableTotalsLine>,
)

data class PrintableTotalsLine(
    val label: String,
    val count: Int,
    val amount: BigDecimal,
    val currencySymbol: String,
    val indentLevel: Int = 0,
    val printFontSize: PrintFontSize = PrintFontSize.SMALL,
    val isBold: Boolean = false,
)

fun createPrintableTotalsReport(
    context: Context,
    result: TotalsResult,
    selectedAcquirerId: String?,
): PrintableTotalsReport {
    val resources = context.resources
    val generatedAt = LocalDateTime.now()
    val sections = mutableListOf<PrintableTotalsSection>()

    val subtitle = if (selectedAcquirerId == null) {
        result.acquirers.forEach { acquirerTotals ->
            sections += buildAcquirerSections(resources, acquirerTotals)
        }
        sections += buildTerminalSections(resources, result.terminal)
        resources.getString(R.string.totals_subtitle_all_acquirers)
    } else {
        val acquirerTotals = result.acquirers.firstOrNull { it.acquirer.id == selectedAcquirerId }
            ?: throw IllegalArgumentException("Acquirer $selectedAcquirerId not found in totals result")
        sections += buildAcquirerSections(resources, acquirerTotals)
        resources.getString(
            R.string.totals_subtitle_single_acquirer,
            acquirerTotals.acquirer.name,
        )
    }

    return PrintableTotalsReport(
        title = resources.getString(R.string.totals_report),
        subtitle = subtitle,
        sections = sections,
        generatedAt = generatedAt,
    )
}

private fun buildAcquirerSections(
    resources: Resources,
    totals: AcquirerTotals,
): List<PrintableTotalsSection> {
    val sections = mutableListOf<PrintableTotalsSection>()
    sections += PrintableTotalsSection(
        title = resources.getString(R.string.totals_section_acquirer, totals.acquirer.name),
        lines = buildSummaryLines(resources, totals.acquirer),
    )
    buildVoidedLines(resources, totals.acquirer).takeIf { it.isNotEmpty() }?.let { voidLines ->
        sections += PrintableTotalsSection(
            title = resources.getString(R.string.report_totals_section_voids),
            lines = voidLines,
        )
    }
    val issuers =totals.issuers.filter { hasActivity(it) }
    if (issuers.size > 1)
    {
        issuers.forEach { issuer ->
            sections += PrintableTotalsSection(
                title = resources.getString(R.string.totals_section_issuer, issuer.name),
                lines = buildSummaryLines(resources, issuer),
            )
            buildVoidedLines(resources, issuer).takeIf { it.isNotEmpty() }?.let { voidLines ->
                sections += PrintableTotalsSection(
                    title = resources.getString(R.string.report_totals_section_voids),
                    lines = voidLines,
                )
            }
        }
    }
    return sections
}

private fun buildTerminalSections(
    resources: Resources,
    record: TotalsRecord,
): List<PrintableTotalsSection> {
    val sections = mutableListOf<PrintableTotalsSection>()
    sections += PrintableTotalsSection(
        title = resources.getString(R.string.totals_section_terminal),
        lines = buildSummaryLines(resources, record),
    )
    buildVoidedLines(resources, record).takeIf { it.isNotEmpty() }?.let { voidLines ->
        sections += PrintableTotalsSection(
            title = resources.getString(R.string.report_totals_section_voids),
            lines = voidLines,
        )
    }
    return sections
}

private fun buildSummaryLines(
    resources: Resources,
    record: TotalsRecord,
): List<PrintableTotalsLine> {
    val currencySymbol = resolveCurrencySymbol(record)
    val lines = mutableListOf<PrintableTotalsLine>()
    lines += record.payment.toPrintableLine(resources.getString(R.string.report_totals_payments), currencySymbol)
    lines += record.refund.toPrintableLine(resources.getString(R.string.report_totals_refunds), currencySymbol)
    lines += record.cash.toPrintableLine(resources.getString(R.string.report_totals_cash), currencySymbol)
    lines += record.sales.toPrintableLine(resources.getString(R.string.report_totals_sales), currencySymbol)
    lines += record.sales.toPrintableLine(resources.getString(R.string.report_totals_sales_normal), currencySymbol, indentLevel = 1)
    lines += record.tax1.toPrintableLine(resources.getString(R.string.report_totals_sales_tax), currencySymbol, indentLevel = 1)
    lines += record.tax1Discount.toPrintableLine(resources.getString(R.string.report_totals_sales_discount), currencySymbol, indentLevel = 1)
    val netSalesAmount = record.sales.amount
        .add(record.tax1.amount)
        .add(record.tax1Discount.amount)
        .setScale(2, RoundingMode.HALF_UP)
    lines += PrintableTotalsLine(
        label = resources.getString(R.string.report_totals_sales_net),
        count = record.sales.count,
        amount = netSalesAmount,
        currencySymbol = currencySymbol,
        indentLevel = 1,
        isBold = true,
    )
    val netTotalCount = record.sales.count + record.cash.count + record.payment.count + record.refund.count
    val netTotalAmount = record.sales.amount
        .add(record.cash.amount)
        .add(record.payment.amount)
        .add(record.refund.amount)
        .setScale(2, RoundingMode.HALF_UP)
    lines += PrintableTotalsLine(
        label = resources.getString(R.string.report_totals_net_total),
        count = netTotalCount,
        amount = netTotalAmount,
        currencySymbol = currencySymbol,
        printFontSize = PrintFontSize.MEDIUM,
        isBold = true,
    )
    return lines
}

private fun buildVoidedLines(
    resources: Resources,
    record: TotalsRecord,
): List<PrintableTotalsLine> {
    val currencySymbol = resolveCurrencySymbol(record)
    val voidLines = mutableListOf<PrintableTotalsLine>()
    fun addLine(labelRes: Int, metric: TotalsMetric) {
        if (metric.count != 0 || metric.amount.compareTo(BigDecimal.ZERO) != 0) {
            voidLines += metric.toPrintableLine(resources.getString(labelRes), currencySymbol)
        }
    }
    addLine(R.string.report_totals_void_sales, record.voidedSales)
    addLine(R.string.report_totals_void_cash, record.voidedCash)
    addLine(R.string.report_totals_void_refunds, record.voidedRefund)
    addLine(R.string.report_totals_void_payments, record.voidedPayment)
    return voidLines
}

private fun TotalsMetric.toPrintableLine(
    label: String,
    currencySymbol: String,
    indentLevel: Int = 0,
): PrintableTotalsLine {
    return PrintableTotalsLine(
        label = label,
        count = count,
        amount = amount.setScale(2, RoundingMode.HALF_UP),
        currencySymbol = currencySymbol,
        indentLevel = indentLevel,
    )
}

private fun resolveCurrencySymbol(record: TotalsRecord): String {
    return record.currencySymbol.takeIf { it.isNotBlank() }
        ?: record.currencyCode.takeIf { it.isNotBlank() }
        ?: ""
}

private fun hasActivity(record: TotalsRecord): Boolean {
    val metrics = listOf(
        record.sales,
        record.voidedSales,
        record.cash,
        record.voidedCash,
        record.extrasSale,
        record.voidedExtrasSale,
        record.quotasSale,
        record.voidedQuotasSale,
        record.loyaltySale,
        record.voidedLoyaltySale,
        record.debitSales,
        record.debitRefunds,
        record.authorizations,
        record.authorizationRefunds,
        record.hypercomReserved1,
        record.hypercomReserved2,
        record.cashback,
        record.tax1,
        record.tax1Discount,
        record.tax2,
        record.tip,
        record.refund,
        record.voidedRefund,
        record.payment,
        record.voidedPayment,
        record.credits,
        record.debits,
    )
    return metrics.any { it.count != 0 || it.amount.compareTo(BigDecimal.ZERO) != 0 }
}

