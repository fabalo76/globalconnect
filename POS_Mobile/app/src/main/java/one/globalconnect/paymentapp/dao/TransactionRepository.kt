package one.globalconnect.paymentapp.dao

import one.globalconnect.paymentapp.transaction.PendingReversal
import one.globalconnect.paymentapp.transaction.Transaction
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    suspend fun insert(transaction: Transaction): Long

    suspend fun delete(vararg transactions: Transaction)

    /**
     * Deletes every transaction in the active terminal batch.
     *
     * @return the number of transaction rows removed.
     */
    suspend fun deleteAllTransactions(): Int

    suspend fun update(vararg transactions: Transaction)

    fun getAllTransactionsStream(): Flow<List<Transaction>>

    fun getAllOpenAndNeedTipTransactionsStream(): Flow<List<Transaction>>

    fun getNeedTipTransactionNumber(): Flow<Int>

    fun getAllNeedTipTransactionsStream(): Flow<List<Transaction>>

    fun getTransactionCount(): Flow<Int>

    fun getOpenAndNeedTipTransactionNumber(): Flow<Int>

    suspend fun getOpenCheckInByFolio(folioNumber: String): Transaction?

    fun getOpenCheckIns(): Flow<List<Transaction>>

    fun getTransactionPage(pageSize: Int, offset: Int): Flow<List<Transaction>>

    suspend fun getLastTransaction(): Transaction?

    fun getAllFromDateRange(startDate: String, endDate: String): Flow<List<Transaction>>

    fun getAllFromDate(date: String): Flow<List<Transaction>>

    fun getTransactionsFromDate(date: String): Flow<List<Transaction>>

    fun getTransactionFlowFromId(id: Int): Flow<Transaction>

    suspend fun getTransactionFromId(id : Int): Transaction?

    suspend fun getTransactionFromTransactionIdAndAuthCodeAndPAN(transactionId: String, authCode: String, PAN: String): Transaction?

    suspend fun deleteOldTransactions(cutoffDate: String)

    suspend fun oldTransactionsNumber(cutoffDate: String): Int

    suspend fun resetOrderNumber()

    suspend fun insertReversal(reversal: PendingReversal): Long

    suspend fun updateReversal(reversal: PendingReversal)

    suspend fun deleteReversal(reversal: PendingReversal)

    /**
     * Deletes every queued reversal awaiting transmission.
     *
     * @return the number of pending reversal rows removed.
     */
    suspend fun deleteAllPendingReversals(): Int

    suspend fun getPendingReversals(acquirerId: String): List<PendingReversal>

    /** Returns every reversal currently awaiting transmission, oldest first. */
    suspend fun getAllPendingReversals(): List<PendingReversal>
}
