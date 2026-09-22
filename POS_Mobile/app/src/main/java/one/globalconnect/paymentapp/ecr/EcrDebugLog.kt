package one.globalconnect.paymentapp.ecr

import android.util.Log
import one.globalconnect.paymentapp.BuildConfig

/** Metadata only: never dump payloads, card data, or exception messages. */
internal object EcrDebugLog {
    fun event(message: () -> String) {
        if (BuildConfig.ENABLE_ECR_DEBUG_LOGS) Log.d("ECR", message())
    }

    fun message(label: String, message: EcrMessage) = event {
        val requestId = (message.fields["RQ"] ?: message.fields["80"]).orEmpty().take(64)
            .map { if (it.isLetterOrDigit() || it in "_-.") it else '?' }.joinToString("")
        "$label command=${message.command} requestId=$requestId response=${message.response} " +
            "indicator=${message.indicator} more=${message.more} fields=${message.fields.keys}"
    }
}
