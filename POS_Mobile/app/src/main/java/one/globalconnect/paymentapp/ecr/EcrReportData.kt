package one.globalconnect.paymentapp.ecr

import one.globalconnect.paymentapp.transaction.*
import one.globalconnect.tms.paymentapp.TMSDATA
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Wire-only formatting shared by report delivery and its protocol tests. */
internal object EcrReportData {
    fun validate(request: EcrMessage) {
        require(request.command in setOf("P1", "P2", "P3", "P4", "P5") && request.indicator == 0 &&
            request.response == "00" && !request.more)
        require(request.fields.keys.all { it in if (request.command == "P1") setOf("80", "65", "P1") else if (request.command in setOf("P4", "P5")) setOf("80", "P1", "AI") else setOf("80", "P1") })
        require(!request.fields["80"].isNullOrBlank() && request.fields.getValue("80").length <= 64)
        require(request.fields["P1"] in listOf(null, "0", "1"))
        request.fields["AI"]?.let { require(it.isNotBlank() && it.length <= 64) }
        request.fields["65"]?.let { require(it.length in 1..12 && it.all(Char::isDigit)) }
    }

    fun text(value: String) = value.map { if (it.code in 32..126 && it != '|' && it != '^') it else ' ' }.joinToString("").trim()
    fun cents(value: String): String = (value.toBigDecimalOrNull() ?: BigDecimal.ZERO).movePointRight(2).toBigIntegerExact().toString()
    fun entry(transaction: Transaction) = when (transaction.cardEntryMethod) {
        "MANUAL" -> "01"; "SWIPE" -> "02"; "EMV" -> "05"; "EMV_CONTACTLESS" -> "07"; "FALLBACK_SWIPE" -> "80"; else -> "00"
    }
    fun type(transaction: Transaction) = if (transaction.returnStatus == ReturnStatus.Voided) "42" else when (transaction.type) {
        TransactionType.SALE, TransactionType.MANUALSALE, TransactionType.TOKENSALE,
        TransactionType.FORCESALE, TransactionType.MANUALFORCESALE, TransactionType.MOTO -> "20"
        TransactionType.REFUND, TransactionType.MANUALREFUND -> "26"
        TransactionType.CASH -> "E7"; TransactionType.PAYMENT -> "38"
        TransactionType.QUOTA_SALE -> "32"; TransactionType.EXTRAS_SALE -> "35"
        TransactionType.LOYALTY_BALANCE -> "33"; TransactionType.LOYALTY_SALE -> "31"; TransactionType.CHECKIN -> "CI"; TransactionType.CHECKOUT -> "CO"
        TransactionType.VOID, TransactionType.VOIDCHECKIN, TransactionType.PARTIALVOID -> "42"
        TransactionType.AUTHONLY, TransactionType.MANUALAUTH -> "10"
        else -> "00"
    }
    fun timestamp(transaction: Transaction) = LocalDateTime.parse(transaction.localDateTime,
        one.globalconnect.paymentapp.records.dateTimeFormatter)

    fun receiptFields(transaction: Transaction, database: TMSDATA): Map<String, String> {
        val acquirer = database.Acquirer.firstOrNull { it.acquirer_id == transaction.acquirerId }
        val date = timestamp(transaction)
        return mapOf(
            "01" to transaction.authCode, "03" to date.format(DateTimeFormatter.ofPattern("yyMMdd")),
            "04" to date.format(DateTimeFormatter.ofPattern("HHmmss")), "05" to entry(transaction),
            "16" to acquirer?.terminalId.orEmpty(), "17" to acquirer?.merchantId.orEmpty(),
            "30" to transaction.masked_cardNumber, "40" to cents(transaction.totalAmount),
            "41" to cents(transaction.tipAmount), "42" to cents(transaction.cashbackAmount), "44" to cents(transaction.tax1Amount),
            "45" to cents(transaction.tax2Amount), "46" to cents(transaction.tax1DiscountAmount),
            "53" to (acquirer?.currencyCode?.toString()?.padStart(3, '0').orEmpty() + "|" + acquirer?.currencySymbol.orEmpty()),
            "65" to transaction.invoiceId, "66" to transaction.folioNumber, "70" to type(transaction),
            "71" to if (transaction.returnStatus == ReturnStatus.Voided) type(transaction.copy(returnStatus = ReturnStatus.None)) else "00",
            "78" to transaction.stan, "79" to transaction.retrievalReferenceNumber,
            "82" to transaction.cardholderName, "90" to transaction.AID, "91" to transaction.applicationLabel,
            "92" to transaction.TVR, "93" to transaction.TSI, "94" to transaction.emvCryptoInformation,
            "95" to transaction.emvCryptogram, "DC" to acquirer?.acquirerName.orEmpty())
            .mapValues { (_, value) -> value.map { if (it.code in 32..126) it else ' ' }.joinToString("").take(999) }
    }

    private fun metrics(record: TotalsRecord): String {
        // calcTotals represents refunds/payments as signed negative credits.
        val grand = TotalsMetric(record.debits.count + record.credits.count, record.debits.amount + record.credits.amount)
        return linkedMapOf("DT" to record.debits, "CT" to record.credits, "GT" to grand,
            "SA" to record.sales, "T1" to record.tax1, "T2" to record.tax2, "TD" to record.tax1Discount,
            "TI" to record.tip, "CH" to record.cash, "PA" to record.payment, "RF" to record.refund,
            "LO" to record.loyaltySale, "ES" to record.extrasSale, "QS" to record.quotasSale,
            "VS" to record.voidedSales, "VC" to record.voidedCash, "VL" to record.voidedLoyaltySale,
            "VP" to record.voidedPayment, "VR" to record.voidedRefund, "VQ" to record.voidedQuotasSale,
            "VE" to record.voidedExtrasSale).map { (code, metric) ->
            require(metric.count in 0..999) { "Report count exceeds legacy protocol capacity" }
            val amount = cents(metric.amount.toPlainString())
            val formatted = if (amount.startsWith('-')) "-" + amount.drop(1).padStart(11, '0') else amount.padStart(12, '0')
            require(formatted.length == 12) { "Report amount exceeds legacy protocol capacity" }
            code + metric.count.toString().padStart(3, '0') + formatted
        }.joinToString("|")
    }

