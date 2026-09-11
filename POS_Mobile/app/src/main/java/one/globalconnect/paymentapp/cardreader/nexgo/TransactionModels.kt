package one.globalconnect.paymentapp.cardreader.nexgo

import com.nexgo.oaf.apiv3.device.reader.CardInfoEntity
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import com.nexgo.oaf.apiv3.emv.EmvProcessResultEntity
import com.nexgo.oaf.apiv3.emv.PromptEnum

/**
 * Describes the parameters required to kick off an EMV transaction.  Amounts are
 * expressed as strings containing the value in the smallest currency unit (for
 * example, "000000010000" represents 100.00).
 */
data class EmvTransactionRequest(
    val amount: String,
    val cashbackAmount: String = "000000000000",
    val transactionType: Byte = 0x00,
    val traceNumber: String = "000001",
    val allowSwipe: Boolean = true,
    val allowContact: Boolean = true,
    val allowContactless: Boolean = true,
    val timeoutSeconds: Int = 60,
    val countryCode: String = "0840",
    val currencyCode: String = "0840",
    val forceOnline: Boolean = false,
    val purpose: EmvTransactionPurpose = EmvTransactionPurpose.PAYMENT,
)

enum class EmvTransactionPurpose {
    PAYMENT,
    OFFLINE_PIN_CHANGE,
    OFFLINE_PIN_UNBLOCK,
}

data class MagstripeData(
    val track1: String?,
    val track2: String?,
    val track3: String?,
)

interface EmvTransactionListener {
    fun onCardDetected(slot: CardSlotTypeEnum, info: CardInfoEntity) {}
    fun onMagstripeRead(data: MagstripeData) {}
    fun onApplicationSelectionRequested(appLabels: List<String>, isMandatory: Boolean) {}
    fun onPrompt(prompt: PromptEnum?) {}
    fun onPinRequested(isOnlinePin: Boolean, attemptsRemaining: Int) {}
    fun onOnlineProcessing() {}
    fun onContactlessRetryRequired() {}
    fun onRemoveCard() {}
    fun onTransactionFinished(resultCode: Int, result: EmvProcessResultEntity?) {}
    fun onError(message: String, throwable: Throwable? = null) {}
    fun onMultipleCardsDetected() {}
    fun onSwipeIncorrect() {}
}
