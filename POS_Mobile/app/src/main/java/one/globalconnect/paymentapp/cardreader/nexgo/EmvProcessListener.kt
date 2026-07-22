package one.globalconnect.paymentapp.cardreader.nexgo

import android.util.Log
import com.nexgo.oaf.apiv3.device.reader.CardInfoEntity
import com.nexgo.oaf.apiv3.emv.CandidateAppInfoEntity
import com.nexgo.oaf.apiv3.emv.EmvProcessResultEntity
import com.nexgo.oaf.apiv3.emv.OnEmvProcessListener2
import com.nexgo.oaf.apiv3.emv.PromptEnum
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listener used to expose [OnEmvProcessListener2] callbacks as Kotlin
 * lambdas so the transaction state machine can be written in a more readable
 * fashion.  The implementation mirrors the behaviour of the .NET wrapper that
 * ships with the legacy POS application.
 */
internal class EmvProcessListener : OnEmvProcessListener2 {
    private val stepCounter = AtomicInteger(0)

    var onCardHolderInputPin: ((Boolean, Int) -> Unit)? = null
    var onConfirmCardNo: ((CardInfoEntity?) -> Unit)? = null
    var onContactlessTapCardAgain: (() -> Unit)? = null
    var onFinish: ((Int, EmvProcessResultEntity?) -> Unit)? = null
    var onOnlineProc: (() -> Unit)? = null
    var onPrompt: ((PromptEnum?) -> Unit)? = null
    var onRemoveCard: (() -> Unit)? = null
    var onSelApp: ((List<String>?, List<CandidateAppInfoEntity>?, Boolean) -> Unit)? = null
    var onTransInitBeforeGpo: (() -> Unit)? = null

    override fun onSelApp(
        appLabels: MutableList<String>?,
        candidateApps: MutableList<CandidateAppInfoEntity>?,
        isMandatory: Boolean,
    ) {
        logStep(
            "onSelApp",
            "labels=${appLabels?.size ?: 0} labelValues=${appLabels.orEmpty()} " +
                "candidates=${candidateApps?.size ?: 0} mandatory=$isMandatory",
        )
        onSelApp?.invoke(appLabels, candidateApps, isMandatory)
    }

    override fun onTransInitBeforeGPO() {
        logStep("onTransInitBeforeGPO")
        onTransInitBeforeGpo!!.invoke()
    }

    override fun onConfirmCardNo(cardInfo: CardInfoEntity?) {
        logStep(
            "onConfirmCardNo",
            "slot=${cardInfo?.cardExistslot} pan=${maskPan(cardInfo?.cardNo)} " +
                "tk1=${cardInfo?.tk1?.length ?: 0} tk2=${cardInfo?.tk2?.length ?: 0} tk3=${cardInfo?.tk3?.length ?: 0}",
        )
        onConfirmCardNo?.invoke(cardInfo)
    }

    override fun onCardHolderInputPin(isOnlinePin: Boolean, leftTimes: Int) {
        logStep("onCardHolderInputPin", "online=$isOnlinePin left=$leftTimes")
        onCardHolderInputPin?.invoke(isOnlinePin, leftTimes)
    }

    override fun onContactlessTapCardAgain() {
        logStep("onContactlessTapCardAgain")
        onContactlessTapCardAgain?.invoke()
    }

    override fun onOnlineProc() {
        logStep("onOnlineProc")
        onOnlineProc?.invoke()
    }

    override fun onPrompt(prompt: PromptEnum?) {
        logStep("onPrompt", "prompt=${prompt?.name}")
        onPrompt?.invoke(prompt)
    }

    override fun onRemoveCard() {
        logStep("onRemoveCard")
        onRemoveCard?.invoke()
    }

    override fun onFinish(resultCode: Int, processResult: EmvProcessResultEntity?) {
        logStep("onFinish", "resultCode=$resultCode result=${processResult.toLogString()}")
        onFinish?.invoke(resultCode, processResult)
    }

    private companion object {
        private const val TAG = "NexgoEmvProcListener"
    }

    private fun maskPan(pan: String?): String? {
        if (pan.isNullOrBlank()) return pan
        return when {
            pan.length <= 4 -> "*".repeat(pan.length)
            pan.length <= 6 -> pan.take(1) + "*".repeat(pan.length - 2) + pan.takeLast(1)
            pan.length <= 10 -> pan.take(2) + "*".repeat(pan.length - 6) + pan.takeLast(4)
            else -> pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)
        }
    }

    private fun logStep(name: String, details: String = "") {
        val suffix = if (details.isBlank()) "" else " $details"
        Log.d(TAG, "EMV_STEP ${stepCounter.incrementAndGet()} $name$suffix")
    }

    private fun EmvProcessResultEntity?.toLogString(): String {
        if (this == null) return "<null>"
        return runCatching {
            val fields = this::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .joinToString(separator = ", ") { field ->
                    field.isAccessible = true
                    "${field.name}=${field.get(this)}"
                }
            "${this::class.java.simpleName}($fields)"
        }.getOrElse { "${this::class.java.simpleName}@${System.identityHashCode(this)}" }
    }
}
