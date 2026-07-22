package one.globalconnect.paymentapp.dao

import android.util.Log
import one.globalconnect.paymentapp.records.dateFormatter
import one.globalconnect.paymentapp.records.dateTimeFormatter
import one.globalconnect.paymentapp.transaction.CheckStatus
import one.globalconnect.paymentapp.transaction.PendingReversal
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class OnDeviceTransactionRepository(private val transactionDao: TransactionDao): TransactionRepository {
    override suspend fun insert(transaction: Transaction): Long = withContext(Dispatchers.IO) {
        val shouldResetOrderNo = transactionDao.shouldResetOrderNo()
        val newOrderNo = if (shouldResetOrderNo) {
            transactionDao.setResetFlagFalse()
            1
        } else {
            val lastOrderNo = transactionDao.getLastOrderNo() ?: 0
            (lastOrderNo % 999) + 1
        }
        transactionDao.insert(transaction.copy(orderNo = newOrderNo))
    }

    override suspend fun delete(vararg transactions: Transaction) {
        transactionDao.delete(*transactions)
    }

    override suspend fun update(vararg transactions: Transaction) {
        transactionDao.update(*transactions)
    }

    override fun getTransactionPage(pageSize: Int, offset: Int): Flow<List<Transaction>> = transactionDao.getTransactionPage(pageSize, offset)

    override suspend fun getLastTransaction(): Transaction? = withContext(Dispatchers.IO) {
        transactionDao.getLastTransaction()
    }

    override fun getAllTransactionsStream(): Flow<List<Transaction>> = transactionDao.getAllTransactionsStream()

    override fun getTransactionFlowFromId(id: Int): Flow<Transaction> = transactionDao.getTransactionFlowFromId(id)

    override fun getAllOpenAndNeedTipTransactionsStream(): Flow<List<Transaction>> = transactionDao.getAllOpenAndNeedTipTransactions(CheckStatus.NeedTip, CheckStatus.Open)

    override fun getTransactionCount(): Flow<Int> = transactionDao.getTransactionCount()

    override fun getOpenAndNeedTipTransactionNumber(): Flow<Int> = transactionDao.getNumberOfTransactionsByTwoCheckStatuses(CheckStatus.Open, CheckStatus.NeedTip)

    override fun getNeedTipTransactionNumber(): Flow<Int> = transactionDao.getNumberOfTransactionsByCheckStatus(CheckStatus.NeedTip)

    override fun getAllNeedTipTransactionsStream(): Flow<List<Transaction>> = transactionDao.getAllTransactionsByCheckstatus(CheckStatus.NeedTip)

    override suspend fun getOpenCheckInByFolio(folioNumber: String): Transaction? = withContext(Dispatchers.IO) {
        transactionDao.getOpenCheckInByFolio(TransactionType.CHECKIN, folioNumber, CheckStatus.Open)
    }

    override fun getOpenCheckIns(): Flow<List<Transaction>> =
        transactionDao.getOpenCheckIns(TransactionType.CHECKIN, CheckStatus.Open)

    override fun getAllFromDate(date: String): Flow<List<Transaction>> {
        return try {
            val startDateTime = LocalDateTime.of(LocalDate.parse(date, dateFormatter), LocalTime.MIN)
            val endDateTime = LocalDateTime.of(LocalDate.parse(date, dateFormatter), LocalTime.MAX)
            transactionDao.getAllFromDateRange(startDateTime.format(dateTimeFormatter), endDateTime.format(
                dateTimeFormatter))
        } catch (e: Exception) {
            Log.d("TransactionRepository", "Failed to parse date string: $date")
            emptyFlow()
        }
    }

    override fun getAllFromDateRange(
        startDate: String,
        endDate: String
    ): Flow<List<Transaction>> {
        return try {
            val startDateTime = LocalDateTime.of(LocalDate.parse(startDate, dateFormatter), LocalTime.MIN)
            val endDateTime = LocalDateTime.of(LocalDate.parse(endDate, dateFormatter), LocalTime.MAX)
            transactionDao.getAllFromDateRange(startDateTime.format(dateTimeFormatter), endDateTime.format(
                dateTimeFormatter))
        } catch (e: Exception) {
            Log.d("TransactionRepository", "Failed to parse date string: $startDate to $endDate")
            emptyFlow()
        }
    }

    override fun getTransactionsFromDate(date: String): Flow<List<Transaction>> {
        return try {
            val startDateTime = LocalDateTime.of(LocalDate.parse(date, dateFormatter), LocalTime.MIN)
            val endDateTime = LocalDateTime.of(LocalDate.parse(date, dateFormatter), LocalTime.MAX)
            transactionDao.getAllFromDateRange(startDateTime.format(dateTimeFormatter), endDateTime.format(
                dateTimeFormatter))
        } catch (e: Exception) {
            Log.d("TransactionRepository", "Failed to parse date string: $date")
            emptyFlow()
        }
    }

    override suspend fun getTransactionFromId(id: Int): Transaction? {
        return transactionDao.getTransactionFromId(id = id)
    }

    override suspend fun getTransactionFromTransactionIdAndAuthCodeAndPAN(
        transactionId: String,
        authCode: String,
        PAN: String,
    ): Transaction? {
        return transactionDao.getTransactionFromTransactionIdAndAuthCodeAndPAN(transactionId, authCode, PAN)
    }

    override suspend fun deleteOldTransactions(cutoffDate: String) = withContext(Dispatchers.IO) {
        val startDateTime = LocalDateTime.of(LocalDate.parse(cutoffDate, dateFormatter), LocalTime.MIN)
        transactionDao.deleteOldTransactions(startDateTime.format(dateTimeFormatter))
    }

    override suspend fun oldTransactionsNumber(cutoffDate: String): Int = withContext(Dispatchers.IO) {
        transactionDao.oldTransactionsNumber(cutoffDate).size
    }

    override suspend fun resetOrderNumber() = withContext(Dispatchers.IO) {
        transactionDao.setResetFlagTrue()
    }

    override suspend fun insertReversal(reversal: PendingReversal): Long = withContext(Dispatchers.IO) {
        transactionDao.insertReversal(reversal)
    }

    override suspend fun updateReversal(reversal: PendingReversal) = withContext(Dispatchers.IO) {
        transactionDao.updateReversal(reversal)
    }

    override suspend fun deleteReversal(reversal: PendingReversal) = withContext(Dispatchers.IO) {
        transactionDao.deleteReversal(reversal)
    }

    override suspend fun getPendingReversals(acquirerId: String): List<PendingReversal> = withContext(Dispatchers.IO) {
        transactionDao.getPendingReversals(acquirerId)
    }
}
