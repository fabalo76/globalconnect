package one.globalconnect.pinpad.ui

import one.globalconnect.pinpad.protocol.QkDetectionRequest

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.model.PinpadTransactionDisplay
import one.globalconnect.pinpad.protocol.PinpadKeypadKey
import one.globalconnect.pinpad.protocol.CameraFacing
import one.globalconnect.pinpad.protocol.PhotoCaptureResult
import one.globalconnect.pinpad.protocol.QrDisplayResult
import one.globalconnect.pinpad.protocol.QrScanResult
import one.globalconnect.pinpad.protocol.SignatureCaptureResult
import one.globalconnect.pinpad.protocol.SignatureImageFormat
import one.globalconnect.pinpad.protocol.SignatureOrientation

object PinpadDisplayController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSensoryThankYou: PinpadDisplayState.BrandSensory? = null

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

    fun showPresentCard(transaction: PinpadTransactionDisplay? = null, chipEnabled: Boolean = true, options: QkDetectionRequest = QkDetectionRequest(if (chipEnabled) "111" else "101")) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.PresentCard(transaction, options))
    }

    fun showMessage(text: String) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.Message(text))
    }

    fun showMessageThenIdle(
        text: String,
        durationMillis: Long = THANK_YOU_MS,
        preserveOnTransactionComplete: Boolean = false,
    ) {
        showTemporaryMessage(
            text,
            PinpadDisplayState.Idle,
            durationMillis,
            preserveOnTransactionComplete,
        )
    }

    fun showMessageThenKeyInjectionMode(text: String) {
        showTemporaryMessage(text, PinpadDisplayState.KeyInjectionMode, THANK_YOU_MS)
    }

    private fun showTemporaryMessage(
        text: String,
        returnState: PinpadDisplayState,
        durationMillis: Long,
        preserveOnTransactionComplete: Boolean = false,
    ) {
        clearContactlessLeds()
        val messageState = PinpadDisplayState.Message(text, preserveOnTransactionComplete)
        updateState(messageState)
        mainHandler.postDelayed({
            if (state == messageState) {
                updateState(returnState)
            }
        }, durationMillis.coerceAtLeast(0L))
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

    fun showKeyLoadAuthentication(
        commandId: String,
        password1Digits: Int,
        password2Digits: Int,
        activePassword: Int,
        useOnScreenKeypad: Boolean,
        message: KeyLoadAuthenticationMessage?,
        onKey: (PinpadKeypadKey) -> Unit,
        onSubmitPasswords: (String, String) -> Unit,
    ) {
        clearContactlessLeds()
        updateState(
            PinpadDisplayState.KeyLoadAuthentication(
                commandId = commandId,
                password1Digits = password1Digits,
                password2Digits = password2Digits,
                activePassword = activePassword,
                useOnScreenKeypad = useOnScreenKeypad,
                message = message,
                onKey = onKey,
                onSubmitPasswords = onSubmitPasswords,
            ),
        )
    }

    fun showKeyInjectionMode() {
        clearContactlessLeds()
        updateState(PinpadDisplayState.KeyInjectionMode)
    }

    fun showSignatureCapture(
        timeoutSeconds: Int,
        orientation: SignatureOrientation,
        imageFormat: SignatureImageFormat,
        onResult: (SignatureCaptureResult) -> Unit,
    ): Boolean {
        val show = {
            if (state.isVisualOperation()) {
                false
            } else {
                clearContactlessLeds()
                val captureState = PinpadDisplayState.SignatureCapture(
                    timeoutSeconds = timeoutSeconds,
                    orientation = orientation,
                    imageFormat = imageFormat,
                    onResult = onResult,
                )
                updateState(captureState)
                mainHandler.postDelayed({
                    if (state === captureState) {
                        PinpadTraceLog.device("signature capture timed out")
                        state = PinpadDisplayState.Idle
                        captureState.onResult(SignatureCaptureResult.Timeout)
                    }
                }, timeoutSeconds * 1_000L)
                true
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            show()
        } else {
            if (state.isVisualOperation()) {
                false
            } else {
                mainHandler.post { show() }
                true
            }
        }
    }

    fun completeSignatureCapture(result: SignatureCaptureResult): Boolean {
        val complete = {
            val captureState = state as? PinpadDisplayState.SignatureCapture
            if (captureState == null) {
                false
            } else {
                PinpadTraceLog.device("signature capture completed result=${result::class.simpleName}")
                state = PinpadDisplayState.Idle
                captureState.onResult(result)
                true
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            complete()
        } else {
            val active = state is PinpadDisplayState.SignatureCapture
            if (active) mainHandler.post { complete() }
            active
        }
    }

    fun dismissSignatureCapture(): Boolean {
        val dismiss = {
            if (state is PinpadDisplayState.SignatureCapture) {
                PinpadTraceLog.device("signature capture dismissed")
                state = PinpadDisplayState.Idle
                true
            } else {
                false
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            dismiss()
        } else {
            val active = state is PinpadDisplayState.SignatureCapture
            if (active) mainHandler.post { dismiss() }
            active
        }
    }

    fun showPhotoCapture(
        timeoutSeconds: Int,
        facing: CameraFacing,
        jpegQuality: Int,
        onResult: (PhotoCaptureResult) -> Unit,
    ): Boolean = showTimedVisualOperation(
        timeoutSeconds = timeoutSeconds,
        createState = {
            PinpadDisplayState.PhotoCapture(timeoutSeconds, facing, jpegQuality, onResult)
        },
        timeoutResult = { state ->
            (state as PinpadDisplayState.PhotoCapture).onResult(PhotoCaptureResult.Timeout)
        },
        description = "photo capture",
    )

    fun completePhotoCapture(result: PhotoCaptureResult): Boolean =
        completeVisualOperation<PinpadDisplayState.PhotoCapture>("photo capture", result) {
            it.onResult(result)
        }

    fun showQrDisplay(
        timeoutSeconds: Int,
        value: String,
        onResult: (QrDisplayResult) -> Unit,
    ): Boolean = showTimedVisualOperation(
        timeoutSeconds = timeoutSeconds,
        createState = { PinpadDisplayState.QrDisplay(timeoutSeconds, value, onResult) },
        timeoutResult = { state ->
            (state as PinpadDisplayState.QrDisplay).onResult(QrDisplayResult.Timeout)
        },
        description = "QR display",
    )

    fun completeQrDisplay(result: QrDisplayResult): Boolean =
        completeVisualOperation<PinpadDisplayState.QrDisplay>("QR display", result) {
            it.onResult(result)
        }

    fun showQrScan(
        timeoutSeconds: Int,
        facing: CameraFacing,
        onResult: (QrScanResult) -> Unit,
    ): Boolean = showTimedVisualOperation(
        timeoutSeconds = timeoutSeconds,
        createState = { PinpadDisplayState.QrScan(timeoutSeconds, facing, onResult) },
        timeoutResult = { state ->
            (state as PinpadDisplayState.QrScan).onResult(QrScanResult.Timeout)
        },
        description = "QR scan",
    )

    fun completeQrScan(result: QrScanResult): Boolean =
        completeVisualOperation<PinpadDisplayState.QrScan>("QR scan", result) {
            it.onResult(result)
        }

    fun dismissVisualOperation(): Boolean {
        val dismiss = {
            if (state.isVisualOperation()) {
                PinpadTraceLog.device("visual operation dismissed state=${state::class.simpleName}")
                state = PinpadDisplayState.Idle
                true
            } else {
                false
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            dismiss()
        } else {
            val active = state.isVisualOperation()
            if (active) mainHandler.post { dismiss() }
            active
        }
    }

    private fun showTimedVisualOperation(
        timeoutSeconds: Int,
        createState: () -> PinpadDisplayState,
        timeoutResult: (PinpadDisplayState) -> Unit,
        description: String,
    ): Boolean {
        val show = {
            if (state.isVisualOperation()) {
                false
            } else {
                clearContactlessLeds()
                val operationState = createState()
                updateState(operationState)
                mainHandler.postDelayed({
                    if (state === operationState) {
                        PinpadTraceLog.device("$description timed out")
                        state = PinpadDisplayState.Idle
                        timeoutResult(operationState)
                    }
                }, timeoutSeconds * 1_000L)
                true
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            show()
        } else {
            if (state.isVisualOperation()) {
                false
            } else {
                mainHandler.post { show() }
                true
            }
        }
    }

    private inline fun <reified T : PinpadDisplayState> completeVisualOperation(
        description: String,
        result: Any,
        crossinline notify: (T) -> Unit,
    ): Boolean {
        val complete = {
            val operationState = state as? T
            if (operationState == null) {
                false
            } else {
                PinpadTraceLog.device("$description completed result=${result::class.simpleName}")
                state = PinpadDisplayState.Idle
                notify(operationState)
                true
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            complete()
        } else {
            val active = state is T
            if (active) mainHandler.post { complete() }
            active
        }
    }

    private fun PinpadDisplayState.isVisualOperation(): Boolean =
        this is PinpadDisplayState.SignatureCapture ||
            this is PinpadDisplayState.PhotoCapture ||
            this is PinpadDisplayState.QrDisplay ||
            this is PinpadDisplayState.QrScan

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

    fun showThankYouAfterTransactionComplete() {
        val showThankYou = {
            val current = state
            val preserve = when (current) {
                is PinpadDisplayState.Message -> current.preserveOnTransactionComplete
                is PinpadDisplayState.BrandSensory -> current.preserveOnTransactionComplete
                else -> false
            }
            if (current is PinpadDisplayState.BrandSensory && !preserve) {
                pendingSensoryThankYou = current
                PinpadTraceLog.device("display transaction completion deferred until sensory finishes brand=${current.brand}")
            } else if (preserve) {
                PinpadTraceLog.device("display transaction completion preserved state=${current::class.simpleName}")
            } else {
                showThankYouThenIdle()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showThankYou()
        } else {
            mainHandler.post(showThankYou)
        }
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

    fun showMedia(path: String, video: Boolean) {
        clearContactlessLeds()
        updateState(PinpadDisplayState.Media(path, video))
    }

    fun showBrandSensory(
        brand: SensoryBrand,
        completionMessage: String? = null,
        completionMessageDurationMillis: Long = THANK_YOU_MS,
        minimumDisplayMillis: Long = if (brand == SensoryBrand.Mastercard) MASTERCARD_MINIMUM_DISPLAY_MS else 0L,
        preserveOnTransactionComplete: Boolean = false,
        onComplete: (() -> Unit)? = null,
    ) {
        clearContactlessLeds()
        updateState(
            PinpadDisplayState.BrandSensory(
                brand = brand,
                completionMessage = completionMessage,
                completionMessageDurationMillis = completionMessageDurationMillis,
                minimumCompletionAtMillis = SystemClock.elapsedRealtime() + minimumDisplayMillis.coerceAtLeast(0L),
                preserveOnTransactionComplete = preserveOnTransactionComplete,
                onComplete = onComplete,
            ),
        )
    }

    fun completeBrandSensory(expectedBrand: SensoryBrand? = null): Boolean {
        val complete = {
            val current = state as? PinpadDisplayState.BrandSensory
            if (current == null || expectedBrand != null && current.brand != expectedBrand) {
                false
            } else {
                val remainingMillis = current.minimumCompletionAtMillis - SystemClock.elapsedRealtime()
                if (remainingMillis > 0L) {
                    mainHandler.postDelayed(
                        {
                            if (state === current) completeBrandSensory(expectedBrand)
                        },
                        remainingMillis,
                    )
                } else {
                    val showDeferredThankYou = pendingSensoryThankYou === current
                    pendingSensoryThankYou = null
                    if (!current.completionMessage.isNullOrBlank()) {
                        showMessageThenIdle(
                            current.completionMessage,
                            current.completionMessageDurationMillis,
                            current.preserveOnTransactionComplete,
                        )
                    } else if (showDeferredThankYou) {
                        showThankYouThenIdle()
                    } else {
                        showIdle()
                    }
                    current.onComplete?.invoke()
                }
                true
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            complete()
        } else {
            val active = state is PinpadDisplayState.BrandSensory &&
                (expectedBrand == null || (state as PinpadDisplayState.BrandSensory).brand == expectedBrand)
            if (active) mainHandler.post { complete() }
            active
        }
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
    private const val MASTERCARD_MINIMUM_DISPLAY_MS = 3_000L
}

sealed interface PinpadDisplayState {
    data object Idle : PinpadDisplayState
    data class SwipeCard(val transaction: PinpadTransactionDisplay? = null) : PinpadDisplayState
    data class InsertCard(val transaction: PinpadTransactionDisplay? = null) : PinpadDisplayState
    data class TapCard(val transaction: PinpadTransactionDisplay? = null) : PinpadDisplayState
    data class PresentCard(val transaction: PinpadTransactionDisplay? = null, val options: QkDetectionRequest = QkDetectionRequest()) : PinpadDisplayState
    data class Message(
        val text: String,
        val preserveOnTransactionComplete: Boolean = false,
    ) : PinpadDisplayState
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
    data class KeyLoadAuthentication(
        val commandId: String,
        val password1Digits: Int,
        val password2Digits: Int,
        val activePassword: Int,
        val useOnScreenKeypad: Boolean,
        val message: KeyLoadAuthenticationMessage?,
        val onKey: (PinpadKeypadKey) -> Unit,
        val onSubmitPasswords: (String, String) -> Unit,
    ) : PinpadDisplayState
    data object KeyInjectionMode : PinpadDisplayState
    data class SignatureCapture(
        val timeoutSeconds: Int,
        val orientation: SignatureOrientation,
        val imageFormat: SignatureImageFormat,
        val onResult: (SignatureCaptureResult) -> Unit,
    ) : PinpadDisplayState
    data class PhotoCapture(
        val timeoutSeconds: Int,
        val facing: CameraFacing,
        val jpegQuality: Int,
        val onResult: (PhotoCaptureResult) -> Unit,
    ) : PinpadDisplayState
    data class QrDisplay(
        val timeoutSeconds: Int,
        val value: String,
        val onResult: (QrDisplayResult) -> Unit,
    ) : PinpadDisplayState
    data class QrScan(
        val timeoutSeconds: Int,
        val facing: CameraFacing,
        val onResult: (QrScanResult) -> Unit,
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
    data class Media(val path: String, val video: Boolean) : PinpadDisplayState
    data class BrandSensory(
        val brand: SensoryBrand,
        val completionMessage: String? = null,
        val completionMessageDurationMillis: Long = 3_000L,
        val minimumCompletionAtMillis: Long = 0L,
        val preserveOnTransactionComplete: Boolean = false,
        val onComplete: (() -> Unit)? = null,
    ) : PinpadDisplayState
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

sealed interface KeyLoadAuthenticationMessage {
    data class InvalidPassword(val attemptsRemaining: Int) : KeyLoadAuthenticationMessage
    data class Cooldown(val secondsRemaining: Long) : KeyLoadAuthenticationMessage
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