    fun reportFields(transactions: List<Transaction>, database: TMSDATA, audit: Boolean, batchNumbers: Map<String, String> = emptyMap()): List<Map<String, String>> {
        val eligible = transactions.filter { it.type != TransactionType.CHECKIN }
        val acquirers = database.Acquirer.associateBy { it.acquirer_id }
        val fields = mutableListOf<Map<String, String>>()
        fun chunks(tag: String, value: String) { value.chunked(900).forEach { fields.add(mapOf(tag to it)) } }
        val currencies = (database.Acquirer.map { it.currencyCode } + eligible.map { acquirers[it.acquirerId]?.currencyCode }).distinct()
        currencies.forEach { currency ->
            val rows = eligible.filter { acquirers[it.acquirerId]?.currencyCode == currency }
            val symbol = database.Acquirer.firstOrNull { it.currencyCode == currency }?.currencySymbol.orEmpty()
            chunks("TT", text(symbol) + "|" + metrics(calcTotals(rows, database).terminal) + "^")
        }
        calcTotals(eligible, database).acquirers.forEach { totals ->
            val record = totals.acquirer
            val acquirer = acquirers[record.id]
            fields.add(mapOf("DC" to text(record.name), "16" to text(acquirer?.terminalId.orEmpty()),
                "17" to text(acquirer?.merchantId.orEmpty())))
            chunks("AT", text(record.name) + "|" + text(record.currencySymbol) + "|" + text(batchNumbers[record.id].orEmpty()) + "|" + metrics(record) + "^")
        }
        if (audit) transactions.sortedBy { it.id }.forEach { transaction ->
            chunks("TD", listOf(timestamp(transaction).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                type(transaction), if (transaction.returnStatus == ReturnStatus.Voided) type(transaction.copy(returnStatus = ReturnStatus.None)) else "00", entry(transaction), transaction.masked_cardNumber,
                transaction.invoiceId, transaction.stan, transaction.retrievalReferenceNumber,
                transaction.authCode, transaction.posTransactionId, cents(transaction.totalAmount),
                cents(transaction.tax1Amount), cents(transaction.tax1DiscountAmount), cents(transaction.tax2Amount),
                cents(transaction.tipAmount), transaction.folioNumber,
                acquirers[transaction.acquirerId]?.acquirerName.orEmpty()).joinToString("|") { text(it) } + "^")
        }
        fields.addAll(installmentFields(eligible, database, batchNumbers))
        return fields
    }

    fun installmentFields(transactions: List<Transaction>, database: TMSDATA,
        batchNumbers: Map<String, String> = emptyMap()): List<Map<String, String>> {
        val acquirers = database.Acquirer.associateBy { it.acquirer_id }
        val rows = transactions.filter { it.returnStatus == ReturnStatus.None && it.type in setOf(TransactionType.QUOTA_SALE, TransactionType.EXTRAS_SALE) }
        fun metrics(rows: List<Transaction>): String = rows.groupBy {
            val details = one.globalconnect.paymentapp.transaction.installments.InstallmentContracts.forTransaction(it.type)
                ?.savedDetails(it.paymentPlan, it.paymentPlanQueryResponse)
            val count = details?.count ?: it.paymentPlan.take(2).toIntOrNull() ?: 0
            (if (it.type == TransactionType.QUOTA_SALE) "Q" else "E") + count.toString().padStart(2, '0')
        }.toSortedMap().map { (key, group) ->
            require(group.size <= 999)
            val amount = cents(group.sumOf { it.totalAmount.toBigDecimal() }.toPlainString()).padStart(12, '0')
            require(amount.length == 12)
            key + group.size.toString().padStart(3, '0') + amount
        }.joinToString("|")
        val fields = mutableListOf<Map<String, String>>()
        fun add(tag: String, row: String) { (row + "^").chunked(900).forEach { fields.add(mapOf(tag to it)) } }
        rows.groupBy { acquirers[it.acquirerId]?.currencyCode }.forEach { (_, group) ->
            add("TQ", text(acquirers[group.first().acquirerId]?.currencySymbol.orEmpty()) + "|" + metrics(group))
        }
        rows.groupBy { it.acquirerId }.forEach { (id, group) ->
            val acquirer = acquirers[id]
            add("AQ", text(acquirer?.acquirerName.orEmpty()) + "|" + text(acquirer?.currencySymbol.orEmpty()) + "|" +
                text(batchNumbers[id].orEmpty()) + "|" + metrics(group))
        }
        return fields
    }

    fun frames(request: EcrMessage, fields: List<Map<String, String>>, printed: Boolean,
        code: String = "00", message: String = "Completed"): List<EcrMessage> {
        val parts = fields + listOf(mapOf("00" to code, "02" to message, "P2" to if (printed) "1" else "0"))
        if (request.command == "P1") return listOf(EcrMessage("P1", code, 1,
            parts.fold(emptyMap<String, String>()) { result, part -> result + part } +
                mapOf("80" to request.fields.getValue("80"))))
        return parts.mapIndexed { index, part -> EcrMessage(request.command, code, 1,
            part + mapOf("80" to request.fields.getValue("80"), "S1" to index.toString()), more = index < parts.lastIndex) }
    }
}
