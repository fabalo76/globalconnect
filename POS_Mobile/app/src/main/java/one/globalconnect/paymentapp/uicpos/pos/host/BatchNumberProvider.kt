package one.globalconnect.paymentapp.uicpos.pos.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates sequential batch numbers separated by acquirer identifier.
 */
object BatchNumberProvider {

    private const val PREFERENCES = "host_protocol"
    private const val KEY_PREFIX = "batch_number_"
    private const val UNINITIALIZED = -1
    private const val MAX_VALUE = 999_999

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Returns the next batch number for the supplied [acquirerId].
     *
     * The sequence is maintained independently per acquirer and wraps back to
     * `000001` after exceeding `999999`. When the counter is initialised it will
     * honour the provided [initialValue] if it is greater than the currently
     * stored value.
     */
    fun nextBatchNumber(acquirerId: String, initialValue: Int?): String {
        val current = currentBatchNumber(acquirerId, initialValue)
        advanceBatchNumber(acquirerId)
        return current
    }

    fun currentBatchNumber(acquirerId: String, initialValue: Int?): String {
        require(acquirerId.isNotBlank()) { "Acquirer identifier must not be blank" }

        val key = sanitizeKey(acquirerId)
        val preferences = preferences()
        val counter = counters.getOrPut(key) { AtomicInteger(UNINITIALIZED) }

        initialiseCounter(key, counter, preferences)

        val normalisedInitial = initialValue?.coerceIn(1, MAX_VALUE)
        val resolved = when {
            normalisedInitial != null && counter.get() < normalisedInitial -> {
                counter.set(normalisedInitial)
                normalisedInitial
            }

            counter.get() <= 0 -> {
                val fallback = normalisedInitial ?: 1
                counter.set(fallback)
                fallback
            }

            else -> counter.get()
        }

        persistValue(key, resolved, preferences)

        val formatted = format(resolved)
        Log.d(TAG, "Resolved current batch number=$formatted for acquirer=$key")
        return formatted
    }

    fun setBatchNumber(acquirerId: String, value: Int) {
        require(acquirerId.isNotBlank()) { "Acquirer identifier must not be blank" }
        val key = sanitizeKey(acquirerId)
        val clamped = value.coerceIn(1, MAX_VALUE)
        val counter = counters.getOrPut(key) { AtomicInteger(clamped) }
        counter.set(clamped)
        persistValue(key, clamped, preferences())
        Log.d(TAG, "Batch number manually set to ${format(clamped)} for acquirer=$key")
    }

    fun advanceBatchNumber(acquirerId: String) {
        require(acquirerId.isNotBlank()) { "Acquirer identifier must not be blank" }

        val key = sanitizeKey(acquirerId)
        val preferences = preferences()
        val counter = counters.getOrPut(key) { AtomicInteger(UNINITIALIZED) }

        initialiseCounter(key, counter, preferences)

        if (counter.get() < 0) {
            counter.set(0)
        }

        val updated = increment(counter)
        persistValue(key, updated, preferences)

        Log.d(TAG, "Advanced batch number=${format(updated)} for acquirer=$key")
    }

    private fun initialiseCounter(
        key: String,
        counter: AtomicInteger,
        preferences: SharedPreferences,
    ) {
        if (counter.get() == UNINITIALIZED) {
            val stored = preferences.getInt(preferencesKey(key), 0)
            counter.compareAndSet(UNINITIALIZED, stored)
        }
    }

    private fun persistValue(key: String, value: Int, preferences: SharedPreferences) {
        preferences.edit().putInt(preferencesKey(key), value).apply()
    }

    private fun increment(counter: AtomicInteger): Int {
        val updated = counter.incrementAndGet()
        if (updated > MAX_VALUE) {
            counter.set(1)
            return 1
        }
        return updated
    }

    private fun preferences() = GlobalConnectPaymentApplication.instance.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )

    private fun preferencesKey(acquirerKey: String) = KEY_PREFIX + acquirerKey

    private fun sanitizeKey(acquirerId: String): String = acquirerId.trim().uppercase(Locale.ROOT)

    private fun format(value: Int): String = String.format(Locale.US, "%06d", value)

    private const val TAG = "BatchNumberProvider"
}
