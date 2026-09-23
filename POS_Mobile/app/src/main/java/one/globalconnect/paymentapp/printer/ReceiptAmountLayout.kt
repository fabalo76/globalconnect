package one.globalconnect.paymentapp.printer

internal object ReceiptAmountLayout {
    fun needsTotal(tax1: Boolean, tax2: Boolean, tip: Boolean, openTip: Boolean): Boolean =
        tax1 || tax2 || tip || openTip

    fun splitAmountLine(label: String, amount: String, lineWidth: Int): Boolean =
        label.length + 1 + amount.length > lineWidth
}
