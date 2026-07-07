package com.uic.uicpaymentapp.records

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.tms.payment_app.TMSDATA
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.dao.TransactionRepository
import com.uic.uicpaymentapp.transaction.CheckStatus
import com.uic.uicpaymentapp.transaction.Transaction
import com.uic.uicpaymentapp.transactions.TransactionReportBridge
import com.uic.uicpaymentapp.transactions.TransactionReportEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.SortedMap

class QuickTipViewModel(
    private val transactionRepository: TransactionRepository,
    private val tmsDatabase: TMSDATA,
) : ViewModel() {
    var startDate: LocalDate by mutableStateOf(LocalDate.now().minusMonths(3))

    var endDate: LocalDate by mutableStateOf(LocalDate.now())

    var searchTerm by mutableStateOf("")

    private val filterFlow =
        MutableStateFlow(Pair(startDate, endDate))

    var tippedTransactions by mutableIntStateOf(0)

    private val _transactionFlow: MutableStateFlow<Transaction> =
        MutableStateFlow(Transaction().applyFormattedTimes())

    val transactionFlow: StateFlow<Transaction> = _transactionFlow

    /**
     * Holds home ui state. The list of transactions are retrieved from [TransactionRepository] and mapped to
     * [SortedMap<LocalDate, MutableList<Transaction>>]
     */
    private val dailyTransactionsByDate: MutableStateFlow<SortedMap<LocalDate, MutableList<Transaction>>> =
        MutableStateFlow(
            sortedMapOf()
        )

    private val _filteredFlow = MutableStateFlow(sortedMapOf<LocalDate, List<Transaction>>())
    val filteredFlow: StateFlow<SortedMap<LocalDate, List<Transaction>>> = _filteredFlow

    init {
        viewModelScope.launch {
            transactionRepository.getAllNeedTipTransactionsStream(
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

    fun updateTippedTransactionNumber() {
        tippedTransactions = dailyTransactionsByDate.value.values.flatten().filter {
            it.tipAmount != "0.00"
        }.size
    }

    fun swapTransaction(id: Int) {
        _transactionFlow.value = dailyTransactionsByDate.value.values.flatten()
            .find { it.id == id }?.applyFormattedTimes() ?: Transaction().applyFormattedTimes()
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

    fun updateTransactions() {
        val copy = dailyTransactionsByDate.value
        copy.values.flatten().forEach {
            if (it.tipAmount != "0.00") {
                viewModelScope.launch {
                    val updated = it.copy(checkStatus = CheckStatus.Open)
                    withContext(Dispatchers.IO) {
                        transactionRepository.update(updated)
                    }
                    TransactionReportBridge.reportTransaction(
                        UICApplication.instance,
                        updated,
                        tmsDatabase,
                        TransactionReportEvent.TipAdjust,
                    )
                }
            }
        }
        tippedTransactions = 0
    }

    private fun tempUpdateTransaction(transaction: Transaction) {
        val transactionsSnapshot = dailyTransactionsByDate.value
        transactionsSnapshot.values.flatten()
            .find { it.id == transaction.id }?.apply {
                tipAmount = transaction.tipAmount
                totalAmount = transaction.totalAmount
            }
        dailyTransactionsByDate.value = transactionsSnapshot
        updateTippedTransactionNumber()
    }

    fun confirmTip(newTipAmount: String) {
        var transaction = _transactionFlow.value
        val newTotal = BigDecimal(transaction.subTotal).add(BigDecimal(newTipAmount))
            .setScale(2, RoundingMode.HALF_DOWN).toPlainString()
        transaction = transaction.copy(tipAmount = newTipAmount, totalAmount = newTotal)
            .applyFormattedTimes()
        _transactionFlow.value = transaction
        tempUpdateTransaction(transaction)
    }

    fun updateDateRange(_startDate: LocalDate, _endDate: LocalDate) {
        startDate = _startDate
        endDate = _endDate
        filterFlow.value = Pair(startDate, endDate)
    }

    fun getNextTransactionId(originalId: Int): Int {
        val transactions = dailyTransactionsByDate.value.values.flatten().sortedByDescending { it.localDateTime }
        val index = transactions.indexOfFirst { it.id == originalId }
        return if (index < 0) {
            -1
        } else if (index == transactions.size - 1) {
            transactions.first().id
        } else {
            transactions[index + 1].id
        }
    }
}
