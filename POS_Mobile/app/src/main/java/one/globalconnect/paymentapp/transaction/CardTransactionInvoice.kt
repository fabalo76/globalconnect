package one.globalconnect.paymentapp.transaction

/** Reserves the invoice before EMV, keeping it stable through pre-authorization card retries. */
internal class CardTransactionInvoice(private val nextInvoice: () -> String) {
    private var reserved: String? = null

    @Synchronized
    fun forCardRead(): String = reserved ?: nextInvoice().also { reserved = it }

    @Synchronized
    fun forHostRequest(): String {
        val invoice = checkNotNull(reserved) { "Invoice must be reserved before reading the card" }
        reserved = null
        return invoice
    }
}
