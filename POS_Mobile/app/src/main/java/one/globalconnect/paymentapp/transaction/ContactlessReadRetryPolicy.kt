package one.globalconnect.paymentapp.transaction

import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import one.globalconnect.paymentapp.cardreader.nexgo.NexgoSdkResult

internal object ContactlessReadRetryPolicy {
    fun shouldRetry(
        resultCode: Int?,
        slot: CardSlotTypeEnum?,
        authorizationStarted: Boolean,
    ): Boolean =
        !authorizationStarted && slot == CardSlotTypeEnum.RF && resultCode in setOf(
            NexgoSdkResult.Fail,
            NexgoSdkResult.Emv_Communicate_Timeout,
            NexgoSdkResult.Picc_Card_Sense_Err,
            NexgoSdkResult.Emv_Candidatelist_Empty,
        )
}
