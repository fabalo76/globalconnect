package one.globalconnect.paymentapp.uicpos.pos.host

import android.content.Context
import android.util.Log
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import java.util.concurrent.atomic.AtomicLong

/**
 * Generates sequential folio numbers for hotel transactions.
 */
object FolioNumberProvider {

    private const val PREFERENCES = "hotel_transactions"
    private const val KEY_FOLIO = "folio_number"
    private const val MAX_FOLIO = 999_999L

    private val cachedFolio = AtomicLong(-1)

    /**
     * Returns the next folio number using the A10-compatible six digit cyclic counter.
     */
    fun nextFolioNumber(): String {
        val value = incrementAndPersist()
        val formatted = value.toString().padStart(6, '0')
        Log.d(TAG, "Generated folio number=$formatted")
        return formatted
    }

    private fun incrementAndPersist(): Long {
        val preferences = GlobalConnectPaymentApplication.instance.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE
        )

        if (cachedFolio.get() < 0) {
            val stored = preferences.getLong(KEY_FOLIO, 0L)
            cachedFolio.compareAndSet(-1, stored)
        }

        val nextValue = if (cachedFolio.incrementAndGet() > MAX_FOLIO) {
            cachedFolio.set(1L)
            1L
        } else {
            cachedFolio.get()
        }

        preferences.edit().putLong(KEY_FOLIO, nextValue).apply()
        return nextValue
    }

    private const val TAG = "FolioNumberProvider"
}
