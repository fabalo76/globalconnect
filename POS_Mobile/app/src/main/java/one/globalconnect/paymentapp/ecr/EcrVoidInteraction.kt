package one.globalconnect.paymentapp.ecr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import one.globalconnect.paymentapp.transaction.ReturnUiState
import one.globalconnect.paymentapp.transaction.Transaction

internal class VoidConfirmation {
    private val decision = CompletableDeferred<Boolean>()
    fun decide(confirmed: Boolean) = decision.complete(confirmed)
    suspend fun await(timeoutMillis: Long = 10_000): Boolean {
        val confirmed = withTimeoutOrNull(timeoutMillis) { decision.await() } ?: false
        decision.complete(false)
        return confirmed
    }
}

internal data class EcrVoidScreenState(val transaction: Transaction, val progress: ReturnUiState)

internal object EcrVoidInteraction {
    val screen = MutableStateFlow<EcrVoidScreenState?>(null)
    val rejectionMessage = MutableStateFlow<String?>(null)
    private var confirmation: VoidConfirmation? = null
    private var dismissed: CompletableDeferred<Unit>? = null

    suspend fun confirm(transaction: Transaction): Boolean {
        val pending = VoidConfirmation()
        confirmation = pending
        screen.value = EcrVoidScreenState(transaction, ReturnUiState.None())
        EcrDebugLog.event { "Void confirmation opened; timeout=10000ms" }
        one.globalconnect.paymentapp.GlobalConnectPaymentApplication.instance.nexgoApi.beepAttentionRequired()
        return try {
            pending.await().also { confirmed ->
                EcrDebugLog.event { if (confirmed) "Void confirmed" else "Void canceled or confirmation expired; no host request" }
            }
        } finally { confirmation = null }
    }
    fun decide(confirmed: Boolean) { confirmation?.decide(confirmed) }
    fun processing(message: String, status: one.globalconnect.paymentapp.transaction.ProcessingStatusUi? = null) {
        screen.value = screen.value?.copy(progress = ReturnUiState.Loading(message, status))
    }
    suspend fun result(success: Boolean, message: String) {
        val completion = CompletableDeferred<Unit>()
        dismissed = completion
        if (screen.value == null) rejectionMessage.value = message
        screen.value = screen.value?.copy(progress = ReturnUiState.ResultReady(success, message))
        try { withTimeoutOrNull(5_000) { completion.await() } } finally {
            dismissed = null
            rejectionMessage.value = null
        }
    }
    fun back() {
        if (screen.value?.progress is ReturnUiState.None) decide(false)
        else if (screen.value?.progress is ReturnUiState.ResultReady || rejectionMessage.value != null) dismissed?.complete(Unit)
    }
    fun clear() { confirmation = null; dismissed = null; screen.value = null; rejectionMessage.value = null }
}
