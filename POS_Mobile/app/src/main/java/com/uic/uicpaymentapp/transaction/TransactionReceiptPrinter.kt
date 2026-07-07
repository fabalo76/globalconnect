package com.uic.uicpaymentapp.transaction

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.printer.CUSTOMER
import com.uic.uicpaymentapp.printer.NexGoPaymentPrinter
import com.uic.uicpaymentapp.printer.PaymentPrinter
import com.uic.uicpaymentapp.profile.Profile
import com.uic.uicpaymentapp.profile.profiledao.ProfileRepository
import com.uic.uicpaymentapp.signature.SignatureRepository
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
    context: Context = UICApplication.instance.applicationContext,
) {
    withContext(Dispatchers.IO) {
        val profile = profileRepository.get() ?: Profile()
        val signature = signatureRepository.getSignatureFromTransactionId(transaction.id.toString())

        if (signature == null) {
            paymentPrinter.printReceipt(
                transaction = transaction,
                profile = profile,
                tmsDatabase = UICApplication.instance.tmsDatabase,
                bitmap = null,
                recipient = CUSTOMER,
                context = context,
            )
        } else {
            try {
                val file = File(UICApplication.instance.filesDir, signature.signatureUUID)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                paymentPrinter.printReceipt(
                    transaction = transaction,
                    profile = profile,
                    tmsDatabase = UICApplication.instance.tmsDatabase,
                    bitmap = bitmap,
                    recipient = CUSTOMER,
                    context = context,
                )
            } catch (error: IOException) {
                paymentPrinter.printReceipt(
                    transaction = transaction,
                    profile = profile,
                    tmsDatabase = UICApplication.instance.tmsDatabase,
                    bitmap = null,
                    recipient = CUSTOMER,
                    context = context,
                )
            }
        }
    }
}

