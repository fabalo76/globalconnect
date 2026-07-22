package one.globalconnect.paymentapp.transaction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.records.applyFormattedTimes
import one.globalconnect.paymentapp.records.dateTimeFormatter
import one.globalconnect.paymentapp.uicpos.pos.controller.TransAuthCapture
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.SortedMap

class BatchViewModel(
    private val transactionRepository: TransactionRepository,
    private val profileRepository: ProfileRepository,
    private val context: Context
) : ViewModel() {
    var startDate: LocalDate by mutableStateOf(LocalDate.now().minusMonths(3))

    var endDate: LocalDate by mutableStateOf(LocalDate.now())

    var searchTerm by mutableStateOf("")

    private val filterFlow =
        MutableStateFlow(Pair(startDate, endDate))

    /**
     * Holds home ui state. The list of transactions are retrieved from [TransactionRepository] and mapped to
     * [DailyTransactionsByDate]
     */
    private val dailyTransactionsByDate: MutableStateFlow<SortedMap<LocalDate, MutableList<Transaction>>> =
        MutableStateFlow(
            sortedMapOf()
        )

    private val _filteredFlow = MutableStateFlow(sortedMapOf<LocalDate, List<Transaction>>())
    val filteredFlow: StateFlow<SortedMap<LocalDate, List<Transaction>>> = _filteredFlow

    private val batchViewModelState = MutableStateFlow(
        BatchViewModelState(
            batchState = BatchState.STOPPED,
            batchProgress = 0,
            errors = 0
        )
    )

    private var transactionsToPrint: List<Transaction> = listOf()

    val uiState = batchViewModelState
        .map(BatchViewModelState::toUiState)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            batchViewModelState.value.toUiState()
        )

    private val maxRetries = 3

    init {
        Log.i("BatchViewModel", "init batch viewmodel")
        viewModelScope.launch {
            transactionRepository.getAllOpenAndNeedTipTransactionsStream(
            ).catch { exception ->
                Log.d("TransactionHistoryViewModel", exception.message.toString())
            }.map {
                val sortedTransactions = mutableMapOf<LocalDate, MutableList<Transaction>>()
                for (transaction in it) {
                    try {
                        val dateTime =
                            LocalDateTime.parse(transaction.localDateTime, dateTimeFormatter)
                        transaction.applyFormattedTimes(dateTime)
                        val date = dateTime.toLocalDate()
                        if (sortedTransactions.containsKey(date)) {
                            sortedTransactions[date]?.add(transaction)
                        } else {
                            sortedTransactions[date] = mutableListOf(transaction)
                        }
                    } catch (e: Exception) {
                        transaction.applyFormattedTimes()
                        Log.d(
                            "TransactionHistoryViewModel",
                            "Failed to parse transaction: $transaction"
                        )
                    }
                }
                sortedTransactions.forEach { entry ->
                    entry.value.sortByDescending { transaction -> transaction.localDateTime }
                }
                return@map sortedTransactions.toSortedMap()
            }.collect {
                dailyTransactionsByDate.value = it
            }
        }

        viewModelScope.launch {
            dailyTransactionsByDate.combine(filterFlow) { entries, datePair ->
                entries.filter { entry ->
                    isDayWithinRange(entry.key, datePair.first, datePair.second)
                }
            }.collect { map ->
                if (searchTerm.isNotBlank()) {
                    _filteredFlow.value = map.mapValues { entry ->
                        entry.value.filter { transaction ->
                            containsSearchTerm(
                                transaction,
                                searchTerm
                            )
                        }
                    }.toSortedMap(compareByDescending { it })
                } else {
                    _filteredFlow.value =
                        map.filter { it.value.isNotEmpty() }.toSortedMap(compareByDescending { it })
                }
            }
        }
    }

    fun updateSearchTerm(newValue: String) {
        searchTerm = newValue
        _filteredFlow.value = dailyTransactionsByDate.value.filterKeys { key ->
            isDayWithinRange(key, startDate, endDate)
        }.mapValues { entry ->
            entry.value.filter { transaction -> containsSearchTerm(transaction, searchTerm) }
        }.filter { it.value.isNotEmpty() }.toSortedMap(compareByDescending { it })
    }

    private fun isDayWithinRange(
        targetDate: LocalDate,
        startDate: LocalDate,
        endDate: LocalDate
    ): Boolean {
        if (targetDate.isEqual(startDate) || targetDate.isEqual(endDate)) {
            return true
        }
        return targetDate.isAfter(startDate) && targetDate.isBefore(endDate)
    }

    private fun containsSearchTerm(
        transaction: Transaction,
        searchTerm: String
    ): Boolean {
        return transaction.masked_cardNumber.contains(
            searchTerm
        ) || transaction.transactionId.contains(searchTerm)
    }

    fun updateDateRange(_startDate: LocalDate, _endDate: LocalDate) {
        startDate = _startDate
        endDate = _endDate
        filterFlow.value = Pair(startDate, endDate)
    }

    fun startBatch() {
        transactionsToPrint = listOf()
        batchViewModelState.update {
            it.copy(
                batchState = BatchState.INPROGRESS,
                batchProgress = 0,
                errors = 0
            )
        }
        var batchIndex = 0
        val batchList = dailyTransactionsByDate.value.values.flatten().sortedBy { it.localDateTime }
        val size = batchList.size
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                transactionRepository.resetOrderNumber()
            }
            flow {
                batchList.forEach { transaction ->
                    retry@ for (retryCount in 0..maxRetries) {
                        val success = makeCaptureRequest(transaction = transaction)
                        Log.d("Retry Loop", "Capture request success: $success\nTransaction: $transaction")
                        if (success) {
                            emit(
                                transaction.copy(
                                    checkStatus = CheckStatus.Closed,
                                    errorOnCapture = false
                                )
                            )
                            break@retry
                        } else {
                            if (retryCount >= 3) {
                                emit(transaction.copy(errorOnCapture = true))
                            } else if (transaction.checkStatus == CheckStatus.Error) {
                                emit(transaction)
                                break@retry
                            } else if (transaction.errMessage == GlobalConnectPaymentApplication.instance.resources.getString(
                                    R.string.err_no_record)){
                                emit(transaction.copy(checkStatus = CheckStatus.Closed))
                                break@retry
                            } else {
                                delay(2000)
                            }
                        }
                    }
                }
            }.catch {
            }.flowOn(Dispatchers.IO)
                .collect { transaction ->
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) {
                            transactionRepository.update(transaction)
                        }
                    }
                    batchIndex++
                    val newBatchState = if (batchIndex >= size) {
                        BatchState.COMPLETE
                    } else {
                        BatchState.INPROGRESS
                    }
                    batchViewModelState.update {
                        it.copy(
                            batchProgress = batchIndex.divideToPercent(size),
                            errors = if (transaction.errorOnCapture) it.errors + 1 else it.errors,
                            batchState = newBatchState
                        )
                    }
                    if (newBatchState == BatchState.COMPLETE) {
                        return@collect
                    }
                }

            transactionsToPrint = batchList.filter { !it.errorOnCapture }
            if (SysParam.getInstance().BatchPrint) {
                printTransactions()
            }
        }
    }

    fun printTransactions() {
        val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val profile = profileRepository.get() ?: Profile()
                paymentPrinter.printBatchReport(
                    context = GlobalConnectPaymentApplication.instance.applicationContext,
                    queryList = transactionsToPrint,
                    batchSummary = summarizeBatch(transactionsToPrint),
                    profile = profile,
                )
            }
        }
    }


    private suspend fun makeCaptureRequest(transaction: Transaction): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val callBack = object : CaptureCallback {
            override fun onSuccess(response: String, transaction: Transaction) {
                deferred.complete(true)
            }

            override fun onError(errorMessage: String, transaction: Transaction, procInfo: ProcInfo?) {
     //           writeToDebugLog(procInfo = procInfo, errMessage = errorMessage, transaction = transaction)
                deferred.complete(false)
            }
        }

        Log.d("BatchViewModel", "Starting capture request")
        var terminalMessage: TerminalMessage
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.obj.toString().isNotBlank()) {
                    when (val _terminalMessage = messageIdToTerminalMessage(msg, context)) {
                        is TerminalMessage.FAILED -> {
                            transaction.errorOnCapture = true
                            transaction.errMessage = _terminalMessage.message
                            callBack.onError(_terminalMessage.message, transaction, null)
                        }

                        is TerminalMessage.PROMPT -> {

                        }

                        is TerminalMessage.RETRY -> {
                            transaction.errorOnCapture = true
                            transaction.errMessage = _terminalMessage.message
                            callBack.onError(_terminalMessage.message, transaction, null)
                        }

                        is TerminalMessage.SUCCESS -> {
                            // Do nothing - let TxnHandler catch
                        }

                        is TerminalMessage.AUTHORIZING -> {}

                        is TerminalMessage.CONNECTING -> {}

                        is TerminalMessage.CONNECTIONFAILED -> {
                            transaction.errorOnCapture = true
                            transaction.errMessage = _terminalMessage.message
                            if (_terminalMessage.message == GlobalConnectPaymentApplication.instance.getString(
                                    ErrCode.ERR_COMM_RECEIVE_TIMEOUT.getId())) {
                                transaction.checkStatus = CheckStatus.Error
                            }
                            callBack.onError(_terminalMessage.message, transaction, null)
                        }
                    }
                    super.handleMessage(msg)
                }
            }
        }

        val txnHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.obj != null) {
                    val procInfo = msg.obj as ProcInfo
                    val statusCode = procInfo.RespCmd.StatusCode
                    if (statusCode != null) {
                        terminalMessage = statusCodeToTerminalMessage(statusCode, context)
                        when (terminalMessage) {
                            is TerminalMessage.AUTHORIZING -> {}

                            is TerminalMessage.CONNECTING -> {}

                            is TerminalMessage.CONNECTIONFAILED -> {
                                transaction.errorOnCapture = true
                                transaction.errMessage = terminalMessage.message
                                callBack.onError(terminalMessage.message, transaction, procInfo = procInfo.copy(RecvData = procInfo.RecvData.substringBefore("</Resp>")))
                            }

                            is TerminalMessage.FAILED -> {
                                transaction.errorOnCapture = true
                                transaction.errMessage = terminalMessage.message
                                callBack.onError(terminalMessage.message, transaction, procInfo = procInfo.copy(RecvData = procInfo.RecvData.substringBefore("</Resp>")))
                            }

                            is TerminalMessage.PROMPT -> {}

                            is TerminalMessage.RETRY -> {
                                if (terminalMessage.message == GlobalConnectPaymentApplication.instance.resources.getString(R.string.err_communication_timeout)) {
                                    transaction.checkStatus = CheckStatus.Error
                                }
                                transaction.errorOnCapture = true
                                transaction.errMessage = terminalMessage.message
                            }

                            is TerminalMessage.SUCCESS -> {
                                when (procInfo.TransLog.TxnResult) {
                                    "02" -> {
                                        transaction.checkStatus = CheckStatus.Closed
                                        transaction.errorOnCapture = false
                                        transaction.errMessage = ""
                                        callBack.onSuccess("", transaction)
                                    }

                                    else -> {
                                        transaction.errorOnCapture = true
                                        transaction.errMessage = terminalMessage.message
                                        callBack.onError(terminalMessage.message, transaction, procInfo = procInfo.copy(RecvData = procInfo.RecvData.substringBefore("</Resp>")))
                                    }
                                }
                            }
                        }
                    }
                }
                super.handleMessage(msg)
            }
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                TransAuthCapture(
                    handler,
                    txnHandler,
                    transaction.totalAmount,
                    transaction.tipAmount,
                    transaction
                ).run()
            }
        }
        return deferred.await()
    }
}

