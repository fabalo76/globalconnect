package one.globalconnect.paymentapp.printer

import one.globalconnect.paymentapp.transaction.installments.installmentDetails
import one.globalconnect.paymentapp.transaction.installments.InstallmentDetails
import one.globalconnect.paymentapp.transaction.resolvedCardBrand
import one.globalconnect.paymentapp.transaction.shortReportLabel

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.printer.AlignEnum
import com.nexgo.oaf.apiv3.device.printer.GrayLevelEnum
import com.nexgo.oaf.apiv3.device.printer.LineOptionEntity
import com.nexgo.oaf.apiv3.device.printer.OnPrintListener
import com.nexgo.oaf.apiv3.device.printer.Printer
import com.nexgo.oaf.apiv3.device.pinpad.PinPadTypeEnum
import com.nexgo.oaf.apiv3.device.pinpad.WorkKeyTypeEnum
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.cardreader.nexgo.NexgoSdkResult
import one.globalconnect.paymentapp.admin.AdminTicketData
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.records.SummaryMetric
import one.globalconnect.paymentapp.records.buildSummaryReport
import one.globalconnect.paymentapp.records.dateTimeFormatter
import one.globalconnect.paymentapp.records.dateTimeFormatterForUsers
import one.globalconnect.paymentapp.records.toSignedAmount
import one.globalconnect.paymentapp.settlement.storage.SettlementSnapshot
import one.globalconnect.paymentapp.transaction.BatchSummary
import one.globalconnect.paymentapp.transaction.PrintableTotalsLine
import one.globalconnect.paymentapp.transaction.PrintableTotalsReport
import one.globalconnect.paymentapp.transaction.ReturnStatus
import one.globalconnect.paymentapp.transaction.ReceiptPinVerification
import one.globalconnect.paymentapp.transaction.ReversalReceiptData
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.transaction.TotalsMetric
import one.globalconnect.paymentapp.transaction.Tax1DiscountCalculator
import one.globalconnect.paymentapp.transaction.resolveReceiptCvmPresentation
import one.globalconnect.paymentapp.transaction.resolveReceiptReferenceValues
import one.globalconnect.paymentapp.transaction.partialApprovalReceipt
import one.globalconnect.paymentapp.transaction.toStringForUsers
import one.globalconnect.paymentapp.transaction.toTransactionString
import one.globalconnect.paymentapp.uicpos.pos.host.InvoiceNumberProvider
import one.globalconnect.paymentapp.uicpos.pos.host.StanProvider
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.paymentapp.uicpos.pos.model.SignatureMode
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotalKind
import one.globalconnect.paymentapp.utils.FormatterUtils
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch

object NexGoPaymentPrinter : PaymentPrinter {
    const val TAG = "Printer"
    const val MERCHANT = "MERCHANT"
    private const val MINFONTSIZE = 18
    private const val TINYFONTSIZE = 20
    private const val SMALLFONTSIZE = 24
    private const val MEDIUMFONTSIZE = 28
    private const val LARGEFONTSIZE = 30
    private const val MASSIVEFONTSIZE = 34
    private const val SETTLEMENT_TRANSACTION_FORMAT_LEFT = "%-3s %-6s %-6s%1s"
    private const val SETTLEMENT_TRANSACTION_FORMAT_RIGHT = "%-6s %12s"
    private val dottedSpacer = "-".repeat(22)
    private val receiptDateFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern(
            GlobalConnectPaymentApplication.instance.resources.getString(R.string.receipt_date_pattern),
        )
    private val reversalTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val settlementDateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    private val settlementTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    // Constants defining initial character limits per font type and size
    private const val CHARSPERLINE_MINFONTSIZE = 42
    private const val CHARSPERLINE_TINYFONTSIZE = 38
    private const val CHARSPERLINE_SMALLFONTSIZE = 32
    private const val CHARSPERLINE_MEDIUMFONTSIZE = 27
    private const val CHARSPERLINE_LARGEFONTSIZE = 25
    private const val CHARSPERLINE_MASSIVEFONTSIZE = 22
    private const val MIN_WRAPPED_LAST_LINE = 8
    private val PRINT_GRAY_LEVEL = GrayLevelEnum.LEVEL_3

    /**
     * Function to test printer capabilities.
     * - Prints test lines for different typefaces and font sizes.
     * - Tests text alignment.
     * - Tests justified text printing.
     */
    override fun printerTest(context: Context) {
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        val grayLevel = PRINT_GRAY_LEVEL
        val fonts = listOf(
            Typeface.DEFAULT to listOf(PrintFontSize.MIN, PrintFontSize.TINY, PrintFontSize.SMALL, PrintFontSize.MEDIUM),
            Typeface.DEFAULT_BOLD to listOf(PrintFontSize.MIN, PrintFontSize.TINY, PrintFontSize.SMALL, PrintFontSize.MEDIUM),
            Typeface.MONOSPACE to listOf(PrintFontSize.MIN, PrintFontSize.TINY, PrintFontSize.SMALL, PrintFontSize.MEDIUM),
            Typeface.SERIF to listOf(PrintFontSize.MIN, PrintFontSize.TINY, PrintFontSize.SMALL, PrintFontSize.MEDIUM),
            Typeface.SANS_SERIF to listOf(PrintFontSize.MIN, PrintFontSize.TINY, PrintFontSize.SMALL, PrintFontSize.MEDIUM),
        )
        printer.initPrinter()
        printer.setGray(grayLevel)
        printer.appendPrnStr("Printer Test Start", 24, AlignEnum.CENTER, true)
        printer.appendPrnStr("Font DEFAULT (24)", 24, AlignEnum.CENTER, true)
        printer.appendPrnStr("Gray Level: $grayLevel", 24, AlignEnum.CENTER, true)
        //here call printer.startPrint and wait to finish using the listener
        printer.startPrintAndAwaitCompletion()

        // Test printing different typefaces and font sizes
        for ((typeface, sizes) in fonts) {
            //For each typeface we need to send the print job and wait it to finish
            printer.initPrinter()
            printer.setGray(grayLevel)
            printer.setTypeface(typeface)
            for (size in sizes) {
                val charLimit = getLineWidth(size)
                val testLine = (1..charLimit).joinToString("") { (it % 10).toString() }
                val printerSize = size.toPrinterSize()
                printer.appendPrnStr("Font: ${typefaceToString(typeface)}, Size: $size:$printerSize", printerSize, AlignEnum.LEFT, false)
                printer.appendPrnStr(testLine, printerSize, AlignEnum.LEFT, false)
                printer.appendPrnStr("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRTSUVWXYZ", printerSize, AlignEnum.LEFT, false )
                printer.appendPrnStr("JUSTIFIED", "RIGHT PART", printerSize, false )
                //printer.appendPrnStr(testLine, printerSize, AlignEnum.LEFT, true)
            }
            //here call printer.startPrint and wait to finish using the listener
            printer.startPrintAndAwaitCompletion()
        }

        // last print job has to be finished before reaching this point.
        printer.initPrinter()
        printer.setGray(grayLevel)
        //printer.setTypeface(Typeface.DEFAULT)
        // Test alignment
        printer.appendPrnStr("Alignment Test", 30, AlignEnum.CENTER, true)
        printer.appendPrnStr("LEFT", 24, AlignEnum.LEFT, false)
        printer.appendPrnStr("CENTER", 24, AlignEnum.CENTER, false)
        printer.appendPrnStr("RIGHT", 24, AlignEnum.RIGHT, false)

        // Test justified printing
        printer.appendPrnStr("(15)Justify Left", "Justify Right", 24, LineOptionEntity().apply { marginLeft = 15 })

        printer.appendPrnStr("Printer Test End", 30, AlignEnum.CENTER, true)
        //here call printer.startPrint and wait to finish using the listener
        printer.startPrintAndAwaitCompletion()

    }

    private fun Printer.startPrintAndAwaitCompletion(isContinuable: Boolean = true) {
        val latch = CountDownLatch(1)
        var result: Int? = null

        val listener = OnPrintListener { sdkResult ->
            result = sdkResult
            latch.countDown()
        }

        startPrint(isContinuable, listener)

        try {
            latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Printer test interrupted while waiting for completion", e)
        }

        when (result) {
            SdkResult.Success -> Log.d(TAG, "Printer test completed successfully.")
            null -> Log.e(TAG, "Printer test failed: result is null")
            else -> Log.e(TAG, "Printer test failed: $result")
        }
    }
    private fun getLineWidth(printFontSize: PrintFontSize): Int {
        return when (printFontSize) {
            PrintFontSize.MIN -> CHARSPERLINE_MINFONTSIZE
            PrintFontSize.TINY -> CHARSPERLINE_TINYFONTSIZE
            PrintFontSize.SMALL -> CHARSPERLINE_SMALLFONTSIZE
            PrintFontSize.MEDIUM -> CHARSPERLINE_MEDIUMFONTSIZE
            PrintFontSize.LARGE -> CHARSPERLINE_LARGEFONTSIZE
            PrintFontSize.MASSIVE -> CHARSPERLINE_MASSIVEFONTSIZE
        }
    }

    private fun PrintFontSize.toPrinterSize(): Int {
        return when (this) {
            PrintFontSize.MIN -> MINFONTSIZE
            PrintFontSize.TINY -> TINYFONTSIZE
            PrintFontSize.SMALL -> SMALLFONTSIZE
            PrintFontSize.MEDIUM -> MEDIUMFONTSIZE
            PrintFontSize.LARGE -> LARGEFONTSIZE
            PrintFontSize.MASSIVE -> MASSIVEFONTSIZE
        }
    }

