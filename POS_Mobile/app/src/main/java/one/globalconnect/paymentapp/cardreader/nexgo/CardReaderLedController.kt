package one.globalconnect.paymentapp.cardreader.nexgo

import android.util.Log
import com.nexgo.oaf.apiv3.device.led.LightModeEnum
import com.nexgo.oaf.apiv3.device.led.LEDDriver
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Handles the contactless status LEDs and the optional decorative light present on
 * the Nexgo N96 terminals. The behaviour mirrors the legacy .NET implementation so the
 * user receives the same visual feedback during card search and EMV processing.
 */
internal class CardReaderLedController(
    private val ledDriver: LEDDriver?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deviceModelProvider: () -> String = { GlobalConnectPaymentApplication.model },
) {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val supportsDecorativeLight: Boolean by lazy {
        ledDriver != null && runCatching { deviceModelProvider() }
            .getOrNull()
            ?.equals("N96", ignoreCase = true) == true
    }

    private var idleJob: Job? = null
    private var msrJob: Job? = null

    /**
     * Places the contactless LED ring into its idle animation. When [preserveCurrentState]
     * is true the controller keeps the currently lit LEDs until the first idle pulse occurs.
     */
    fun enterIdle(preserveCurrentState: Boolean = false) {
        if (ledDriver == null) {
            Log.d(TAG, "enterIdle skipped; LED driver unavailable")
            return
        }
        cancelIdleJob()
        stopMsrJob()
        idleJob = scope.launch {
            if (!preserveCurrentState) {
                setContactlessLights(false, false, false, false)
            }
            while (isActive) {
                delay(IDLE_SLEEP_MS)
                setContactlessLights(blue = true)
                delay(IDLE_FLASH_MS)
                setContactlessLights(false, false, false, false)
            }
        }
    }

    /** Prepares the LED state before invoking a new card search. */
    fun prepareForTransaction(allowContactless: Boolean, allowSwipe: Boolean) {
        if (ledDriver == null) {
            Log.d(TAG, "prepareForTransaction skipped; LED driver unavailable")
            return
        }
        cancelIdleJob()
        if (allowContactless || allowSwipe) {
            startMsrBlink()
        } else {
            stopMsrJob()
        }
        if (allowContactless) {
            setContactlessLights(blue = true)
        } else {
            setContactlessLights(false, false, false, false)
        }
    }

    /** Invoked when the SDK reports card data. */
    fun onCardDetected(slot: CardSlotTypeEnum?) {
        stopMsrJob()
        if (slot == CardSlotTypeEnum.RF) {
            setContactlessLights(blue = true, yellow = true)
        }
    }

    /** Lights the green LED to indicate the card was read successfully. */
    fun onOnlineProcessing() {
        setContactlessLights(blue = true, yellow = true, green = true)
    }

    /** Highlights an error condition by illuminating the red LED. */
    fun onError() {
        cancelIdleJob()
        stopMsrJob()
        setContactlessLights(red = true)
    }

    /** Called when the transaction is cancelled by the caller. */
    fun onTransactionCancelled() {
        enterIdle()
    }

    /** Called when the EMV flow completes. */
    fun onTransactionFinished() {
        enterIdle(preserveCurrentState = true)
    }

    /** Ensures LEDs are turned off and jobs cancelled when the API is destroyed. */
    fun shutdown() {
        cancelIdleJob()
        stopMsrJob()
        setContactlessLights(false, false, false, false)
        scope.cancel()
    }

    private fun setContactlessLights(
        blue: Boolean = false,
        yellow: Boolean = false,
        green: Boolean = false,
        red: Boolean = false,
    ) {
        if (ledDriver == null) return
        runCatching {
            ledDriver?.setLed(LightModeEnum.BLUE, blue)
            ledDriver?.setLed(LightModeEnum.YELLOW, yellow)
            ledDriver?.setLed(LightModeEnum.GREEN, green)
            ledDriver?.setLed(LightModeEnum.RED, red)
        }.onFailure { error ->
            Log.w(TAG, "Failed to update contactless LEDs", error)
        }
    }

    private fun startMsrBlink() {
        if (!supportsDecorativeLight) {
            Log.d(TAG, "startMsrBlink skipped; decorative light unsupported")
            return
        }
        if (msrJob?.isActive == true) {
            return
        }
        msrJob = scope.launch {
            try {
                while (isActive) {
                    setDecorativeLight(true)
                    delay(MSR_BLINK_ON_MS)
                    setDecorativeLight(false)
                    delay(MSR_BLINK_OFF_MS)
                }
            } finally {
                setDecorativeLight(false)
            }
        }
    }

    private fun stopMsrJob() {
        msrJob?.cancel()
        msrJob = null
        // Cancellation is non-blocking, and startMsrBlink's finally block turns the light off on
        // the controller's I/O dispatcher. Calling the Nexgo LED driver here would run hardware
        // Binder IPC on the caller (usually the main thread) and can cause an input-timeout ANR.
    }

    private fun cancelIdleJob() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun setDecorativeLight(enabled: Boolean) {
        if (!supportsDecorativeLight || ledDriver == null) return
        runCatching {
            ledDriver?.setLed(LightModeEnum.DECORATIVE_LIGHT, enabled)
        }.onFailure { error ->
            Log.w(TAG, "Failed to toggle decorative light", error)
        }
    }

    companion object {
        private const val TAG = "CardReaderLedCtrl"
        private const val IDLE_SLEEP_MS = 5_000L
        private const val IDLE_FLASH_MS = 200L
        private const val MSR_BLINK_ON_MS = 500L
        private const val MSR_BLINK_OFF_MS = 500L
    }
}
