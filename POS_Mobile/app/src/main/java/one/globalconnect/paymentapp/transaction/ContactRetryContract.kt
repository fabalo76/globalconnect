package one.globalconnect.paymentapp.transaction

import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import one.globalconnect.paymentapp.cardreader.nexgo.NexgoSdkResult

/** Defines issuer-response rules that require a contactless transaction to be repeated by contact ICC. */
object ContactRetryContract {
    /** Mastercard issuer response requesting that the cardholder use the chip interface. */
    const val USE_CONTACT_CHIP_RESPONSE_CODE = "65"
    const val USE_CONTACT_CHIP_RESPONSE_CODE_EXTENDED = "123"

    private val useContactChipResponseCodes = setOf(
        USE_CONTACT_CHIP_RESPONSE_CODE,
        USE_CONTACT_CHIP_RESPONSE_CODE_EXTENDED,
    )

    /**
     * Determines whether the terminal must restart the current operation using only contact ICC.
     *
     * @param responseCode ISO 8583 response code returned by the issuer.
     * @param cardSlot card interface used for the completed attempt.
     * @return `true` only for response 65 or 123 received after a contactless attempt.
     */
    fun shouldRetryUsingContact(
        responseCode: String?,
        cardSlot: CardSlotTypeEnum?,
    ): Boolean = responseCode?.trim() in useContactChipResponseCodes && cardSlot == CardSlotTypeEnum.RF

    /** Detects a contactless kernel decision at First GEN AC that requires a new contact ICC attempt. */
    fun shouldRetryKernelResultUsingContact(
        resultCode: Int?,
        cardSlot: CardSlotTypeEnum?,
    ): Boolean = resultCode == NexgoSdkResult.Emv_Other_Interface && cardSlot == CardSlotTypeEnum.RF
}
