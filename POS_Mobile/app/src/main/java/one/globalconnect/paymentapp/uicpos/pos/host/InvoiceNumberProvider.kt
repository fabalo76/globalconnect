package one.globalconnect.paymentapp.uicpos.pos.host

import android.content.Context
import android.util.Log
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates sequential invoice identifiers using a six digit cyclic counter.
 */
object InvoiceNumberProvider {

    private const val PREFERENCES = "host_protocol"
    private const val KEY_INVOICE = "invoice_number"

    private val cachedInvoice = AtomicInteger(-1)

    fun setInvoiceValue(value: Int) {
        val clamped = value.coerceIn(1, 999_999)
        cachedInvoice.set(clamped)
        GlobalConnectPaymentApplication.instance.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putInt(KEY_INVOICE, clamped).apply()
        Log.d(TAG, "Invoice manually set to ${String.format("%06d", clamped)}")
    }

    fun currentInvoiceNumber(): String {
        val preferences = GlobalConnectPaymentApplication.instance.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val current = if (cachedInvoice.get() >= 0) cachedInvoice.get() else preferences.getInt(KEY_INVOICE, 1)
        return String.format("%06d", maxOf(1, current))
    }

    /**
     * Returns the next invoice identifier in a cyclic range between 000001 and 999999.
     */
    fun nextInvoiceNumber(): String {
        val nextValue = incrementAndPersist()
        val invoiceNumber = String.format("%06d", nextValue)
        Log.d(TAG, "Generated invoice number=$invoiceNumber")
        return invoiceNumber
    }

    private fun incrementAndPersist(): Int {
        val preferences = GlobalConnectPaymentApplication.instance.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE
        )

        if (cachedInvoice.get() < 0) {
            val stored = preferences.getInt(KEY_INVOICE, 0)
            cachedInvoice.compareAndSet(-1, stored)
        }

        val updated = if (cachedInvoice.incrementAndGet() > 999_999) {
            cachedInvoice.set(1)
            1
        } else {
            cachedInvoice.get()
        }

        preferences.edit().putInt(KEY_INVOICE, updated).apply()
        return updated
    }

    private const val TAG = "InvoiceNumberProvider"
}
