package one.globalconnect.paymentapp

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "TmsParamsViewModel"

/** How long to wait for a reply from xTMSAgent before declaring a timeout. */
private const val REQUEST_TIMEOUT_MS = 60_000L

/**
 * Represents the state of an in-progress or failed parameter download.
 *
 * Drives the UI shown to the operator when the terminal has no TMS parameters.
 */
sealed class ParamsRequestState {
    /** Broadcast sent to xTMSAgent — waiting for it to download and reply. */
    object Requesting : ParamsRequestState()

    /** No reply arrived within [REQUEST_TIMEOUT_MS] milliseconds. */
    object TimedOut : ParamsRequestState()

    /** xTMSAgent replied with an explicit failure reason. */
    data class Failed(val reason: String) : ParamsRequestState()
}

/**
 * Manages the parameter download request lifecycle for the "no parameters" state.
 *
 * Created by MainActivity when [GlobalConnectPaymentApplication.tmsDatabase] has no terminals.
 * Automatically sends the first request and starts a timeout timer.  If the timer
 * expires before xTMSAgent replies, the state transitions to [ParamsRequestState.TimedOut]
 * and the UI shows a retry button.
 *
 * MainActivity's dynamic BroadcastReceiver calls [reportFailure] when xTMSAgent
 * sends ACTION_PARAMS_DOWNLOAD_FAILED.  On success, [GlobalConnectPaymentApplication.paramsReadyFlow]
 * emits `true` and Compose recomposes the UI in place — no ViewModel involvement needed.
 */
class TmsParamsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state: MutableStateFlow<ParamsRequestState> =
        MutableStateFlow(ParamsRequestState.Requesting)

    /** Observed by the waiting screen composable to drive its UI. */
    val state: StateFlow<ParamsRequestState> = _state.asStateFlow()

    private var timeoutJob: Job? = null

    init {
        // Send the first request immediately.  GlobalConnectPaymentApplication.onCreate() may have
        // already sent one; xTMSAgent's AtomicBoolean deduplicates concurrent calls.
        sendRequest()

        // Cancel the timeout as soon as params arrive successfully.
        viewModelScope.launch {
            getApplication<GlobalConnectPaymentApplication>().paramsReadyFlow
                .filter { it }
                .first()
            Log.i(TAG, "Params ready — cancelling timeout")
            timeoutJob?.cancel()
        }
    }

    /**
     * Re-checks a parameter update already pushed to this application before asking
     * xTMSAgent to start a new download. A pushed update can be stored locally while
     * it waits for the terminal to become idle, so blindly downloading again could
     * supersede the task that the portal is already tracking.
     */
    fun retry() {
        timeoutJob?.cancel()
        _state.value = ParamsRequestState.Requesting

        viewModelScope.launch {
            when (
                PendingUpdateManager.applyPendingParamUpdateIfBatchEmpty(
                    getApplication<GlobalConnectPaymentApplication>().applicationContext,
                )
            ) {
                PendingParamUpdateResult.Applied -> {
                    Log.i(TAG, "Retry applied the pending pushed parameter update")
                    // applyTmsUpdate() raises paramsReadyFlow and dismisses the waiting screen.
                }

                PendingParamUpdateResult.WaitingForIdleBatch -> {
                    Log.i(TAG, "Retry found a pushed parameter update waiting for an idle batch")
                    _state.value = ParamsRequestState.Failed(
                        getApplication<GlobalConnectPaymentApplication>()
                            .getString(R.string.pending_param_update_msg),
                    )
                }

                PendingParamUpdateResult.NoPendingUpdate,
                PendingParamUpdateResult.Failed -> {
                    Log.i(TAG, "No applicable local pushed update; asking xTMSAgent to check or download")
                    sendRequest()
                }
            }
        }
    }

    /**
     * Transitions to [ParamsRequestState.Failed] and cancels the timeout timer.
     * Called by MainActivity when ACTION_PARAMS_DOWNLOAD_FAILED is received.
     */
    fun reportFailure(reason: String) {
        Log.e(TAG, "Param download failed: $reason")
        timeoutJob?.cancel()
        _state.value = ParamsRequestState.Failed(reason)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun sendRequest() {
        timeoutJob?.cancel()
        _state.value = ParamsRequestState.Requesting
        Log.i(TAG, "Sending ACTION_REQUEST_PARAMS to xTMSAgent (timeout=${REQUEST_TIMEOUT_MS}ms)")
        getApplication<GlobalConnectPaymentApplication>().requestParamsFromXtmsAgent()

        timeoutJob = viewModelScope.launch {
            delay(REQUEST_TIMEOUT_MS)
            if (_state.value is ParamsRequestState.Requesting) {
                Log.w(TAG, "No reply from xTMSAgent after ${REQUEST_TIMEOUT_MS}ms — timing out")
                _state.value = ParamsRequestState.TimedOut
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timeoutJob?.cancel()
    }
}
