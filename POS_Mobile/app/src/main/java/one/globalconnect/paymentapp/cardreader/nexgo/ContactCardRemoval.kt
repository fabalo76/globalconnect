package one.globalconnect.paymentapp.cardreader.nexgo

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "ContactCardRemoval"
private const val CARD_REMOVAL_POLL_INTERVAL_MS = 250L
private const val CARD_REMOVAL_BEEP_INTERVAL_MS = 3_000L

/** Waits for the customer-facing ICC1 slot to become empty. */
suspend fun NexgoApi.awaitContactCardRemoval(
    onRemovalRequiredChanged: (Boolean) -> Unit,
) {
    var promptVisible = false
    var nextReminderAt = 0L
    while (withContext(Dispatchers.IO) { isContactCardInserted() }) {
        if (!promptVisible) {
            Log.d(TAG, "Contact card remains inserted; requesting removal")
            promptVisible = true
            onRemovalRequiredChanged(true)
        }
        val now = SystemClock.elapsedRealtime()
        if (now >= nextReminderAt) {
            withContext(Dispatchers.IO) { beepCardRemovalReminder() }
            nextReminderAt = now + CARD_REMOVAL_BEEP_INTERVAL_MS
        }
        delay(CARD_REMOVAL_POLL_INTERVAL_MS)
    }
    if (promptVisible) {
        Log.d(TAG, "Contact card removal detected")
        onRemovalRequiredChanged(false)
    }
}