    private fun Printer.printCentered(text: String, printFontSize: PrintFontSize, isBold: Boolean = false) {
        appendPrnStr(text, printFontSize.toPrinterSize(), AlignEnum.CENTER, isBold)
    }

    private fun Printer.printLine(text: String, printFontSize: PrintFontSize, isBold: Boolean = false) {
        appendPrnStr(text, printFontSize.toPrinterSize(), AlignEnum.LEFT, isBold)
    }
    private fun Printer.printLine(left: String, right: String, printFontSize: PrintFontSize, isBold: Boolean = false) {
        appendPrnStr(left, right, printFontSize.toPrinterSize(), isBold)
    }

    /**
     * Print [text] with [centerChar] used to split left and right portions that
     * are then justified to the line width for [printFontSize]. If [centerChar] is
     * not present the text is printed as-is.
     */
    private fun Printer.printJustified(
        text: String,
        centerChar: Char = '|',
        printFontSize: PrintFontSize = PrintFontSize.SMALL,
        isBold: Boolean = false,
    ) {
        val width = getLineWidth(printFontSize)
        val parts = text.split(centerChar, limit = 2)
        if (parts.size == 2) {
            val left = parts[0]
            val right = parts[1]
            printLine(left, right, printFontSize, isBold)
        } else {
            printLine(text, printFontSize, isBold)
            text
        }
    }


    private fun typefaceToString(typeface: Typeface): String {
        return when (typeface) {
            Typeface.DEFAULT -> "Default"
            Typeface.DEFAULT_BOLD -> "Default Bold"
            Typeface.MONOSPACE -> "Monospace"
            Typeface.SERIF -> "Serif"
            Typeface.SANS_SERIF -> "Sans-Serif"
            else -> "Unknown"
        }
    }



    override fun printReport(
        context: Context,
        transactions: List<Transaction>,
        printTransactions: Boolean,
        profile: Profile,
        tmsDatabase: TMSDATA,
        onPrintResult: ((Boolean) -> Unit)?,
    ) {
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        if (onPrintResult != null && printer.status != SdkResult.Success) {
            onPrintResult(false)
            return
        }
        printer.initPrinter()
        val state = printer.status
        Log.d(TAG, "printer state = $state")
        printer.setGray(PRINT_GRAY_LEVEL)

        val terminal = tmsDatabase.Terminal[0]
        val acquirer = tmsDatabase.Acquirer[0]

        printHeader(context, terminal.MerchantTitle1, terminal.MerchantTitle2, terminal.MerchantTitle3, acquirer.AcqLine1, printerObject = printer, printLogo = true)


        val resources = context.resources
        val summaryData = buildSummaryReport(transactions, tmsDatabase)

        val title = if (printTransactions) {
            resources.getString(R.string.audit_report_title)
        } else {
            resources.getString(R.string.summary_report_title)
        }
        printer.appendPrnStr(title, LARGEFONTSIZE, AlignEnum.CENTER, true)

        val acquirerLabel = when {
            summaryData.acquirerNames.size == 1 -> summaryData.acquirerNames.first()
            summaryData.acquirerNames.isNotEmpty() -> resources.getString(R.string.report_all_acquirers)
            else -> null
        }
        acquirerLabel?.let { printer.appendPrnStr(it, SMALLFONTSIZE, AlignEnum.CENTER, false) }

        terminal.TermID.takeIf { it.isNotBlank() }?.let { term ->
            printer.appendPrnStr(
                resources.getString(R.string.report_terminal_label, term),
                SMALLFONTSIZE,
                AlignEnum.CENTER,
                false,
            )
        }

        val merchantId = when {
            summaryData.acquirerIds.size == 1 -> {
                val acquirerId = summaryData.acquirerIds.first()
                tmsDatabase.Acquirer.firstOrNull { it.AcqID == acquirerId }?.MerchID?.takeIf { it.isNotBlank() }
            }
            tmsDatabase.Acquirer.size == 1 -> tmsDatabase.Acquirer.first().MerchID.takeIf { it.isNotBlank() }
            else -> null
        }
        merchantId?.let { merch ->
            printer.appendPrnStr(
                resources.getString(R.string.report_merchant_label, merch),
                SMALLFONTSIZE,
                AlignEnum.CENTER,
                false,
            )
        }

        printer.appendPrnStr(
            resources.getString(R.string.report_current_batch),
            SMALLFONTSIZE,
            AlignEnum.CENTER,
            false,
        )

        printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)

