package one.globalconnect.pinpad.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.model.PinpadTransactionDisplay

object PinpadDisplayController {
    private val mainHandler = Handler(Looper.getMainLooper())

    var state: PinpadDisplayState by mutableStateOf(PinpadDisplayState.Idle)
        private set

    var idleMessage: String? by mutableStateOf(null)
        private set

    var contactlessLedState: ContactlessLedState by mutableStateOf(ContactlessLedState.Off)
        private set

    fun updateIdleMessage(value: String?) {
        idleMessage = value?.takeIf { it.isNotBlank() }
        showIdle()
    }

    fun showIdle() {
        clearContactlessLeds()
        updateState(PinpadDisplayState.Idle)
    }

    fun showSwipeCard(transaction: PinpadTransactionDisplay? = null) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.SwipeCard(transaction))
    }

    fun showInsertCard(transaction: PinpadTransactionDisplay? = null) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.InsertCard(transaction))
    }

    fun showTapCard(transaction: PinpadTransactionDisplay? = null) {
        updateState(PinpadDisplayState.TapCard(transaction))
    }

    fun showPresentCard(transaction: PinpadTransactionDisplay? = null) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.PresentCard(transaction))
    }

    fun showMessage(text: String) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.Message(text))
    }

    fun showMessageThenIdle(text: String) {
        clearContactlessLeds()
        val messageState = PinpadDisplayState.Message(text)
        updateState(messageState)
        mainHandler.postDelayed({
            if (state == messageState) {
                state = PinpadDisplayState.Idle
            }
        }, THANK_YOU_MS)
    }

    fun showTextEntry(prompt: String, text: String, echoMode: TextEntryEchoMode) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.TextEntry(prompt, text, echoMode))
    }

    fun showApplicationSelection(labels: List<String>, onSelected: (Int) -> Unit) {
        clearContactlessLeds()
        updateState(
            PinpadDisplayState.ApplicationSelection(
                labels = labels,
                selectedIndex = 0,
                onSelected = onSelected,
            ),
        )
    }

    fun moveApplicationSelection(delta: Int): Boolean {
        val move = {
            val current = state
            if (current is PinpadDisplayState.ApplicationSelection && current.labels.isNotEmpty()) {
                val size = current.labels.size
                val next = (current.selectedIndex + delta + size) % size
                PinpadTraceLog.device("application selection move delta=$delta from=${current.selectedIndex} to=$next")
                state = current.copy(selectedIndex = next)
                true
            } else {
                false
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            move()
        } else {
            mainHandler.post { move() }
            state is PinpadDisplayState.ApplicationSelection
        }
    }

    fun confirmApplicationSelection(): Boolean {
        val confirm = {
            val current = state as? PinpadDisplayState.ApplicationSelection
            if (current != null) {
                val selectedIndex = current.selectedIndex.coerceIn(0, current.labels.lastIndex)
                selectApplicationLocked(current, selectedIndex)
            } else {
                false
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            confirm()
        } else {
            val active = state is PinpadDisplayState.ApplicationSelection
            if (active) mainHandler.post { confirm() }
            active
        }
    }

    fun selectApplication(index: Int): Boolean {
        val select = {
            val current = state as? PinpadDisplayState.ApplicationSelection
            if (current != null) {
                selectApplicationLocked(current, index.coerceIn(0, current.labels.lastIndex))
            } else {
                false
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            select()
        } else {
            val active = state is PinpadDisplayState.ApplicationSelection
            if (active) mainHandler.post { select() }
            active
        }
    }

    private fun selectApplicationLocked(
        current: PinpadDisplayState.ApplicationSelection,
        selectedIndex: Int,
    ): Boolean {
        val appName = current.labels.getOrElse(selectedIndex) { "" }
        val selectingState = PinpadDisplayState.SelectingApplication(appName)
        updateState(selectingState)
        mainHandler.postDelayed({
            if (state == selectingState) {
                state = PinpadDisplayState.Idle
            }
        }, SELECTING_APPLICATION_MS)
        current.onSelected(selectedIndex)
        return true
    }

    fun updateTextEntry(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val current = state
            if (current is PinpadDisplayState.TextEntry) {
                state = current.copy(text = text)
            }
        } else {
            mainHandler.post {
                val current = state
                if (current is PinpadDisplayState.TextEntry) {
                    state = current.copy(text = text)
                }
            }
        }
    }

    fun showEnterPin(promptLines: List<String> = emptyList()) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.EnterPin(promptLines = promptLines.filter { it.isNotBlank() }))
    }

    fun updatePinDigits(count: Int) {
        val next = count.coerceAtLeast(0)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val current = state
            if (current is PinpadDisplayState.EnterPin) {
                state = current.copy(digits = next)
            }
        } else {
            mainHandler.post {
                val current = state
                if (current is PinpadDisplayState.EnterPin) {
                    state = current.copy(digits = next)
                }
            }
        }
    }

    fun showProcessing() {
        updateState(PinpadDisplayState.Processing)
        mainHandler.postDelayed({
            if (state == PinpadDisplayState.Processing) {
                clearContactlessLeds()
                state = PinpadDisplayState.Idle
            }
        }, PROCESSING_MS)
    }

    fun showBadReadThenSwipe() {
        updateState(PinpadDisplayState.BadRead)
        mainHandler.postDelayed({
            if (state == PinpadDisplayState.BadRead) {
                clearContactlessLeds()
                state = PinpadDisplayState.SwipeCard()
            }
        }, BAD_READ_MS)
    }

    fun showBadReadThenIdle() {
        updateState(PinpadDisplayState.BadRead)
        mainHandler.postDelayed({
            if (state == PinpadDisplayState.BadRead) {
                clearContactlessLeds()
                state = PinpadDisplayState.Idle
            }
        }, BAD_READ_MS)
    }

    fun showDeclinedThenIdle() {
        updateState(PinpadDisplayState.Declined)
        mainHandler.postDelayed({
            if (state == PinpadDisplayState.Declined) {
                clearContactlessLeds()
                state = PinpadDisplayState.Idle
            }
        }, ERROR_MESSAGE_MS)
    }

    fun showIccFallbackThenIdle() {
        clearContactlessLeds()
        updateState(PinpadDisplayState.IccFallback)
        mainHandler.postDelayed({
            if (state == PinpadDisplayState.IccFallback) {
                state = PinpadDisplayState.Idle
            }
        }, ICC_FALLBACK_MESSAGE_MS)
    }

    fun showOperationCancelledThenIdle() {
        clearContactlessLeds()
        updateState(PinpadDisplayState.OperationCancelled)
        mainHandler.postDelayed({
            if (state == PinpadDisplayState.OperationCancelled) {
                state = PinpadDisplayState.Idle
            }
        }, ERROR_MESSAGE_MS)
    }

    fun showPinKeySchemeErrorThenIdle(keyId: Char) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.PinKeySchemeError(keyId.uppercaseChar()))
        mainHandler.postDelayed({
            if (state is PinpadDisplayState.PinKeySchemeError) {
                state = PinpadDisplayState.Idle
            }
        }, ERROR_MESSAGE_MS)
    }

    fun showKeyMetadataMissingThenIdle(keyId: Char) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.KeyMetadataMissing(keyId.uppercaseChar()))
        mainHandler.postDelayed({
            if (state is PinpadDisplayState.KeyMetadataMissing) {
                state = PinpadDisplayState.Idle
            }
        }, ERROR_MESSAGE_MS)
    }

    fun showThankYouThenIdle() {
        clearContactlessLeds()
        updateState(PinpadDisplayState.ThankYou)
        mainHandler.postDelayed({ state = PinpadDisplayState.Idle }, THANK_YOU_MS)
    }

    fun showJpeg(path: String) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.Jpeg(path))
    }

    fun showJpegSequence(paths: List<String>) {
        if (paths.isNotEmpty()) {
            clearContactlessLeds()
            updateState(PinpadDisplayState.JpegSequence(paths))
        }
    }

    fun showBrandSensory(brand: SensoryBrand) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.BrandSensory(brand))
    }

    fun showContactlessLeds(next: ContactlessLedState) {
        PinpadTraceLog.device("display contactlessLeds=$next")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            contactlessLedState = next
        } else {
            mainHandler.post { contactlessLedState = next }
        }
    }

    fun clearContactlessLeds() {
        if (contactlessLedState == ContactlessLedState.Off) return
        showContactlessLeds(ContactlessLedState.Off)
    }

    private fun updateState(next: PinpadDisplayState) {
        PinpadTraceLog.device("display state=$next")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            state = next
        } else {
            mainHandler.post { state = next }
        }
    }

    private const val BAD_READ_MS = 1_500L
    private const val ERROR_MESSAGE_MS = 3_000L
    private const val ICC_FALLBACK_MESSAGE_MS = 5_000L
    private const val SELECTING_APPLICATION_MS = 5_000L
    private const val PROCESSING_MS = 3_000L
    private const val THANK_YOU_MS = 3_000L
}

