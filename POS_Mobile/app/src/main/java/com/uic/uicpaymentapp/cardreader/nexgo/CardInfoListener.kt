package com.uic.uicpaymentapp.cardreader.nexgo

import android.util.Log
import com.nexgo.oaf.apiv3.device.reader.CardInfoEntity
import com.nexgo.oaf.apiv3.device.reader.OnCardInfoListener

/**
 * Thin wrapper around [OnCardInfoListener] that exposes callback lambdas so the
 * EMV orchestration code can be written in a more Kotlin friendly manner.
 */
internal class CardInfoListener : OnCardInfoListener {
    var onCardInfo: ((Int, CardInfoEntity?) -> Unit)? = null
    var onMultipleCards: (() -> Unit)? = null
    var onSwipeIncorrect: (() -> Unit)? = null

    override fun onCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
        Log.d(
            TAG,
            "onCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot} pan=${maskPan(cardInfo?.cardNo)} tk2Length=${cardInfo?.tk2?.length}",
        )
        onCardInfo?.invoke(retCode, cardInfo)
    }

    override fun onSwipeIncorrect() {
        Log.d(TAG, "onSwipeIncorrect")
        onSwipeIncorrect?.invoke()
    }

    override fun onMultipleCards() {
        Log.d(TAG, "onMultipleCards")
        onMultipleCards?.invoke()
    }

    private fun maskPan(pan: String?): String? {
        Log.d(TAG, "maskPan invoked panLength=${pan?.length}")
        if (pan.isNullOrBlank()) return pan
        return when {
            pan.length <= 4 -> "*".repeat(pan.length)
            pan.length <= 6 -> pan.take(1) + "*".repeat(pan.length - 2) + pan.takeLast(1)
            pan.length <= 10 -> pan.take(2) + "*".repeat(pan.length - 6) + pan.takeLast(4)
            else -> pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)
        }.also {
            Log.d(TAG, "maskPan result=$it")
        }
    }

    private companion object {
        private const val TAG = "NexgoCardInfoListener"
    }
}
