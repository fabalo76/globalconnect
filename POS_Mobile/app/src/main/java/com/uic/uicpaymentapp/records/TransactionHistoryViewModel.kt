package com.uic.uicpaymentapp.records

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.uicpaymentapp.dao.TransactionRepository
import com.uic.uicpaymentapp.transaction.Transaction
import com.uic.uicpaymentapp.transaction.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.SortedMap


class TransactionHistoryViewModel(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    var startDate: LocalDate by mutableStateOf(LocalDate.now().minusDays(7))

    var endDate: LocalDate by mutableStateOf(LocalDate.now())

    var searchTerm by mutableStateOf("")

    private val filterFlow =
        MutableStateFlow(Pair(startDate, endDate))

    /**
     * Holds home ui state. The list of transactions are retrieved from [TransactionRepository] and mapped to
     * SortedMap<LocalDate, MutableList<Transaction>>
     */
    private val dailyTransactionsByDate: MutableStateFlow<SortedMap<LocalDate, MutableList<Transaction>>> =
        MutableStateFlow(
            sortedMapOf()
        )

    private val _filteredFlow = MutableStateFlow(sortedMapOf<LocalDate, List<Transaction>>())
    val filteredFlow: StateFlow<SortedMap<LocalDate, List<Transaction>>> = _filteredFlow

    private val _needTipTransactionFlow = MutableStateFlow(0)
    val needTipTransactionFlow: StateFlow<Int> = _needTipTransactionFlow

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading


    companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }

    init {
        viewModelScope.launch {
            transactionRepository.getAllTransactionsStream(
            ).catch { exception ->
                Log.d("TransactionHistoryViewModel", exception.message.toString())
            }.map {
                val sortedTransactions = mutableMapOf<LocalDate, MutableList<Transaction>>()
                for (transaction in it) {
                    if (transaction.type == TransactionType.CHECKIN) {
                        continue
                    }
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
                _isLoading.value = false
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

        viewModelScope.launch {
            transactionRepository.getNeedTipTransactionNumber(

            ).catch { exception ->
                Log.d("TransactionHistoryViewModel", exception.message.toString())
            }.collect {
                Log.d("TransactionHistoryViewModel", "Collecting need tips")
                _needTipTransactionFlow.value = it
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

    fun updateDateRange(inputStartDate: LocalDate, inputEndDate: LocalDate) {
        startDate = inputStartDate
        endDate = inputEndDate
        filterFlow.value = Pair(startDate, endDate)
    }
}
