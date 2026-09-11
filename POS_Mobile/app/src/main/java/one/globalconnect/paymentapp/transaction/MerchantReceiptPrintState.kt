package one.globalconnect.paymentapp.transaction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MerchantReceiptPrintStatus { READY, PRINTING, PRINTED }

internal class MerchantReceiptPrintState(initiallyPrinted: Boolean) {
    private val mutableStatus = MutableStateFlow(
        if (initiallyPrinted) MerchantReceiptPrintStatus.PRINTED else MerchantReceiptPrintStatus.READY,
    )
    val status = mutableStatus.asStateFlow()

    fun tryStart(): Boolean = mutableStatus.compareAndSet(
        MerchantReceiptPrintStatus.READY, MerchantReceiptPrintStatus.PRINTING,
    )

    fun complete(success: Boolean) {
        if (success) mutableStatus.value = MerchantReceiptPrintStatus.PRINTED
        else mutableStatus.compareAndSet(MerchantReceiptPrintStatus.PRINTING, MerchantReceiptPrintStatus.READY)
    }
}
