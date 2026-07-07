package com.uic.uicpaymentapp.records

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.tms.payment_app.TMSDATA
import com.uic.uicpaymentapp.printer.NexGoPaymentPrinter
import com.uic.uicpaymentapp.printer.PaymentPrinter
import com.uic.uicpaymentapp.settlement.storage.SettlementSnapshot
import com.uic.uicpaymentapp.settlement.storage.SettlementStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class ReprintSettlementViewModel(
    private val settlementStateRepository: SettlementStateRepository,
    private val tmsDatabase: TMSDATA,
) : ViewModel() {

    private val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
    private val _uiState = MutableStateFlow(ReprintSettlementUiState())
    val uiState: StateFlow<ReprintSettlementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settlementStateRepository.observeStates().collectLatest { states ->
                val options = states
                    .map { state ->
                        ReprintSettlementOption(
                            id = state.acquirerId,
                            name = state.acquirerName.ifBlank { state.acquirerId },
                            currencySymbol = state.currencySymbol,
                            pending = state.pending,
                            snapshot = state.lastSnapshot,
                        )
                    }
                    .sortedBy { it.name.lowercase(Locale.getDefault()) }
                val selectedId = resolveSelectedId(options, _uiState.value.selectedAcquirerId)
                _uiState.value = ReprintSettlementUiState(
                    options = options,
                    selectedAcquirerId = selectedId,
                )
            }
        }
    }

    fun selectAcquirer(acquirerId: String) {
        _uiState.value = _uiState.value.copy(selectedAcquirerId = acquirerId)
    }

    fun print(context: Context) {
        val snapshot = currentSnapshot() ?: return
        paymentPrinter.printSettlementReceipt(context, snapshot, tmsDatabase)
    }

    private fun currentSnapshot(): SettlementSnapshot? =
        _uiState.value.options.firstOrNull { it.id == _uiState.value.selectedAcquirerId }?.snapshot

    private fun resolveSelectedId(
        options: List<ReprintSettlementOption>,
        current: String?,
    ): String? {
        if (options.isEmpty()) return null
        if (current != null && options.any { it.id == current }) return current
        return options.firstOrNull { it.snapshot != null }?.id ?: options.first().id
    }
}

data class ReprintSettlementOption(
    val id: String,
    val name: String,
    val currencySymbol: String,
    val pending: Boolean,
    val snapshot: SettlementSnapshot?,
)

data class ReprintSettlementUiState(
    val options: List<ReprintSettlementOption> = emptyList(),
    val selectedAcquirerId: String? = null,
) {
    val selectedOption: ReprintSettlementOption?
        get() = options.firstOrNull { it.id == selectedAcquirerId }
}
