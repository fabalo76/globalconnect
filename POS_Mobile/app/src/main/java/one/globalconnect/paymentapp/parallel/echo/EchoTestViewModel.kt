package one.globalconnect.paymentapp.parallel.echo

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.transaction.HostProcessingStateMachine
import one.globalconnect.paymentapp.transaction.ProcessingStatusStepState
import one.globalconnect.paymentapp.transaction.ProcessingStatusStrings
import one.globalconnect.paymentapp.transaction.ProcessingStatusUi
import one.globalconnect.paymentapp.uicpos.pos.host.AcquirerSslCache
import one.globalconnect.paymentapp.uicpos.pos.host.LengthPrefixRegistry
import one.globalconnect.paymentapp.uicpos.pos.host.parseHostAddress
import one.globalconnect.paymentapp.uicpos.pos.host.HostSettings
import one.globalconnect.paymentapp.uicpos.pos.host.HostProcessingEvent
import one.globalconnect.paymentapp.uicpos.pos.host.HostResponseMessageResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.NumberFormatException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.max

class EchoTestViewModel(
    private val client: EchoTestClient = EchoTestClient(),
    private val application: GlobalConnectPaymentApplication = GlobalConnectPaymentApplication.instance
) : ViewModel() {

    private val _uiState = MutableStateFlow(EchoTestUiState())
    val uiState: StateFlow<EchoTestUiState> = _uiState.asStateFlow()

    private val tmsDatabase: TMSDATA = application.container.tmsDatabase
    private val terminal: TMS_Terminal? = tmsDatabase.Terminal.firstOrNull()
    private val hostProcessingStrings = ProcessingStatusStrings(
        connecting = application.getString(R.string.processing_status_connecting),
        sending = application.getString(R.string.processing_status_sending),
        waitingForResponse = application.getString(R.string.processing_status_waiting),
        processingResponse = application.getString(R.string.processing_status_processing_response),
        result = application.getString(R.string.processing_status_result),
        pendingResult = application.getString(R.string.processing_status_result_pending),
    )
    private val hostProcessingStateMachine = HostProcessingStateMachine(hostProcessingStrings)

    init {
        loadAcquirers()
    }

    fun selectAcquirer(acquirerId: String) {
        Log.d(TAG, "selectAcquirer invoked with id=$acquirerId")
        _uiState.update { current ->
            val option = current.acquirers.find { it.acquirer.AcqID == acquirerId }
            if (option == null) {
                current.copy(
                    selectedAcquirerId = acquirerId,
                    hostSettings = null,
                    status = EchoTestStatus.Failure("Acquirer not found"),
                    processingStatus = null,
                    showStatusWindow = false
                )
            } else {
                Log.d(TAG, "Selected acquirer ${option.acquirer.AcquirerName} (${option.acquirer.AcqID})")
                val settings = buildHostSettings(option)
                current.copy(
                    selectedAcquirerId = acquirerId,
                    hostSettings = settings,
                    status = EchoTestStatus.Idle,
                    lastAttempt = 0,
                    lastRetry = 0,
                    processingStatus = null,
                    showStatusWindow = false
                )
            }
        }
    }

    fun sendEchoTest() {
        val state = _uiState.value
        val option = state.acquirers.find { it.acquirer.AcqID == state.selectedAcquirerId }
        val terminalConfig = terminal

        Log.d(
            TAG,
            "sendEchoTest invoked with acquirer=${option?.acquirer?.AcquirerName} MerchID=${option?.acquirer?.MerchID} AcqTermID=${option?.acquirer?.AcqTermID} TMS TerminalId=${terminalConfig?.TermID}"
        )

        if (option == null || terminalConfig == null) {
            _uiState.update {
                it.copy(
                    status = EchoTestStatus.Failure("Terminal or acquirer configuration is missing"),
                    processingStatus = null,
                    showStatusWindow = false
                )
            }
            return
        }

        val settings = state.hostSettings ?: buildHostSettings(option)
        if (settings.primary == null && settings.secondary == null) {
            _uiState.update {
                it.copy(
                    status = EchoTestStatus.Failure("No host endpoints configured"),
                    processingStatus = null,
                    showStatusWindow = false
                )
            }
            return
        }

        val initialProcessingStatus = hostProcessingStateMachine.restart()
        val request = buildRequest(option, settings, terminalConfig)
        Log.d(TAG, "Echo test request prepared: $request")

        _uiState.update {
            it.copy(
                status = EchoTestStatus.InProgress,
                lastAttempt = 0,
                lastRetry = 0,
                processingStatus = initialProcessingStatus,
                showStatusWindow = true
            )
        }

        viewModelScope.launch {
            try {
                val result = client.execute(request)
                val successStatus = result.status
                val responseMessage = if (successStatus is EchoTestStatus.Success) {
                    HostResponseMessageResolver.resolveOrFallback(successStatus.responseCode)
                } else {
                    application.getString(R.string.msg_approved)
                }
                setProcessingResult(ProcessingStatusStepState.COMPLETED, responseMessage)
                _uiState.update {
                    it.copy(
                        status = result.status,
                        lastAttempt = result.attempt,
                        lastRetry = result.retry,
                        showStatusWindow = true
                    )
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Echo test failed", error)
                val reason = error.toUserMessage()
                setProcessingResult(ProcessingStatusStepState.FAILED, reason)
                _uiState.update {
                    it.copy(
                        status = EchoTestStatus.Failure(reason),
                        lastAttempt = request.attempts,
                        lastRetry = max(request.primaryRetries, request.secondaryRetries),
                        showStatusWindow = true
                    )
                }
            }
        }
    }

    private fun loadAcquirers() {
        val ipProfiles = tmsDatabase.IPTab.associateBy { it.IPTabID }
        val options = tmsDatabase.Acquirer.mapNotNull { acquirer ->
            val ipProfile = ipProfiles[acquirer.IPTabTran]
            if (ipProfile == null) {
                Log.w(TAG, "Missing IPTab configuration for ${acquirer.AcquirerName}")
                null
            } else {
                AcquirerOption(acquirer, ipProfile)
            }
        }

        Log.d(TAG, "Loaded ${options.size} acquirer profiles from TMS")

        val selected = options.firstOrNull()
        _uiState.update {
            it.copy(
                acquirers = options,
                selectedAcquirerId = selected?.acquirer?.AcqID,
                hostSettings = selected?.let { option -> buildHostSettings(option) },
                status = if (options.isEmpty()) {
                    EchoTestStatus.Failure("No acquirers configured")
                } else {
                    EchoTestStatus.Idle
                },
                processingStatus = null,
                showStatusWindow = false
            )
        }
    }

    private fun buildRequest(
        option: AcquirerOption,
        settings: HostSettings,
        terminal: TMS_Terminal
    ): EchoTestRequest {
        val connectTimeoutMs = secondsToMillis(settings.connectTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SEC)
        val readTimeoutMs = secondsToMillis(settings.readTimeoutSeconds, DEFAULT_READ_TIMEOUT_SEC)
        Log.d(
            TAG,
            "Building EchoTestRequest: acquirer=${option.acquirer.AcquirerName} connectTimeoutMs=$connectTimeoutMs readTimeoutMs=$readTimeoutMs attempts=${settings.attempts} primaryRetries=${settings.primaryRetries} secondaryRetries=${settings.secondaryRetries} useTls=${settings.isTls}"
        )
        Log.d(
            TAG,
            "Host endpoints -> primary=${settings.primary?.displayValue ?: "<none>"} secondary=${settings.secondary?.displayValue ?: "<none>"} length=${settings.length}"
        )

        return EchoTestRequest(
            acquirer = option.acquirer,
            ipProfile = option.ipProfile,
            terminal = terminal,
            primaryEndpoint = settings.primary,
            secondaryEndpoint = settings.secondary,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            attempts = settings.attempts,
            primaryRetries = settings.primaryRetries,
            secondaryRetries = settings.secondaryRetries,
            useTls = settings.isTls,
            sslSocketFactory = if (settings.isTls) AcquirerSslCache.get(option.ipProfile.IPTabID.toString()) else null,
            lengthConfig = settings.length,
            onStatusChanged = ::updateProcessingStatus
        )
    }

    private fun updateProcessingStatus(event: HostProcessingEvent) {
        _uiState.update { current ->
            val status = current.processingStatus ?: return@update current
            val updated = hostProcessingStateMachine.onEvent(event)
            current.copy(processingStatus = updated)
        }
    }

    private fun setProcessingResult(state: ProcessingStatusStepState, detail: String) {
        _uiState.update { current ->
            val status = current.processingStatus ?: return@update current
            val updated = when (state) {
                ProcessingStatusStepState.COMPLETED -> hostProcessingStateMachine.onSuccess(detail)
                ProcessingStatusStepState.FAILED -> hostProcessingStateMachine.onFailure(detail)
                else -> status
            }
            current.copy(processingStatus = updated)
        }
    }

    fun dismissStatusWindow() {
        _uiState.update {
            it.copy(showStatusWindow = false, processingStatus = null)
        }
    }

    private fun buildHostSettings(option: AcquirerOption): HostSettings {
        val length = LengthPrefixRegistry.resolve(option.acquirer.HostProtocol, terminal)
        val connectTimeout = option.ipProfile.IPConnTime.safeInt(DEFAULT_CONNECT_TIMEOUT_SEC)
        val readTimeout = option.ipProfile.TranTimeOut.safeInt(DEFAULT_READ_TIMEOUT_SEC)
        val attempts = option.ipProfile.AttemptIPT.safeInt(1)
        val primaryRetries = option.ipProfile.IPConnRetriesP.safeInt(1)
        val secondaryRetries = option.ipProfile.IPConnRetriesS.safeInt(1)

        Log.d(
            TAG,
            "Host settings resolved for ${option.acquirer.AcquirerName}: connectTimeout=$connectTimeout readTimeout=$readTimeout attempts=$attempts primaryRetries=$primaryRetries secondaryRetries=$secondaryRetries tls=${option.ipProfile.SSL}"
        )

        return HostSettings(
            isTls = option.ipProfile.SSL,
            primary = parseHostAddress(option.ipProfile.PrimIpAddr),
            secondary = parseHostAddress(option.ipProfile.SecIpAddr),
            connectTimeoutSeconds = connectTimeout,
            readTimeoutSeconds = readTimeout,
            attempts = attempts,
            primaryRetries = primaryRetries,
            secondaryRetries = secondaryRetries,
            length = length
        )
    }

    private fun Long.safeInt(default: Int): Int {
        if (this <= 0) return default
        return coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun secondsToMillis(seconds: Int, fallback: Int): Int {
        val value = if (seconds <= 0) fallback else seconds
        val capped = value.coerceAtMost(Int.MAX_VALUE / 1000)
        return capped * 1000
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is SocketTimeoutException -> "Host response timed out"
        is UnknownHostException -> "Unknown host: ${message ?: ""}".trim()
        is NumberFormatException -> "Invalid host port configuration"
        else -> localizedMessage ?: this::class.java.simpleName
    }

    companion object {
        private const val TAG = "EchoTestViewModel"
        private const val DEFAULT_CONNECT_TIMEOUT_SEC = 10
        private const val DEFAULT_READ_TIMEOUT_SEC = 60
    }
}

data class AcquirerOption(
    val acquirer: TMS_Acquirer,
    val ipProfile: TMS_HostConnectionInfo
)

data class EchoTestUiState(
    val acquirers: List<AcquirerOption> = emptyList(),
    val selectedAcquirerId: String? = null,
    val hostSettings: HostSettings? = null,
    val status: EchoTestStatus = EchoTestStatus.Idle,
    val lastAttempt: Int = 0,
    val lastRetry: Int = 0,
    val processingStatus: ProcessingStatusUi? = null,
    val showStatusWindow: Boolean = false
)
