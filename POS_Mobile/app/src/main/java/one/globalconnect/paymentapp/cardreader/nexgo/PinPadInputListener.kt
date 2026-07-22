package one.globalconnect.paymentapp.cardreader.nexgo

import android.util.Log
import com.nexgo.oaf.apiv3.device.pinpad.OnPinPadInputListener

/**
 * Lightweight wrapper around [OnPinPadInputListener] so that the PIN pad event
 * handling code can be unit tested and reused between the EMV transaction
 * manager and the dedicated PIN entry activity.
 */
internal class PinPadInputListener : OnPinPadInputListener {
    var onInputResult: ((Int, ByteArray?) -> Unit)? = null
    var onSendKey: ((Byte) -> Unit)? = null

    override fun onInputResult(retCode: Int, data: ByteArray?) {
        Log.d(TAG, "onInputResult retCode=$retCode length=${data?.size ?: 0}")
        onInputResult?.invoke(retCode, data)
    }

    override fun onSendKey(keyCode: Byte) {
        Log.d(TAG, "onSendKey keyCode=$keyCode")
        onSendKey?.invoke(keyCode)
    }

    private companion object {
        private const val TAG = "NexgoPinPadListener"
    }
}
