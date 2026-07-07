package com.uic.uicpaymentapp.profile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.systeminfo.InfoMgmtRepository
import com.uic.uicpaymentapp.systeminfo.buildInfoMgmtFromTms
import com.uic.uicpaymentapp.transaction.ProcessTransactionUiState
import com.uic.uicpaymentapp.transaction.StateType
import com.uic.uicpaymentapp.uicpos.pos.model.InfoMgmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InfoMgmtViewModel(
    private val infoMgmtRepository: InfoMgmtRepository,
    private val context: Context,
) : ViewModel() {

    private val infoMgmtViewModelState = MutableStateFlow(InfoMgmtViewModelState())

    val uiState = infoMgmtViewModelState
        .map(InfoMgmtViewModelState::toUiState)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            infoMgmtViewModelState.value.toUiState(),
        )

    var showFailedDialog: Boolean by mutableStateOf(false)

    var fetchInfoOngoing: Boolean by mutableStateOf(false)

    private val _infoMgmt = MutableStateFlow(InfoMgmt())
    val infoMgmt: StateFlow<InfoMgmt> = _infoMgmt

    init {
        viewModelScope.launch {
            val storedInfo = withContext(Dispatchers.IO) {
                infoMgmtRepository.getInfoMgmt()
            }
            _infoMgmt.value = storedInfo ?: InfoMgmt()
            getInfoMgmt()
        }
    }

    fun getInfoMgmt() {
        fetchInfoOngoing = true
        showFailedDialog = false
        infoMgmtViewModelState.update {
            it.copy(
                state = StateType.CONNECTING,
                error = false,
                message = context.getString(R.string.msg_comm_connecting),
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val infoMgmt = buildInfoMgmtFromTms(UICApplication.instance.container.tmsDatabase)
                    val existing = infoMgmtRepository.getInfoMgmt()
                    if (existing == null) {
                        infoMgmtRepository.insertAll(infoMgmt)
                    } else {
                        infoMgmt.databaseId = existing.databaseId
                        infoMgmtRepository.update(infoMgmt)
                    }
                    infoMgmt
                }
            }

            result.onSuccess { infoMgmt ->
                _infoMgmt.value = infoMgmt
                fetchInfoOngoing = false
                infoMgmtViewModelState.update {
                    it.copy(
                        state = StateType.AUTHORIZED,
                        error = false,
                        message = context.getString(R.string.payment_state_payment_success),
                    )
                }
            }.onFailure { throwable ->
                fetchInfoOngoing = false
                showFailedDialog = true
                val errorMessage = throwable.localizedMessage?.takeUnless { it.isBlank() }
                    ?: context.getString(R.string.err_unknown)
                infoMgmtViewModelState.update {
                    it.copy(
                        state = StateType.FAILED,
                        error = true,
                        message = errorMessage,
                    )
                }
            }
        }
    }
}

private data class InfoMgmtViewModelState(
    val state: StateType = StateType.AUTHORIZED,
    val error: Boolean = false,
    val message: String = "",
) {
    fun toUiState(): ProcessTransactionUiState =
        when (state) {
            StateType.CONNECTING -> ProcessTransactionUiState.CONNECTING(message)
            StateType.AUTHORIZED -> {
                ProcessTransactionUiState.AUTHORIZED(
                    message.ifBlank {
                        UICApplication.instance.getString(R.string.payment_state_payment_success)
                    },
                )
            }

            StateType.AUTHORIZING -> ProcessTransactionUiState.AUTHORIZING(
                UICApplication.instance.getString(R.string.payment_state_authorizing),
            )

            StateType.AWAITINGCARD -> ProcessTransactionUiState.AWAITINGCARD(message, "")
            StateType.FAILED -> ProcessTransactionUiState.FAILED(message)
            StateType.PARTIAL -> ProcessTransactionUiState.FAILED(
                UICApplication.instance.getString(R.string.payment_state_unexpected_partial),
            )

            StateType.RETRY -> ProcessTransactionUiState.RETRY(message)
            StateType.CONNECTIONFAILED -> ProcessTransactionUiState.CONNECTIONFAILED(message)
        }
}
