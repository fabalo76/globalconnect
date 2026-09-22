package one.globalconnect.paymentapp.transaction

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.printer.CUSTOMER
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.signature.SignatureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Helper responsible for printing receipts for a [Transaction]. The logic mirrors the
 * implementation used by the transaction details screen so that other features (for example the
 * hotel check-in report) can trigger reprints without duplicating the underlying code.
 */
suspend fun printTransactionReceipt(
    transaction: Transaction,
    profileRepository: ProfileRepository,
    signatureRepository: SignatureRepository,
    paymentPrinter: PaymentPrinter = NexGoPaymentPrinter,
    context: Context = GlobalConnectPaymentApplication.instance.applicationContext,
    onPrintResult: ((Boolean) -> Unit)? = null,
) {
    withContext(Dispatchers.IO) {
        val profile = profileRepository.get() ?: Profile()
        val signature = signatureRepository.getSignatureFromTransactionId(transaction.id.toString())

        if (signature == null) {
            paymentPrinter.printReceipt(
                transaction = transaction,
                profile = profile,
                tmsDatabase = GlobalConnectPaymentApplication.instance.tmsDatabase,
                bitmap = null,
                recipient = CUSTOMER,
                context = context,
                onPrintResult = onPrintResult,
            )
        } else {
            try {
                val file = File(GlobalConnectPaymentApplication.instance.filesDir, signature.signatureUUID)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                paymentPrinter.printReceipt(
                    transaction = transaction,
                    profile = profile,
                    tmsDatabase = GlobalConnectPaymentApplication.instance.tmsDatabase,
                    bitmap = bitmap,
                    recipient = CUSTOMER,
                    context = context,
                    onPrintResult = onPrintResult,
                )
            } catch (error: IOException) {
                paymentPrinter.printReceipt(
                    transaction = transaction,
                    profile = profile,
                    tmsDatabase = GlobalConnectPaymentApplication.instance.tmsDatabase,
                    bitmap = null,
                    recipient = CUSTOMER,
                    context = context,
                    onPrintResult = onPrintResult,
                )
            }
        }
    }
}

