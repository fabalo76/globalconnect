package one.globalconnect.paymentapp.uicpos.pos.host

import android.content.Context
import android.util.Log
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates sequential values for the system trace audit number (STAN).
 */
object StanProvider {

    private const val PREFERENCES = "host_protocol"
    private const val KEY_STAN = "stan"

    private val cachedStan = AtomicInteger(-1)

    fun setStanValue(value: Int) {
        val clamped = value.coerceIn(1, 999_999)
        cachedStan.set(clamped)
        GlobalConnectPaymentApplication.instance.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putInt(KEY_STAN, clamped).apply()
        Log.d(TAG, "STAN manually set to ${String.format("%06d", clamped)}")
    }

    fun currentStan(): String {
        val preferences = GlobalConnectPaymentApplication.instance.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val current = if (cachedStan.get() >= 0) cachedStan.get() else preferences.getInt(KEY_STAN, 1)
        return String.format("%06d", maxOf(1, current))
    }

    /**
     * Returns the next STAN in a cyclic range between 000001 and 999999.
     */
    fun nextStan(): String {
        val nextValue = incrementAndPersist()
        val stan = String.format("%06d", nextValue)
        Log.d(TAG, "Generated STAN=$stan")
        return stan
    }

    private fun incrementAndPersist(): Int {
        val preferences = GlobalConnectPaymentApplication.instance.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE
        )

        if (cachedStan.get() < 0) {
            val stored = preferences.getInt(KEY_STAN, 0)
            cachedStan.compareAndSet(-1, stored)
        }

        val updated = if (cachedStan.incrementAndGet() > 999_999) {
            cachedStan.set(1)
            1
        } else {
            cachedStan.get()
        }

        preferences.edit().putInt(KEY_STAN, updated).apply()
        return updated
    }

    private const val TAG = "StanProvider"
}
