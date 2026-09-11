package one.globalconnect.paymentapp.cardreader.nexgo

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nexgo.oaf.apiv3.device.beeper.Beeper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.utils.AudioPlaybackDiagnostics

/**
 * Handles card-reader and cardholder-instruction beeper feedback.
 */
class CardReaderBeeper(
    private val beeper: Beeper,
) {
    private val TAG = "CardReaderBeeper"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val beepExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "card-reader-beeper")
    }
    private val SUCCESS_BEEP_DURATION_MS = 1_000
    private val ERROR_BEEP_DURATION_MS = 200
    private val SEE_PHONE_BEEP_DURATION_MS = 1_000
    private val CONTACT_CARD_REQUIRED_BEEP_DURATION_MS = 1_000
    private val SUCCESS_BEEP_DELAY_MS = 150L
    private val ERROR_PAUSE_SHORT_MS = 200L
    private val ERROR_PAUSE_LONG_MS = 300L

    fun onContactlessSuccess() {
        // Let the card-reader callback return before requesting one long hardware beep.
        // Android's TONE_PROP_ACK is intentionally not layered here because it is a
        // two-short-tone pattern and would duplicate the contactless acknowledgement.
        scheduleBeep(
            durationMs = SUCCESS_BEEP_DURATION_MS,
            delayMs = SUCCESS_BEEP_DELAY_MS,
        )
    }

    fun onContactlessError() {
        scheduleBeep(ERROR_BEEP_DURATION_MS)
        scheduleBeep(
            ERROR_BEEP_DURATION_MS,
            ERROR_BEEP_DURATION_MS + ERROR_PAUSE_SHORT_MS,
        )
        scheduleBeep(
            ERROR_BEEP_DURATION_MS,
            (ERROR_BEEP_DURATION_MS * 2) + ERROR_PAUSE_SHORT_MS + ERROR_PAUSE_LONG_MS,
        )
    }

    fun onCardRemovalReminder() {
        beep(ERROR_BEEP_DURATION_MS)
    }

    fun onSeePhoneInstruction() {
        beep(SEE_PHONE_BEEP_DURATION_MS)
    }

    /** Alerts the cardholder that the transaction must continue using the ICC reader. */
    fun onContactCardRequired() {
        scheduleBeep(
            durationMs = CONTACT_CARD_REQUIRED_BEEP_DURATION_MS,
        )
    }

    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        beepExecutor.shutdownNow()
    }

    private fun scheduleBeep(
        durationMs: Int,
        delayMs: Long = 0L,
    ) {
        Log.d(TAG, "Scheduling beeper duration=${durationMs}ms delay=${delayMs}ms")
        mainHandler.postDelayed(
            {
                beepExecutor.execute {
                    beep(durationMs)
                }
            },
            delayMs,
        )
    }

    private fun beep(durationMs: Int) {
        try {
            Log.d(TAG, "Requesting beeper duration=${durationMs}ms")
            AudioPlaybackDiagnostics.log(GlobalConnectPaymentApplication.instance, "Card reader beep duration=${durationMs}ms")
            beeper.beep(durationMs)
            Log.d(TAG, "Beeper request returned duration=${durationMs}ms")
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to trigger beeper", error)
        }
    }

}