        if (printTransactions) {
            val (headerLeft, headerRight) = formatTransactionHeader(resources)
            printer.printLine(
                headerLeft, headerRight,
                PrintFontSize.TINY,
                isBold = true,
            )
            printer.printLine("-".repeat(getLineWidth(PrintFontSize.SMALL)), PrintFontSize.SMALL)

            if (transactions.isEmpty()) {
                printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
                printer.appendPrnStr(
                    resources.getString(R.string.report_no_data),
                    SMALLFONTSIZE,
                    AlignEnum.CENTER,
                    false,
                )
            } else {
                transactions.forEach { transaction ->
                    val (left, right) = formatTransactionLine(transaction)
                    printer.printLine(
                        left, right,
                        PrintFontSize.TINY,
                    )
                    printInstallmentDetails(printer, transaction.installmentDetails())
                }
            }

            printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)
            printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
            printer.appendPrnStr(
                resources.getString(R.string.summary_report_title),
                MEDIUMFONTSIZE,
                AlignEnum.CENTER,
                true,
            )
        }

        val currencySymbol = summaryData.currencySymbol
        printer.appendPrnStr(
            resources.getString(R.string.report_totals_payments),
            formatSummaryValue(summaryData.totals.payments, currencySymbol),
            SMALLFONTSIZE,
            false,
        )
        printer.appendPrnStr(
            resources.getString(R.string.report_totals_refunds),
            formatSummaryValue(summaryData.totals.refunds, currencySymbol),
            SMALLFONTSIZE,
            false,
        )
        printer.appendPrnStr(
            resources.getString(R.string.report_totals_cash),
            formatSummaryValue(summaryData.totals.cash, currencySymbol),
            SMALLFONTSIZE,
            false,
        )
        printer.appendPrnStr(
            resources.getString(R.string.report_totals_sales),
            formatSummaryValue(summaryData.totals.sales, currencySymbol),
            SMALLFONTSIZE,
            false,
        )
        printer.appendPrnStr(
            "  ${resources.getString(R.string.report_totals_sales_normal)}",
            formatSummaryValue(summaryData.totals.sales, currencySymbol),
            SMALLFONTSIZE,
            false,
        )
        printer.appendPrnStr(
            "  ${resources.getString(R.string.report_totals_sales_tax)}",
            formatSummaryValue(summaryData.totals.tax1, currencySymbol),
            SMALLFONTSIZE,
            false,
        )
        printer.appendPrnStr(
            "  ${resources.getString(R.string.report_totals_sales_discount)}",
            formatSummaryValue(summaryData.totals.tax1Discount, currencySymbol),
            SMALLFONTSIZE,
            false,
        )
        printer.appendPrnStr(
            "  ${resources.getString(R.string.report_totals_sales_net)}",
            formatSummaryValue(summaryData.totals.netSales, currencySymbol),
            SMALLFONTSIZE,
            false,
        )
        printer.appendPrnStr(
            resources.getString(R.string.report_totals_net_total),
            formatSummaryValue(summaryData.totals.netTotal, currencySymbol),
            SMALLFONTSIZE,
            false,
        )

        val voidMetrics = summaryData.voids
        if (voidMetrics.sales.hasActivity || voidMetrics.cash.hasActivity || voidMetrics.refunds.hasActivity || voidMetrics.payments.hasActivity) {
            printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)
            printer.appendPrnStr(
                resources.getString(R.string.report_totals_section_voids),
                MEDIUMFONTSIZE,
                AlignEnum.LEFT,
                true,
            )
            printer.appendPrnStr(
                resources.getString(R.string.report_totals_void_sales),
                formatSummaryValue(voidMetrics.sales, currencySymbol),
                SMALLFONTSIZE,
                false,
            )
            printer.appendPrnStr(
                resources.getString(R.string.report_totals_void_cash),
                formatSummaryValue(voidMetrics.cash, currencySymbol),
                SMALLFONTSIZE,
                false,
            )
            printer.appendPrnStr(
                resources.getString(R.string.report_totals_void_refunds),
                formatSummaryValue(voidMetrics.refunds, currencySymbol),
                SMALLFONTSIZE,
                false,
            )
            printer.appendPrnStr(
                resources.getString(R.string.report_totals_void_payments),
                formatSummaryValue(voidMetrics.payments, currencySymbol),
                SMALLFONTSIZE,
                false,
            )
        }

        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(
            resources.getString(R.string.report_generated_on),
            SMALLFONTSIZE,
            AlignEnum.LEFT,
            false,
        )
        printer.appendPrnStr(
            LocalDateTime.now().format(dateTimeFormatterForUsers),
            SMALLFONTSIZE,
            AlignEnum.LEFT,
            false,
        )
        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(
            resources.getString(R.string.end_of_report),
            LARGEFONTSIZE,
            AlignEnum.CENTER,
            false,
        )
        printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)

        val listener = OnPrintListener { p0 ->
            onPrintResult?.invoke(p0 == SdkResult.Success)
            when (p0) {
                SdkResult.Success -> Log.d(TAG, "Printer job finished successfully!")
                SdkResult.Printer_Print_Fail -> Log.e(TAG, "Printer Failed: $p0")
                SdkResult.Printer_Busy -> Log.e(TAG, "Printer is Busy: $p0")
                SdkResult.Printer_PaperLack -> Log.e(TAG, "Printer is out of paper: $p0")
                SdkResult.Printer_Fault -> Log.e(TAG, "Printer fault: $p0")
                SdkResult.Printer_TooHot -> Log.e(TAG, "Printer temperature is too hot: $p0")
                SdkResult.Printer_UnFinished -> Log.w(TAG, "Printer job is unfinished: $p0")
                SdkResult.Printer_Other_Error -> Log.e(TAG, "Printer Other_Error: $p0")
                else -> Log.e(TAG, "Generic Fail Error: $p0")
            }
        }

        printer.startPrint(true, listener)
    }
    private fun formatSummaryValue(metric: SummaryMetric, currencySymbol: String): String {
        val currency = currencySymbol.takeIf { it.isNotBlank() } ?: ""
        val amount = metric.amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
        return String.Companion.format(Locale.US, "%3d %-3s %10s", metric.count, currency, amount)
    }

    private fun getEntryModeLetter(transaction: Transaction): String {
        return when (transaction.cardEntryMethod.uppercase(Locale.US)) {
            "EMV" -> "C"
            "EMV_CONTACTLESS", "NFC" -> "L"
            "SWIPE" -> "S"
            "MANUAL" -> "M"
            "FALLBACK_SWIPE" -> "F"
            else -> ""
        }
    }

    private fun getShortTransactionLabel(transaction: Transaction): String {
        return transaction.shortReportLabel()
    }

    override fun printBatchReport(
        context: Context,
        queryList: List<Transaction>,
        batchSummary: BatchSummary,
        profile: Profile
    ) {
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        printer.initPrinter()
        val state = printer.status
        Log.d(TAG, "printer state = $state")

        printer.setGray(PRINT_GRAY_LEVEL)
        printer.setLetterSpacing(4)
        //printer.setTypeface(Typeface.DEFAULT)
        // Business info
        printer.appendPrnStr(profile.businessName, SMALLFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(profile.streetAddress, SMALLFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(
            "${profile.cityState} ${profile.zipCode}",
            SMALLFONTSIZE,
            AlignEnum.CENTER,
            false
        )
        printer.appendPrnStr(profile.phoneNumber, SMALLFONTSIZE, AlignEnum.CENTER, false)

        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)

        printer.appendPrnStr(
            "Batch Report",
            LARGEFONTSIZE,
            AlignEnum.CENTER,
            true
        )

        printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)

        // Print transactions
        if (SysParam.Companion.getInstance().DetailedBatchReport) {
            printer.appendPrnStr(
                "Order # ${
                    GlobalConnectPaymentApplication.Companion.instance.resources.getString(
                        R.string.type
                    )
                }  ${
                    GlobalConnectPaymentApplication.Companion.instance.resources.getString(
                        R.string.tip
                    )
                }   ${GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.amt)}",
                SMALLFONTSIZE,
                AlignEnum.LEFT,
                true
            )

            printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)

            if (queryList.isEmpty()) {
                printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
                printer.appendPrnStr(
                    GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.no_data),
                    SMALLFONTSIZE,
                    AlignEnum.CENTER,
                    false
                )
            } else {
                for (transaction in queryList) {
                    printer.appendPrnStr(
                        "${transaction.orderNo} ${transaction.type.toStringForUsers()}  $${transaction.tipAmount}",
                        "$${transaction.totalAmount}",
                        TINYFONTSIZE,
                        false
                    )
                    printer.appendPrnStr(
                        "TxnId: ${transaction.transactionId}",
                        "${transaction.resolvedCardBrand()} ${transaction.masked_cardNumber.takeLast(4)}",
                        TINYFONTSIZE,
                        false
                    )
                }
            }
        }

        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)


        // Summary start
        printer.appendPrnStr(
            GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.summary_credit),
            "${batchSummary.creditRecords}",
            SMALLFONTSIZE,
            false
        )
        printer.appendPrnStr(
            GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.summary_credit_total),
            "$${batchSummary.creditTotal}", SMALLFONTSIZE, false
        )
        printer.appendPrnStr(
            GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.summary_tip_total),
            "$${batchSummary.tipTotal}",
            SMALLFONTSIZE,
            false
        )

        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(
            GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.report_generated_on),
            SMALLFONTSIZE,
            AlignEnum.LEFT,
            false
        )
        printer.appendPrnStr(
            LocalDateTime.now().format(dateTimeFormatterForUsers),
            SMALLFONTSIZE,
            AlignEnum.LEFT,
            false
        )
        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(
            GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.end_of_report),
            LARGEFONTSIZE,
            AlignEnum.LEFT,
            false
        )
        printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)

        val listener = OnPrintListener { p0 ->
            when (p0) {
                SdkResult.Success -> Log.d(TAG, "Printer job finished successfully!")
                SdkResult.Printer_Print_Fail -> Log.e(TAG, "Printer Failed: $p0")
                SdkResult.Printer_Busy -> Log.e(TAG, "Printer is Busy: $p0")
                SdkResult.Printer_PaperLack -> Log.e(
                    TAG,
                    "Printer is out of paper: $p0"
                )

                SdkResult.Printer_Fault -> Log.e(TAG, "Printer fault: $p0")
                SdkResult.Printer_TooHot -> Log.e(
                    TAG,
                    "Printer temperature is too hot: $p0"
                )

                SdkResult.Printer_UnFinished -> Log.w(
                    TAG,
                    "Printer job is unfinished: $p0"
                )

                SdkResult.Printer_Other_Error -> Log.e(TAG, "Printer Other_Error: $p0")
                else -> Log.e(TAG, "Generic Fail Error: $p0")
            }
        }

        printer.startPrint(true, listener)
    }

    override fun printTotalsReport(
        context: Context,
        report: PrintableTotalsReport,
        profile: Profile?,
        tmsDatabase: TMSDATA,
        onPrintResult: ((Boolean) -> Unit)?,
    ) {
        //printerTest(context);
        //return
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        if (onPrintResult != null && printer.status != SdkResult.Success) {
            onPrintResult(false)
            return
        }
        printer.initPrinter()
        printer.setGray(PRINT_GRAY_LEVEL)
        printer.setTypeface(Typeface.DEFAULT)

        val terminal = tmsDatabase.Terminal[0]
        val acquirer = tmsDatabase.Acquirer[0]

        printHeader(context, terminal.MerchantTitle1, terminal.MerchantTitle2, terminal.MerchantTitle3, acquirer.AcqLine1, printerObject = printer, printLogo = true)

        val resources = GlobalConnectPaymentApplication.Companion.instance.resources

        printer.appendPrnStr(report.title, LARGEFONTSIZE, AlignEnum.CENTER, true)
        report.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
            printer.appendPrnStr(subtitle, SMALLFONTSIZE, AlignEnum.CENTER, false)
        }

        tmsDatabase.Terminal.firstOrNull()?.TermID?.takeIf { it.isNotBlank() }?.let { termId ->
            printer.appendPrnStr(
                resources.getString(R.string.totals_terminal_id_label, termId),
                SMALLFONTSIZE,
                AlignEnum.CENTER,
                false,
            )
        }

        printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)

        report.sections.forEach { section ->
            printer.appendPrnStr(section.title, MEDIUMFONTSIZE, AlignEnum.LEFT, true)
            section.lines.forEach { line ->
                printer.printJustified(
                    text = formatTotalsLine(line),
                    printFontSize = line.printFontSize,
                    isBold = line.isBold,
                )
            }
            printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)
        }

        printer.appendPrnStr(
            "${resources.getString(R.string.report_generated_on)} ${report.generatedAt.format(
                dateTimeFormatterForUsers
            )}",
            SMALLFONTSIZE,
            AlignEnum.LEFT,
            false,
        )
        printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(
            resources.getString(R.string.end_of_report),
            LARGEFONTSIZE,
            AlignEnum.CENTER,
            false,
        )
        printer.appendPrnStr(dottedSpacer, SMALLFONTSIZE, AlignEnum.CENTER, false)

        val listener = OnPrintListener { result ->
            onPrintResult?.invoke(result == SdkResult.Success)
            when (result) {
                SdkResult.Success -> Log.d(TAG, "Totals report printed successfully")
                SdkResult.Printer_Print_Fail -> Log.e(TAG, "Totals report failed: $result")
                SdkResult.Printer_Busy -> Log.e(TAG, "Printer busy during totals report: $result")
                SdkResult.Printer_PaperLack -> Log.e(
                    TAG,
                    "Printer out of paper during totals report: $result"
                )

                SdkResult.Printer_Fault -> Log.e(TAG, "Printer fault during totals report: $result")
                SdkResult.Printer_TooHot -> Log.e(
                    TAG,
                    "Printer too hot during totals report: $result"
                )

                SdkResult.Printer_UnFinished -> Log.w(
                    TAG,
                    "Printer unfinished job during totals report: $result"
                )

                SdkResult.Printer_Other_Error -> Log.e(
                    TAG,
                    "Printer other error during totals report: $result"
                )

                else -> Log.e(TAG, "Unknown error during totals report printing: $result")
            }
        }

        printer.startPrint(true, listener)
    }

    override fun printSettlementReceipt(
        context: Context,
        snapshot: SettlementSnapshot,
        tmsDatabase: TMSDATA,
    ) {
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        printer.initPrinter()
        printer.setGray(PRINT_GRAY_LEVEL)
        //printer.setTypeface(Typeface.DEFAULT)

        val resources = GlobalConnectPaymentApplication.Companion.instance.resources
        val terminal = tmsDatabase.Terminal[0]
        val acquirer = tmsDatabase.Acquirer.find { it.AcqID == snapshot.acquirerId }
        if (acquirer == null) {
            Toast.makeText(context, "Error: Acquirer not found for ID ${snapshot.acquirerId}", Toast.LENGTH_LONG).show()
            return
        }
        printHeader(context, terminal.MerchantTitle1, terminal.MerchantTitle2, terminal.MerchantTitle3, acquirer.AcqLine1, printerObject = printer, printLogo = true)

        printer.printCentered(resources.getString(R.string.settlement_receipt_title), PrintFontSize.LARGE, isBold = true)

        val parsedTimestamp = parseSnapshotTimestamp(snapshot.completedAt)
        if (parsedTimestamp != null) {
            printer.printLine(
                resources.getString(R.string.settlement_receipt_date_label) + ": " + parsedTimestamp.format(settlementDateFormatter),
                parsedTimestamp.format(settlementTimeFormatter),
                PrintFontSize.MEDIUM,
            )
        } else {
            printer.printLine(
                resources.getString(R.string.settlement_receipt_date_label) + ": " + snapshot.completedAt,
                PrintFontSize.MEDIUM,
            )
        }
        snapshot.acquirerName.takeIf { it.isNotBlank() }?.let {
            printer.printLine(it, PrintFontSize.MEDIUM, isBold = true)
        }

        val merchantLabel = resources.getString(R.string.settlement_receipt_merchant_label)
        val terminalLabel = resources.getString(R.string.settlement_receipt_terminal_label)
        val batchLabel = resources.getString(R.string.settlement_receipt_batch_label)
        val hostLabel = resources.getString(R.string.settlement_receipt_host_label)

        val merchantId = snapshot.merchantId?.takeIf { it.isNotBlank() }
        val terminalId = snapshot.terminalId?.takeIf { it.isNotBlank() }
        if (merchantId != null || terminalId != null) {
            printer.printLine(
                "$merchantLabel:${merchantId ?: ""}",
                terminalId ?: "",
                PrintFontSize.SMALL,
            )
        }

        val batchNumber = snapshot.batchNumber?.takeIf { it.isNotBlank() }
        printer.printLine(
            batchNumber?.let { "$batchLabel: $it" } ?: "",
            "$hostLabel: ${snapshot.acquirerName}",
            PrintFontSize.SMALL,
        )

        snapshot.responseCode.takeIf { it.isNotBlank() }?.let { code ->
            printer.printLine(
                resources.getString(R.string.settlement_receipt_response_code, code),
                PrintFontSize.SMALL,
            )
        }

        if (snapshot.transactions.isEmpty()) {
            printer.printLine(
                resources.getString(R.string.settlement_receipt_no_transactions),
                PrintFontSize.TINY,
            )
        } else {
            val (transHeaderLeft, transHeaderRight) = formatTransactionHeader(resources)
            printer.printLine(
                transHeaderLeft,transHeaderRight,
                PrintFontSize.TINY,
                isBold = true,
            )
            printer.printLine("-".repeat(getLineWidth(PrintFontSize.SMALL)), PrintFontSize.SMALL)
            snapshot.transactions.forEach { transaction ->
                val (left,right) = formatTransactionLine(transaction)
                printer.printLine(
                    left, right,
                    PrintFontSize.TINY,
                )
                printInstallmentDetails(printer, transaction.installmentDetails())
            }
        }

        printer.printLine("-".repeat(getLineWidth(PrintFontSize.SMALL)), PrintFontSize.SMALL)
        printer.printLine(resources.getString(R.string.settlement_totals_header), PrintFontSize.MEDIUM, isBold = true)
        snapshot.totals.orderedEntries()
            .filter { (_, metric) -> metric.hasActivity() }
            .forEach { (kind, metric) ->
                val label = resources.labelFor(kind)
                printer.printLine(
                    label,
                    formatSettlementMetric(metric, snapshot.currencySymbol),
                    PrintFontSize.SMALL,
                )
            }

        printer.printLine("-".repeat(getLineWidth(PrintFontSize.SMALL)), PrintFontSize.SMALL)
        printer.printLine(resources.getString(R.string.settlement_receipt_end_footer), PrintFontSize.SMALL, isBold = true)

        val listener = OnPrintListener { result ->
            when (result) {
                SdkResult.Success -> Log.d(TAG, "Settlement receipt printed successfully")
                SdkResult.Printer_Print_Fail -> Log.e(TAG, "Settlement receipt failed: $result")
                SdkResult.Printer_Busy -> Log.e(TAG, "Printer busy during settlement receipt: $result")
                SdkResult.Printer_PaperLack -> Log.e(TAG, "Printer out of paper during settlement receipt: $result")
                SdkResult.Printer_Fault -> Log.e(TAG, "Printer fault during settlement receipt: $result")
                SdkResult.Printer_TooHot -> Log.e(TAG, "Printer too hot during settlement receipt: $result")
                SdkResult.Printer_UnFinished -> Log.w(TAG, "Printer unfinished job during settlement receipt: $result")
                SdkResult.Printer_Other_Error -> Log.e(TAG, "Printer other error during settlement receipt: $result")
                else -> Log.e(TAG, "Unknown error during settlement receipt printing: $result")
            }
        }

        printer.startPrint(true, listener)
    }

    private fun formatTotalsLine(line: PrintableTotalsLine): String {
        val width = getLineWidth(line.printFontSize)
        val right = buildString {
            append(line.count.toString().padStart(3))
            append(' ')
            line.currencySymbol.takeIf { it.isNotBlank() }?.let {
                append(it)
                append(' ')
            }
            append(line.amount.setScale(2, RoundingMode.HALF_UP).toPlainString().padStart(12))
        }
        val maxLeftLength = (width - 1 - right.length).coerceAtLeast(0)
        val indent = buildString { repeat(line.indentLevel) { append("  ") } }
        val indentToUseLength = minOf(indent.length, (maxLeftLength - 1).coerceAtLeast(0))
        val indentToUse = indent.take(indentToUseLength)
        val remainingForLabel = (maxLeftLength - indentToUse.length).coerceAtLeast(0)
        val labelPortion = line.label.take(remainingForLabel)
        val left = (indentToUse + labelPortion).ifEmpty { line.label.take(maxLeftLength) }
        return "$left|$right"
    }

    private fun parseSnapshotTimestamp(value: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(value, dateTimeFormatter) }.getOrNull()

    private fun formatSettlementMetric(metric: TotalsMetric, currencySymbol: String): String {
        val normalizedSymbol = currencySymbol.takeIf { it.isNotBlank() } ?: ""
        val formattedAmount = FormatterUtils.formatAmount(normalizedSymbol, metric.amount).trim()
        return String.format(Locale.US, "%3d %s", metric.count, formattedAmount)
    }

    private fun TotalsMetric.hasActivity(): Boolean =
        count != 0 || amount.compareTo(BigDecimal.ZERO) != 0

    private fun formatTransactionHeader(resources: Resources): Pair<String,String> {
        val left =  String.format(
            Locale.US,
            SETTLEMENT_TRANSACTION_FORMAT_LEFT,
            resources.getString(R.string.settlement_receipt_tran_type_header),
            resources.getString(R.string.settlement_receipt_invoice_header),
            resources.getString(R.string.settlement_receipt_card_header),
            ""
        )
        val right =  String.format(
            Locale.US,
            SETTLEMENT_TRANSACTION_FORMAT_RIGHT,
            resources.getString(R.string.settlement_receipt_auth_header),
            resources.getString(R.string.settlement_receipt_amount_header),
        )
        return Pair(left,right)
    }

    private fun printInstallmentDetails(printer: Printer, details: InstallmentDetails?) {
        details ?: return
        val resources = GlobalConnectPaymentApplication.instance.resources
        printer.appendPrnStr("${resources.getString(R.string.receipt_payment_plan)}: ${details.planName} (${details.planCode})",
            TINYFONTSIZE, AlignEnum.LEFT, false)
        printer.appendPrnStr("${resources.getString(R.string.receipt_installment_count)}: ${details.count}",
            TINYFONTSIZE, AlignEnum.LEFT, false)
    }

    private fun formatTransactionLine(transaction: Transaction): Pair<String,String> {
        val invoice = resolveTransactionInvoice(transaction)
        val bin = resolveTransactionBin(transaction)
        val card = resolveTransactionCard(transaction)
        val auth = resolveTransactionAuth(transaction)
        val amount = resolveTransactionAmount(transaction)
        val left =  String.format(
            Locale.US,
            SETTLEMENT_TRANSACTION_FORMAT_LEFT,
            getShortTransactionLabel(transaction),
            invoice,
            card.takeLast(6),
            getEntryModeLetter(transaction)
        )
        val right =  String.format(
            Locale.US,
            SETTLEMENT_TRANSACTION_FORMAT_RIGHT,
            auth,
            formatTransactionAmount(amount),
        )
        return Pair(left,right)
    }

    private fun resolveTransactionInvoice(transaction: Transaction): String {
        val invoice = transaction.invoiceId.takeIf { it.isNotBlank() }
            ?: transaction.orderNo.toString()
        return invoice.takeLast(5).padStart(5, '0')
    }

    private fun resolveTransactionBin(transaction: Transaction): String {
        val digits = transaction.cardNumber.filter(Char::isDigit)
            .ifBlank { transaction.masked_cardNumber.filter(Char::isDigit) }
        val normalized = digits.takeLast(5)
        return if (normalized.isNotBlank()) normalized.padStart(5, '0') else "-"
    }

    private fun resolveTransactionCard(transaction: Transaction): String {
        val masked = transaction.masked_cardNumber.takeIf { it.isNotBlank() }
        if (!masked.isNullOrBlank()) {
            return masked.takeLast(8)
        }
        val last4 = transaction.cardNumber.takeLast(4)
        return if (last4.isNotBlank()) "****$last4" else "-"
    }

    private fun resolveTransactionAuth(transaction: Transaction): String {
        val auth = transaction.authorizationId
            .takeIf { it.isNotBlank() }
            ?: transaction.authCode.takeIf { it.isNotBlank() }
        return auth?.takeLast(6) ?: "-"
    }

    private fun resolveTransactionAmount(transaction: Transaction): BigDecimal {
        val sanitized = transaction.totalAmount.replace(",", "").trim()
        return sanitized.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
            ?: BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    }

    private fun formatTransactionAmount(amount: BigDecimal): String {
        val normalized = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
        return if (normalized.length <= 8) normalized else normalized.takeLast(8)
    }

    private fun Resources.labelFor(kind: ReconciliationTotalKind): String = when (kind) {
        ReconciliationTotalKind.CREDIT_SALES -> getString(R.string.settlement_totals_credit_sales)
        ReconciliationTotalKind.TAX1 -> getString(R.string.settlement_totals_tax1)
        ReconciliationTotalKind.TAX1DISCOUNT -> getString(R.string.settlement_totals_tax1_discount)
        ReconciliationTotalKind.TAX2 -> getString(R.string.settlement_totals_tax2)
        ReconciliationTotalKind.TIP -> getString(R.string.settlement_totals_tip)
        ReconciliationTotalKind.REFUNDS -> getString(R.string.settlement_totals_refunds)
        ReconciliationTotalKind.CASH -> getString(R.string.settlement_totals_cash)
        ReconciliationTotalKind.DEBIT_SALES -> getString(R.string.settlement_totals_debit_sales)
        ReconciliationTotalKind.DEBIT_REFUNDS -> getString(R.string.settlement_totals_debit_refunds)
        ReconciliationTotalKind.AUTHORIZATIONS -> getString(R.string.settlement_totals_authorizations)
        ReconciliationTotalKind.AUTHORIZATION_REFUNDS -> getString(R.string.settlement_totals_authorization_refunds)
        ReconciliationTotalKind.HYPERCOM_RESERVED_1 -> getString(R.string.settlement_totals_reserved_one)
        ReconciliationTotalKind.HYPERCOM_RESERVED_2 -> getString(R.string.settlement_totals_reserved_two)
        ReconciliationTotalKind.VOIDED_SALES -> getString(R.string.settlement_totals_voided_sales)
        ReconciliationTotalKind.VOIDED_REFUNDS -> getString(R.string.settlement_totals_voided_refunds)
        ReconciliationTotalKind.PAYMENTS -> getString(R.string.settlement_totals_payments)
        ReconciliationTotalKind.VOIDED_PAYMENTS -> getString(R.string.settlement_totals_voided_payments)
        ReconciliationTotalKind.LOYALTY -> getString(R.string.settlement_totals_loyalty)
        ReconciliationTotalKind.CASHBACK -> getString(R.string.settlement_totals_cashback)
    }

    override fun <T> printHeader(
        context: Context,
        headerTitle1 : String,
        headerTitle2 : String,
        headerTitle3 : String,
        headerTitle4 : String,
        printerObject : T,
        printLogo : Boolean
    ) {
        if (printLogo)
        {
            val printer = printerObject as Printer
            val headerLogo = getBitmapFromAssets(context, "header_logo.bmp")
            if (headerLogo != null) {
                printer.appendImage(headerLogo, AlignEnum.CENTER)
            }
            if (headerTitle1.isNotEmpty())
                printer.appendPrnStr(headerTitle1, MEDIUMFONTSIZE, AlignEnum.CENTER, false)
            if (headerTitle2.isNotEmpty())
                printer.appendPrnStr(headerTitle2, MEDIUMFONTSIZE, AlignEnum.CENTER, false)
            if (headerTitle3.isNotEmpty())
                printer.appendPrnStr(headerTitle3, MEDIUMFONTSIZE, AlignEnum.CENTER, false)
            if (headerTitle4.isNotEmpty())
                printer.appendPrnStr(headerTitle4, MEDIUMFONTSIZE, AlignEnum.CENTER, false)
        }
    }
    fun getBitmapFromAssets(context: Context, fileName: String): Bitmap? {
        return try {
            val assetManager = context.assets
            val inputStream = assetManager.open(fileName)
            BitmapFactory.decodeStream(inputStream) // ✅ Decode bitmap from stream
        } catch (e: IOException) {
            e.printStackTrace()
            null // Return null if the file is not found or cannot be loaded
        }
    }
    override fun printReceipt(
        context: Context,
        transaction: Transaction,
        profile: Profile,
        tmsDatabase: TMSDATA,
        bitmap: ImageBitmap?,
        recipient: String,
        onPrintResult: ((Boolean) -> Unit)?,
        onPrintStatus: ((Int) -> Unit)?,
    ) {
        val originalTax1Amount = Tax1DiscountCalculator.originalTaxAmountFromDiscounted(
            discountedTaxAmount = transaction.tax1Amount,
            discountAmount = transaction.tax1DiscountAmount,
        )
        val receiptBaseAmount = transaction.baseAmount.ifBlank {
            transaction.subTotal.ifBlank { transaction.totalAmount }
        }
        val tax1Present = (originalTax1Amount.toBigDecimal() > BigDecimal.ZERO) || (tmsDatabase.Terminal[0].Tax1Mandatory)
        val tax1DiscountPresent = (transaction.tax1DiscountAmount.toBigDecimal() > BigDecimal.ZERO)
        val tax2Present = (transaction.tax2Amount.toBigDecimal() > BigDecimal.ZERO)
        val tipPresent = (transaction.tipAmount.toBigDecimal() > BigDecimal.ZERO)

        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        printer.initPrinter()
        val state = printer.status
        Log.d(TAG, "printer state = $state")
        if (state != SdkResult.Success) {
            Log.e(TAG, "Printer preflight failed: ${NexgoSdkResult.sdkName(state)} ($state)")
            onPrintStatus?.invoke(state)
            onPrintResult?.invoke(false)
            return
        }
        //printerManager.setPrintFont("/system/fonts/Android-1.ttf");
        printer.setGray(PRINT_GRAY_LEVEL)
        //printer.setLetterSpacing(4)
        //printer.setTypeface(Typeface.MONOSPACE)
        // Business info
        val terminal = tmsDatabase.Terminal[0]
        val acquirer = tmsDatabase.Acquirer[0]
        val curSym = acquirer.Currency
        printHeader(context, terminal.MerchantTitle1, terminal.MerchantTitle2, terminal.MerchantTitle3, acquirer.AcqLine1, printerObject = printer, printLogo = true)
/*
        val headerlogo = getBitmapFromAssets(context, "header_logo.bmp")
        if (headerlogo != null) {
            printer.appendImage(headerlogo, AlignEnum.CENTER)
        }

        if (terminal.MerchantTitle1.isNotEmpty())
            printer.appendPrnStr(terminal.MerchantTitle1, MEDIUMFONTSIZE, AlignEnum.CENTER, false)
        if (terminal.MerchantTitle2.isNotEmpty())
            printer.appendPrnStr(terminal.MerchantTitle2, MEDIUMFONTSIZE, AlignEnum.CENTER, false)
        if (terminal.MerchantTitle3.isNotEmpty())
            printer.appendPrnStr(terminal.MerchantTitle3, MEDIUMFONTSIZE, AlignEnum.CENTER, false)
*/
        printer.appendPrnStr(acquirer.MerchID, acquirer.AcqTermID, SMALLFONTSIZE, false)

        val time = LocalDateTime.parse(transaction.localDateTime, dateTimeFormatter)
        printer.appendPrnStr(
            time.format(receiptDateFormatter), time.format(
                DateTimeFormatter.ofPattern("HH:mm:ss")
            ), SMALLFONTSIZE, false
        )

        var entryMode = when (transaction.cardEntryMethod.uppercase()) {
            "SWIPE" -> GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.short_entrymode_MSR)
            "NFC" -> GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.short_entrymode_NFC)
            "EMV" -> GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.short_entrymode_EMV)
            "EMV_CONTACTLESS" -> GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.short_entrymode_EMVCTLS)
            "MANUAL" -> GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.short_entrymode_MANUAL)
            "FALLBACK_SWIPE" -> GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.short_entrymode_FALLBACK)
             else -> GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.short_entrymode_UNKNOWN)
        }
        entryMode = GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.entrymode_short , entryMode)

        val batchno = 1
        printer.appendPrnStr(entryMode, "${GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.batch)}#: ${FormatterUtils.paddedInteger(batchno, 6)}" , TINYFONTSIZE, false )

        printer.appendPrnStr(transaction.masked_cardNumber, transaction.resolvedCardBrand(),  MEDIUMFONTSIZE, false)

        printer.appendPrnStr("RRN: ${transaction.retrievalReferenceNumber.trim().ifBlank { "----" }}", "${
            GlobalConnectPaymentApplication.Companion.instance.resources.getString(
                R.string.invoice_short)}: ${transaction.invoiceId}" , TINYFONTSIZE, false)

        val resources = GlobalConnectPaymentApplication.Companion.instance.resources
        printInstallmentDetails(printer, transaction.installmentDetails())
        val receiptReferences = resolveReceiptReferenceValues(
            folioNumber = transaction.folioNumber,
            externalReferenceNumber = transaction.externalReferenceNumber,
        )
        if (transaction.authCode.isNotBlank()) {
            printer.appendPrnStr(
                "${resources.getString(R.string.auth_code_short)}: ${transaction.authCode.trim()}",
                TINYFONTSIZE,
                AlignEnum.RIGHT,
                false,
            )
        }
        receiptReferences.folioNumber?.let { folioNumber ->
            printer.appendPrnStr(
                "${resources.getString(R.string.hotel_check_in_report_detail_folio_label)}: $folioNumber",
                SMALLFONTSIZE,
                AlignEnum.LEFT,
                true,
            )
        }
        receiptReferences.externalReferenceNumber?.let { externalReferenceNumber ->
            printer.appendPrnStr(
                "${resources.getString(R.string.ext_ref)}: $externalReferenceNumber",
                SMALLFONTSIZE,
                AlignEnum.LEFT,
                true,
            )
        }

        printer.appendPrnStr(" ", LARGEFONTSIZE, AlignEnum.LEFT, false )
        // Transaction info
        if (transaction.returnStatus == ReturnStatus.Voided) {
            printer.appendPrnStr(GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.receipt_void_prefix), LARGEFONTSIZE, AlignEnum.LEFT, false)
        }
        val isLoyaltyBalance = transaction.type == TransactionType.LOYALTY_BALANCE
        val partialApproval = transaction.partialApprovalReceipt()
        if (partialApproval != null) {
            printer.appendPrnStr(resources.getString(R.string.receipt_partial_approved), SMALLFONTSIZE, AlignEnum.CENTER, true)
            printer.appendPrnStr(resources.getString(R.string.receipt_verify_amount), SMALLFONTSIZE, AlignEnum.CENTER, true)
            printer.appendPrnStr(" ", SMALLFONTSIZE, AlignEnum.LEFT, false)
        }
        if (isLoyaltyBalance) {
            printer.appendPrnStr(
                transaction.type.toStringForUsers(),
                LARGEFONTSIZE,
                AlignEnum.CENTER,
                true,
            )
            transaction.loyaltyBalancePoints.takeIf { it.isNotBlank() }?.let { points ->
                printer.appendPrnStr(
                    resources.getString(R.string.loyalty_points_available, one.globalconnect.paymentapp.transaction.LoyaltyContract.formatPoints(points)),
                    LARGEFONTSIZE,
                    AlignEnum.CENTER,
                    true,
                )
            }
        } else {
            printer.appendPrnStr(transaction.type.toStringForUsers(), FormatterUtils.formatAmount(curSym, receiptBaseAmount), LARGEFONTSIZE, false)
        }
        if (!isLoyaltyBalance && tax1Present) {
            printer.appendPrnStr(" ${GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.Tax).uppercase()}",
                FormatterUtils.formatAmount(curSym, originalTax1Amount),SMALLFONTSIZE,false)
            if (tax1DiscountPresent)
            {
                printer.appendPrnStr("  ${GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.Tax_Discount).uppercase()}", "${FormatterUtils.formatAmount(curSym, transaction.tax1DiscountAmount.toBigDecimal().negate())}   ", SMALLFONTSIZE, false)
            }
        }
        if (!isLoyaltyBalance && tax2Present)
            printer.appendPrnStr(" ${GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.Tax2).uppercase()}" , FormatterUtils.formatAmount(curSym, transaction.tax2Amount), SMALLFONTSIZE, false)
        if (!isLoyaltyBalance && tipPresent)
            printer.appendPrnStr(" ${GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.Tip).uppercase()}", FormatterUtils.formatAmount(curSym, transaction.tipAmount), SMALLFONTSIZE, false)

        if (!isLoyaltyBalance) {
            printer.appendPrnStr("-".repeat(CHARSPERLINE_SMALLFONTSIZE/3), SMALLFONTSIZE, AlignEnum.RIGHT, false)
            if (partialApproval != null) {
                printer.appendPrnStr(resources.getString(R.string.receipt_original_amount),
                    partialApproval.originalAmount?.let { FormatterUtils.formatAmount(curSym, it) } ?: "----",
                    SMALLFONTSIZE, false)
                printer.appendPrnStr(resources.getString(R.string.receipt_approved_amount),
                    FormatterUtils.formatAmount(curSym, partialApproval.approvedAmount), LARGEFONTSIZE, false)
            } else {
                printer.appendPrnStr(resources.getString(R.string.Total).uppercase(), FormatterUtils.formatAmount(curSym, transaction.totalAmount), LARGEFONTSIZE, false)
            }
        }

        /** Tip stuff for auth transactions */
        if (transaction.type != TransactionType.REFUND && transaction.returnStatus != ReturnStatus.Voided) {
            val cvmPresentation = resolveReceiptCvmPresentation(
                transaction = transaction,
                configuredSignatureRequired = SysParam.Companion.getInstance().signatureMode != SignatureMode.None,
            )
            when (cvmPresentation.pinVerification) {
                ReceiptPinVerification.ONLINE -> printer.appendPrnStr(
                    resources.getString(R.string.PinVerified),
                    SMALLFONTSIZE,
                    AlignEnum.CENTER,
                    true,
                )
                ReceiptPinVerification.OFFLINE -> printer.appendPrnStr(
                    resources.getString(R.string.PinVerifiedICC),
                    SMALLFONTSIZE,
                    AlignEnum.CENTER,
                    true,
                )
                ReceiptPinVerification.NONE -> Unit
            }

            if (cvmPresentation.noSignatureRequiredEmv) {
                printer.appendPrnStr(
                    resources.getString(
                        when {
                            cvmPresentation.noSignatureRequiredCdcvm ->
                                R.string.receipt_no_signature_required_cdcvm
                            cvmPresentation.noSignatureRequiredCvm ->
                                R.string.receipt_no_signature_required_cvm
                            else -> R.string.receipt_no_signature_required_emv
                        },
                    ),
                    // The English MTIP wording is 33 characters; the tiny font fits 38 per line.
                    TINYFONTSIZE,
                    AlignEnum.CENTER,
                    true,
                )
                printer.appendPrnStr(
                    transaction.cardholderName.trim(),
                    SMALLFONTSIZE,
                    AlignEnum.CENTER,
                    false,
                )
            } else if (cvmPresentation.signatureRequired) {
                val signaturetext = resources.getString(R.string.receipt_signature)
                if (bitmap == null) {
                    printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
                    printer.appendPrnStr("          ", LARGEFONTSIZE, AlignEnum.CENTER, false)
                    printer.appendPrnStr(signaturetext + "_".repeat(CHARSPERLINE_SMALLFONTSIZE - signaturetext.length), SMALLFONTSIZE, AlignEnum.LEFT, false)
                    printer.appendPrnStr(
                        transaction.cardholderName.trim(),
                        SMALLFONTSIZE,
                        AlignEnum.CENTER,
                        false,
                    )
                    val agreement1 = resources.getString(R.string.receipt_agreement_1).uppercase().trim()
                    val agreement2 = resources.getString(R.string.receipt_agreement_2).uppercase().trim()
                    val agreement3 = resources.getString(R.string.receipt_agreement_3).uppercase().trim()
                    if (agreement1.isNotEmpty())
                        printer.appendPrnStr(agreement1, TINYFONTSIZE, AlignEnum.CENTER,false)
                    if (agreement2.isNotEmpty())
                        printer.appendPrnStr(agreement2,TINYFONTSIZE, AlignEnum.CENTER,false)
                    if (agreement3.isNotEmpty())
                        printer.appendPrnStr(agreement3,TINYFONTSIZE, AlignEnum.CENTER,false)
                } else {
                    printer.appendImage(
                        bitmap.asAndroidBitmap(),
                        AlignEnum.CENTER
                    )
                    printer.appendPrnStr(
                        transaction.cardholderName.trim(),
                        SMALLFONTSIZE,
                        AlignEnum.CENTER,
                        false,
                    )
                }
            }
        }

        if ((transaction.cardEntryMethod == "EMV")||(transaction.cardEntryMethod == "EMV_CONTACTLESS")) {
            val emvinfolabel = GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.emv_information)
            val emvdotter = "=".repeat((CHARSPERLINE_TINYFONTSIZE - emvinfolabel.length)/2)
            printer.appendPrnStr("$emvdotter$emvinfolabel$emvdotter", TINYFONTSIZE, AlignEnum.CENTER, false)
            /** Additional Info */

            printer.appendPrnStr(
                "${resources.getString(R.string.receipt_emv_app_label)} ${transaction.applicationName.trim()}",
                TINYFONTSIZE, AlignEnum.LEFT, false,
            )
            printer.appendPrnStr(
                "${resources.getString(R.string.AID)} ${transaction.AID.trim()}",
                TINYFONTSIZE, AlignEnum.LEFT, false,
            )

            printer.appendPrnStr("${GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.TVR)} ${transaction.TVR}", "${GlobalConnectPaymentApplication.Companion.instance.resources.getString(
                    R.string.TSI)} ${transaction.TSI}", TINYFONTSIZE, false)
            printer.appendPrnStr("${resources.getString(R.string.AC)} ${transaction.AC}",
                "${resources.getString(R.string.ARC)} ${transaction.ARC}", TINYFONTSIZE, false)
            printer.appendPrnStr("=".repeat(CHARSPERLINE_TINYFONTSIZE), TINYFONTSIZE, AlignEnum.CENTER, false)
        }
        if (recipient == MERCHANT) {
            printer.appendPrnStr(GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.merchant_copy), MINFONTSIZE, AlignEnum.CENTER, false)
        } else {
            printer.appendPrnStr(GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.customer_copy), MINFONTSIZE, AlignEnum.CENTER, false)
        }
        printer.appendPrnStr(
            GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.powered_by_global_connect), MINFONTSIZE,
            AlignEnum.CENTER,false)

        val versionText = "${GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.version)}: ${GlobalConnectPaymentApplication.Companion.instance.appVersion}"
        printer.appendPrnStr(versionText, MINFONTSIZE, AlignEnum.CENTER, false)

        printer.appendPrnStr(" ", MASSIVEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(" ", MASSIVEFONTSIZE, AlignEnum.CENTER, false)
        printer.appendPrnStr(" ", MASSIVEFONTSIZE, AlignEnum.CENTER, false)

        //printer.cutPaper();

        val listener = object : OnPrintListener {
            override fun onPrintResult(p0: Int) {
                when (p0) {
                    SdkResult.Success -> {
                        Log.d(TAG, "Printer job finished successfully!")
                    }
                    SdkResult.Printer_Print_Fail -> Log.e(TAG, "Printer Failed: $p0")
                    SdkResult.Printer_Busy -> Log.e(TAG, "Printer is Busy: $p0")
                    SdkResult.Printer_PaperLack -> Log.e(
                        TAG,
                        "Printer is out of paper: $p0"
                    )

                    SdkResult.Printer_Fault -> Log.e(TAG, "Printer fault: $p0")
                    SdkResult.Printer_TooHot -> Log.e(
                        TAG,
                        "Printer temperature is too hot: $p0"
                    )

                    SdkResult.Printer_UnFinished -> Log.w(
                        TAG,
                        "Printer job is unfinished: $p0"
                    )

                    SdkResult.Printer_Other_Error -> Log.e(TAG, "Printer Other_Error: $p0")
                    else -> Log.e(TAG, "Generic Fail Error: $p0")
                }
            }
        }

        printer.startPrint(false, object : OnPrintListener {
            override fun onPrintResult(result: Int) {
                listener.onPrintResult(result)
                onPrintStatus?.invoke(result)
                onPrintResult?.invoke(result == SdkResult.Success)
            }
        })
    }

    override fun printReversalReceipt(
        context: Context,
        profile: Profile?,
        tmsDatabase: TMSDATA,
        data: ReversalReceiptData,
    ) {
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        printer.initPrinter()
        printer.setGray(PRINT_GRAY_LEVEL)

        val terminal = tmsDatabase.Terminal.firstOrNull()
        val acquirer = tmsDatabase.Acquirer.firstOrNull()

        printHeader(context, terminal!!.MerchantTitle1, terminal!!.MerchantTitle2, terminal!!.MerchantTitle3, acquirer!!.AcqLine1, printerObject = printer, printLogo = false)

        acquirer.let {
            printer.appendPrnStr(it.MerchID, it.AcqTermID, SMALLFONTSIZE, false)
        }

        val timestamp = data.timestamp
        printer.appendPrnStr(
            timestamp.format(receiptDateFormatter),
            timestamp.format(reversalTimeFormatter),
            SMALLFONTSIZE,
            false
        )

        if (data.maskedPan.isNotBlank() || data.cardBrand.isNotBlank()) {
            printer.appendPrnStr(
                data.maskedPan.ifBlank { "" },
                data.cardBrand.ifBlank { "" },
                MEDIUMFONTSIZE,
                false
            )
        }

        val invoiceLabel = GlobalConnectPaymentApplication.Companion.instance.resources.getString(R.string.invoice_short)
        val invoiceValue = data.invoiceNumber.ifBlank { "----" }
        printer.appendPrnStr("RRN: ${data.rrn}", "$invoiceLabel: $invoiceValue", TINYFONTSIZE, false)
        printInstallmentDetails(printer, data.installmentDetails)

        printer.appendPrnStr(" ", LARGEFONTSIZE, AlignEnum.LEFT, false)
        printer.appendPrnStr(data.transactionTypeLabel, data.totalAmountText, LARGEFONTSIZE, false)

        printer.appendPrnStr(
            GlobalConnectPaymentApplication.Companion.instance.getString(R.string.receipt_transaction_reversed),
            MASSIVEFONTSIZE,
            AlignEnum.CENTER,
            true
        )
        printer.appendPrnStr(" ", MASSIVEFONTSIZE, AlignEnum.CENTER, false)

        val listener = object : OnPrintListener {
            override fun onPrintResult(p0: Int) {
                when (p0) {
                    SdkResult.Success -> Log.d(TAG, "Reversal receipt printed successfully")
                    SdkResult.Printer_Print_Fail -> Log.e(TAG, "Reversal receipt failed: $p0")
                    SdkResult.Printer_Busy -> Log.e(TAG, "Printer busy during reversal receipt: $p0")
                    SdkResult.Printer_PaperLack -> Log.e(TAG, "Printer out of paper during reversal receipt: $p0")
                    SdkResult.Printer_Fault -> Log.e(TAG, "Printer fault during reversal receipt: $p0")
                    SdkResult.Printer_TooHot -> Log.e(TAG, "Printer too hot during reversal receipt: $p0")
                    SdkResult.Printer_UnFinished -> Log.w(TAG, "Printer unfinished job during reversal receipt: $p0")
                    SdkResult.Printer_Other_Error -> Log.e(TAG, "Printer other error during reversal receipt: $p0")
                    else -> Log.e(TAG, "Unknown printer error during reversal receipt: $p0")
                }
            }
        }

        printer.startPrint(false, listener)
    }

    fun printAdminTicket(context: Context, ticket: AdminTicketData) {
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        val resources = context.resources
        printer.initPrinter()
        printer.setGray(PRINT_GRAY_LEVEL)
        printer.setTypeface(Typeface.MONOSPACE)

        val tmsDatabase = GlobalConnectPaymentApplication.instance.tmsDatabase
        val terminal = tmsDatabase.Terminal.firstOrNull()
        val primaryAcquirer = tmsDatabase.Acquirer.firstOrNull()
        printHeader(
            context,
            terminal?.MerchantTitle1 ?: "",
            terminal?.MerchantTitle2 ?: "",
            terminal?.MerchantTitle3 ?: "",
            primaryAcquirer?.AcqLine1 ?: "",
            printerObject = printer,
            printLogo = true,
        )

        printer.printCentered(resources.getString(R.string.admin_ticket_receipt_title), PrintFontSize.LARGE, isBold = true)
        printer.printLine("-".repeat(getLineWidth(PrintFontSize.TINY)), PrintFontSize.TINY)
        printer.printWrappedField(resources.getString(R.string.admin_ticket_id), ticket.ticketId)
        printer.printWrappedField(resources.getString(R.string.admin_ticket_title), ticket.ticketTitle)
        printer.printWrappedField(resources.getString(R.string.admin_ticket_request_type), ticket.requestType)
        printer.printWrappedField(resources.getString(R.string.admin_ticket_terminal_id), ticket.terminalId)
        printer.printWrappedField(resources.getString(R.string.admin_ticket_device_serial), ticket.deviceSerial)
        printer.printWrappedField(resources.getString(R.string.admin_ticket_lane_id), ticket.laneId)
        printer.printWrappedField(resources.getString(R.string.admin_ticket_created_at), ticket.createdAt)
        printer.printLine("-".repeat(getLineWidth(PrintFontSize.TINY)), PrintFontSize.TINY)
        printer.printWrappedField(resources.getString(R.string.admin_ticket_message), ticket.messageText)
        printer.printLine("-".repeat(getLineWidth(PrintFontSize.TINY)), PrintFontSize.TINY)
        printer.printWrappedCentered(resources.getString(R.string.admin_ticket_footer), PrintFontSize.TINY)
        printer.printLine("          ", PrintFontSize.LARGE)
        printer.startPrintAndAwaitCompletion()
    }

    private fun Printer.printWrappedField(label: String, value: String, printFontSize: PrintFontSize = PrintFontSize.TINY) {
        val cleanValue = value.trim()
        if (cleanValue.isBlank()) return

        val width = getLineWidth(printFontSize)
        val prefix = "${label.trim()}: "
        val availableFirstLine = width - prefix.length
        if (availableFirstLine >= MIN_WRAPPED_LAST_LINE && cleanValue.length <= availableFirstLine) {
            printLine(prefix + cleanValue, printFontSize)
            return
        }

        if (availableFirstLine >= MIN_WRAPPED_LAST_LINE) {
            wrapPrintableFieldValue(cleanValue, availableFirstLine, width)
                .forEachIndexed { index, line ->
                    printLine(if (index == 0) prefix + line else line, printFontSize)
                }
        } else {
            printLine(label.trim(), printFontSize, isBold = true)
            wrapPrintableText(cleanValue, width).forEach { printLine(it, printFontSize) }
        }
    }

    private fun Printer.printWrappedCentered(text: String, printFontSize: PrintFontSize) {
        wrapPrintableText(text.trim(), getLineWidth(printFontSize))
            .forEach { printCentered(it, printFontSize) }
    }

    internal fun wrapPrintableText(text: String, width: Int): List<String> {
        if (text.isBlank()) return emptyList()

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(Regex("\\s+"))) {
            if (word.length > width) {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                word.chunked(width).forEach { lines += it }
            } else if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= width) {
                current.append(' ').append(word)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return rebalanceShortLastLine(lines, width)
    }

    internal fun wrapPrintableFieldValue(text: String, firstLineWidth: Int, continuationWidth: Int): List<String> {
        if (text.isBlank()) return emptyList()

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        var currentWidth = firstLineWidth

        fun startNextLine(seed: String = "") {
            if (current.isNotEmpty()) lines += current.toString()
            current = StringBuilder(seed)
            currentWidth = continuationWidth
        }

        for (word in text.trim().split(Regex("\\s+"))) {
            if (word.length > currentWidth) {
                if (current.isNotEmpty()) startNextLine()
                val chunks = chunkLongWord(word, currentWidth, continuationWidth)
                chunks.dropLast(1).forEach { chunk ->
                    lines += chunk
                    currentWidth = continuationWidth
                }
                current = StringBuilder(chunks.last())
            } else if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= currentWidth) {
                current.append(' ').append(word)
            } else {
                startNextLine(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return rebalanceShortLastLine(lines, continuationWidth)
    }

    private fun chunkLongWord(word: String, firstWidth: Int, continuationWidth: Int): List<String> {
        val chunks = mutableListOf<String>()
        var remaining = word
        var width = firstWidth
        while (remaining.length > width) {
            chunks += remaining.take(width)
            remaining = remaining.drop(width)
            width = continuationWidth
        }
        if (remaining.isNotEmpty()) chunks += remaining
        return rebalanceShortLastLine(chunks, continuationWidth)
    }

    private fun rebalanceShortLastLine(lines: List<String>, width: Int): List<String> {
        if (lines.size < 2) return lines
        val balanced = lines.toMutableList()
        var last = balanced.last()
        if (last.length >= MIN_WRAPPED_LAST_LINE) return balanced

        var previous = balanced[balanced.lastIndex - 1]
        while (last.length < MIN_WRAPPED_LAST_LINE && previous.contains(' ')) {
            val splitAt = previous.lastIndexOf(' ')
            val moved = previous.substring(splitAt + 1)
            val candidateLast = "$moved $last"
            val candidatePrevious = previous.substring(0, splitAt)
            if (candidatePrevious.isBlank() || candidateLast.length > width) break
            previous = candidatePrevious
            last = candidateLast
        }

        if (last.length < MIN_WRAPPED_LAST_LINE && !previous.contains(' ')) {
            val moveCount = (MIN_WRAPPED_LAST_LINE - last.length)
                .coerceAtMost((previous.length - MIN_WRAPPED_LAST_LINE).coerceAtLeast(0))
            if (moveCount > 0) {
                last = previous.takeLast(moveCount) + last
                previous = previous.dropLast(moveCount)
            }
        }

        balanced[balanced.lastIndex - 1] = previous
        balanced[balanced.lastIndex] = last
        return balanced
    }

    override fun printConfigReport(context: Context, tmsDatabase: TMSDATA) {
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        printer.initPrinter()
        printer.setGray(PRINT_GRAY_LEVEL)
        printer.setTypeface(Typeface.MONOSPACE)

        val terminal = tmsDatabase.Terminal.firstOrNull()
        val primaryAcquirer = tmsDatabase.Acquirer.firstOrNull()

        printHeader(
            context,
            terminal?.MerchantTitle1 ?: "",
            terminal?.MerchantTitle2 ?: "",
            terminal?.MerchantTitle3 ?: "",
            primaryAcquirer?.AcqLine1 ?: "",
            printerObject = printer,
            printLogo = true,
        )

        val now = LocalDateTime.now()
        printer.printCentered("CONFIGURATION REPORT", PrintFontSize.LARGE, isBold = true)
        printer.printCentered(
            "${now.format(receiptDateFormatter)} ${now.format(reversalTimeFormatter)}",
            PrintFontSize.SMALL,
        )
        printer.printWrappedField("Model", GlobalConnectPaymentApplication.model)
        printer.printWrappedField("Serial number", GlobalConnectPaymentApplication.serialNumber)
        printer.printWrappedField("Application version", GlobalConnectPaymentApplication.instance.appVersion)

        buildConfigurationReportSections(
            database = tmsDatabase,
            invoiceNumber = InvoiceNumberProvider.currentInvoiceNumber(),
            stan = StanProvider.currentStan(),
        ).forEach { section ->
            printer.printLine(dottedSpacer, PrintFontSize.SMALL)
            printer.printWrappedCentered(section.title, PrintFontSize.SMALL)
            section.fields.forEach { field ->
                printer.printWrappedField(field.label, field.value)
            }
        }

        printer.printLine(dottedSpacer, PrintFontSize.SMALL)
        printer.printCentered(GlobalConnectPaymentApplication.instance.getString(R.string.end_of_report), PrintFontSize.LARGE, isBold = false)
        printer.printLine("          ", PrintFontSize.LARGE)

        val listener = OnPrintListener { result ->
            when (result) {
                SdkResult.Success -> Log.d(TAG, "Config report printed successfully")
                else -> Log.e(TAG, "Config report print failed: $result")
            }
        }
        printer.startPrint(true, listener)
    }

    override fun printPinPadKeysReport(context: Context, tmsDatabase: TMSDATA) {
        val deviceEngine = APIProxy.getDeviceEngine(context)
        val printer = deviceEngine.printer
        printer.initPrinter()
        printer.setGray(PRINT_GRAY_LEVEL)
        printer.setTypeface(Typeface.MONOSPACE)

        val terminal = tmsDatabase.Terminal.firstOrNull()
        val primaryAcquirer = tmsDatabase.Acquirer.firstOrNull()
        printHeader(
            context,
            terminal?.MerchantTitle1 ?: "",
            terminal?.MerchantTitle2 ?: "",
            terminal?.MerchantTitle3 ?: "",
            primaryAcquirer?.AcqLine1 ?: "",
            printerObject = printer,
            printLogo = true,
        )

        val now = LocalDateTime.now()
        printer.printCentered("PIN PAD KEY STATUS", PrintFontSize.LARGE, isBold = true)
        printer.printCentered(
            "${now.format(receiptDateFormatter)} ${now.format(reversalTimeFormatter)}",
            PrintFontSize.SMALL,
        )
        printer.printWrappedField("Model", GlobalConnectPaymentApplication.model)
        printer.printWrappedField("Serial number", GlobalConnectPaymentApplication.serialNumber)
        printer.printLine(dottedSpacer, PrintFontSize.SMALL)
        printer.printWrappedCentered("CONFIGURED PIN PROFILES", PrintFontSize.SMALL)

        val configuredProfiles = tmsDatabase.Acquirer.filter { it.supportsOnlinePin }
        if (configuredProfiles.isEmpty()) {
            printer.printLine("No online PIN profiles configured", PrintFontSize.TINY)
        } else {
            configuredProfiles.forEach { acquirer ->
                val slot = acquirer.nexgoPinKeyIndex?.toString() ?: "INVALID"
                printer.printWrappedField(
                    acquirer.AcqID.ifBlank { acquirer.AcquirerName.ifBlank { "Acquirer" } },
                    "${acquirer.pinKeyScheme.name} slot $slot",
                )
            }
        }

        printer.printLine(dottedSpacer, PrintFontSize.SMALL)
        printer.printWrappedCentered("LOADED PED KEYS", PrintFontSize.SMALL)
        val pinPad = deviceEngine.pinPad
        val initResult = runCatching { pinPad.initPinPad(PinPadTypeEnum.INTERNAL) }.getOrNull()
        if (initResult != SdkResult.Success) {
            printer.printWrappedField("PIN pad", "INITIALIZATION FAILED (${initResult ?: "EXCEPTION"})")
        } else {
            val statuses = readPinPadKeyStatuses(
                masterKeyKcv = { index ->
                    runCatching {
                        val zeros = ByteArray(16)
                        pinPad.encryptByMKey(index, zeros, zeros.size)?.copyOfRange(0, 4)?.toHexString()
                    }.getOrNull()
                },
                workingKeyKcv = { index, type ->
                    runCatching { pinPad.calcWKeyKCV(index, type)?.toHexString() }.getOrNull()
                },
                dukptLoaded = { index ->
                    runCatching { pinPad.dukptCurrentKsn(index)?.size == 10 }.getOrDefault(false)
                },
            )
            statuses.forEach { status ->
                printer.printWrappedField(
                    "${status.type} [${status.index}]",
                    status.kcv?.let { "LOADED KCV $it" } ?: status.state,
                )
            }
        }

        printer.printLine(dottedSpacer, PrintFontSize.SMALL)
        printer.printLine("No key values are printed.", PrintFontSize.TINY)
        printer.printCentered(GlobalConnectPaymentApplication.instance.getString(R.string.end_of_report), PrintFontSize.LARGE)
        printer.printLine("          ", PrintFontSize.LARGE)

        val listener = OnPrintListener { result ->
            when (result) {
                SdkResult.Success -> Log.d(TAG, "PIN pad key report printed successfully")
                else -> Log.e(TAG, "PIN pad key report print failed: $result")
            }
        }
        printer.startPrint(true, listener)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