fun Int.divideToPercent(divideTo: Int): Int {
    return if (divideTo == 0) 0
    else (this / divideTo.toFloat() * 100).toInt()
}

interface CaptureCallback {
    fun onSuccess(response: String, transaction: Transaction)
    fun onError(errorMessage: String, transaction: Transaction, procInfo: ProcInfo?)
}

data class BatchSummary(
    var creditRecords: Int = 0,
    var creditTotal: String = "$0.00",
    var tipTotal: String = "$0.00",
)

fun summarizeBatch(transactions: List<Transaction>): BatchSummary {
    var creditRecords = 0
    var creditTotal = 0f
    var tax1Total = 0f
    var tax1Discount = 0f
    var tax2Total = 0f
    var tipTotal = 0f
    for (transaction in transactions) {
        if (!transaction.errorOnCapture) {
            creditRecords++
            creditTotal += transaction.totalAmount.toFloat()
            tipTotal += transaction.tipAmount.toFloat()
            tax1Total += transaction.tax1Amount.toFloat()
            tax1Discount += transaction.tax1DiscountAmount.toFloat()
            tax2Total += transaction.tax2Amount.toFloat()
        }
    }
    return BatchSummary(
        creditRecords = creditRecords,
        creditTotal = BigDecimal(creditTotal.toString()).setScale(2, RoundingMode.DOWN).toPlainString(),
        tipTotal = BigDecimal(tipTotal.toString()).setScale(2, RoundingMode.DOWN).toPlainString()
    )
}
