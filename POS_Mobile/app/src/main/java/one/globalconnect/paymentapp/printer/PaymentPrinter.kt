package one.globalconnect.paymentapp.printer

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.transaction.BatchSummary
import one.globalconnect.paymentapp.transaction.PrintableTotalsReport
import one.globalconnect.paymentapp.transaction.ReversalReceiptData
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.settlement.storage.SettlementSnapshot

const val MERCHANT = "MERCHANT"
const val CUSTOMER = "CUSTOMER"
interface PaymentPrinter {

    fun printerTest(context: Context)

    fun <T> printHeader(
        context: Context,
        headerTitle1 : String,
        headerTitle2 : String,
        headerTitle3 : String,
        headerTitle4 : String,
        printerObject : T,
        printLogo : Boolean
    )
        fun printReport(
        context: Context,
        transactions: List<Transaction>,
        printTransactions: Boolean,
        profile: Profile,
        tmsDatabase: TMSDATA,
        onPrintResult: ((Boolean) -> Unit)? = null,
    )

    fun printBatchReport(
        context: Context,
        queryList: List<Transaction>,
        batchSummary: BatchSummary,
        profile: Profile
    )

    fun printTotalsReport(
        context: Context,
        report: PrintableTotalsReport,
        profile: Profile?,
        tmsDatabase: TMSDATA,
        onPrintResult: ((Boolean) -> Unit)? = null,
    )

    fun printReceipt(
        context: Context,
        transaction: Transaction,
        profile: Profile,
        tmsDatabase: TMSDATA,
        bitmap: ImageBitmap? = null,
        recipient: String = MERCHANT,
        onPrintResult: ((Boolean) -> Unit)? = null,
        onPrintStatus: ((Int) -> Unit)? = null,
    )

    fun printReversalReceipt(
        context: Context,
        profile: Profile?,
        tmsDatabase: TMSDATA,
        data: ReversalReceiptData,
    )

    fun printSettlementReceipt(
        context: Context,
        snapshot: SettlementSnapshot,
        tmsDatabase: TMSDATA,
        includeAudit: Boolean = true,
        onPrintResult: ((Boolean) -> Unit)? = null,
    )

    fun printConfigReport(
        context: Context,
        tmsDatabase: TMSDATA,
    )

    fun printPinPadKeysReport(
        context: Context,
        tmsDatabase: TMSDATA,
    )
}
