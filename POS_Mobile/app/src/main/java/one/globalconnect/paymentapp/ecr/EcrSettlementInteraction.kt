package one.globalconnect.paymentapp.ecr

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import one.globalconnect.paymentapp.settlement.SettlementResult

internal data class EcrSettlementResultState(
    val success: Boolean,
    val results: List<SettlementResult>,
    val detail: String? = null,
) {
    val displayMillis: Long get() = if (success) 3_000 else 5_000
}

internal object EcrSettlementInteraction {
    val active = MutableStateFlow(false)
    val result = MutableStateFlow<EcrSettlementResultState?>(null)

    suspend fun show(state: EcrSettlementResultState) {
        result.value = state
        try { delay(state.displayMillis) } finally { clear() }
    }

    fun clear() { result.value = null; active.value = false }
}