sealed interface PinpadDisplayState {
    data object Idle : PinpadDisplayState
    data class SwipeCard(val transaction: PinpadTransactionDisplay? = null) : PinpadDisplayState
    data class InsertCard(val transaction: PinpadTransactionDisplay? = null) : PinpadDisplayState
    data class TapCard(val transaction: PinpadTransactionDisplay? = null) : PinpadDisplayState
    data class PresentCard(val transaction: PinpadTransactionDisplay? = null) : PinpadDisplayState
    data class Message(val text: String) : PinpadDisplayState
    data class TextEntry(
        val prompt: String,
        val text: String = "",
        val echoMode: TextEntryEchoMode = TextEntryEchoMode.Masked,
    ) : PinpadDisplayState
    data class ApplicationSelection(
        val labels: List<String>,
        val selectedIndex: Int,
        val onSelected: (Int) -> Unit,
    ) : PinpadDisplayState
    data class SelectingApplication(val label: String) : PinpadDisplayState
    data class EnterPin(
        val digits: Int = 0,
        val promptLines: List<String> = emptyList(),
    ) : PinpadDisplayState
    data object Processing : PinpadDisplayState
    data object BadRead : PinpadDisplayState
    data object Declined : PinpadDisplayState
    data object IccFallback : PinpadDisplayState
    data object OperationCancelled : PinpadDisplayState
    data class PinKeySchemeError(val keyId: Char) : PinpadDisplayState
    data class KeyMetadataMissing(val keyId: Char) : PinpadDisplayState
    data object ThankYou : PinpadDisplayState
    data class Jpeg(val path: String) : PinpadDisplayState
    data class JpegSequence(val paths: List<String>) : PinpadDisplayState
    data class BrandSensory(val brand: SensoryBrand) : PinpadDisplayState
}

enum class SensoryBrand {
    Visa,
    Mastercard,
}

enum class TextEntryEchoMode {
    Masked,
    Plain,
    Hidden,
}

enum class ContactlessLedState(
    val blue: Boolean,
    val yellow: Boolean,
    val green: Boolean,
    val red: Boolean,
) {
    Off(false, false, false, false),
    Ready(true, false, false, false),
    Detected(true, true, false, false),
    Processing(true, true, true, false),
    Error(false, false, false, true),
}
