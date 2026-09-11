package one.globalconnect.paymentapp.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import one.globalconnect.paymentapp.transaction.CheckStatus
import one.globalconnect.paymentapp.transaction.PendingReversal
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Delete
    suspend fun delete(vararg transactions: Transaction)

    /**
     * Deletes every transaction in the active terminal batch.
     *
     * @return the number of transaction rows removed.
     */
    @Query("DELETE FROM `transaction`")
    suspend fun deleteAllTransactions(): Int

    @Update
    suspend fun update(vararg transactions: Transaction)

    @Query("SELECT * FROM `transaction`")
    fun getAllTransactionsStream(): Flow<List<Transaction>>

    @Query("SELECT * from 'transaction' WHERE checkStatus == :checkStatus")
    fun getAllTransactionsByCheckstatus(checkStatus: CheckStatus): Flow<List<Transaction>>

    @Query("SELECT * from 'transaction' WHERE checkStatus == :checkStatus1 OR checkStatus == :checkStatus2")
    fun getAllOpenAndNeedTipTransactions(checkStatus1: CheckStatus, checkStatus2: CheckStatus): Flow<List<Transaction>>

    @Query("SELECT * FROM 'transaction' " +
            "WHERE localDateTime BETWEEN :startTime and :endTime"
    )
    fun getAllFromDateRange(startTime: String, endTime: String): Flow<List<Transaction>>

    @Query("SELECT * from 'transaction' ORDER BY localDateTime DESC LIMIT :pageSize OFFSET :offset")
    fun getTransactionPage(pageSize: Int, offset: Int): Flow<List<Transaction>>

    @Query("SELECT * FROM 'transaction' ORDER BY id DESC LIMIT 1")
    suspend fun getLastTransaction(): Transaction?

    @Query("SELECT COUNT(*) FROM 'transaction'")
    fun getTransactionCount(): Flow<Int>

    @Query("SELECT COUNT(*) from 'transaction' WHERE checkStatus == :checkStatus OR checkStatus == :checkStatus1")
    fun getNumberOfTransactionsByTwoCheckStatuses(checkStatus: CheckStatus, checkStatus1: CheckStatus): Flow<Int>

    @Query("SELECT COUNT(*) from 'transaction' WHERE checkStatus == :checkStatus")
    fun getNumberOfTransactionsByCheckStatus(checkStatus: CheckStatus): Flow<Int>

    @Query("SELECT * from `transaction` WHERE id == :id LIMIT 1")
    fun getTransactionFlowFromId(id: Int): Flow<Transaction>

    @Query("SELECT * from 'transaction' WHERE id == :id")
    suspend fun getTransactionFromId(id : Int): Transaction?

    @Query("SELECT * from 'transaction' WHERE transactionId == :transactionId AND authCode == :authCode AND hashed_cardNumber == :Hashed_PAN")
    suspend fun getTransactionFromTransactionIdAndAuthCodeAndPAN(transactionId: String, authCode: String, Hashed_PAN: String) : Transaction?

    @Query("SELECT * from 'transaction' WHERE type == :type AND folioNumber == :folio AND checkStatus == :status LIMIT 1")
    suspend fun getOpenCheckInByFolio(type: TransactionType, folio: String, status: CheckStatus): Transaction?

    @Query("SELECT * from 'transaction' WHERE type == :type AND checkStatus == :status ORDER BY localDateTime")
    fun getOpenCheckIns(type: TransactionType, status: CheckStatus): Flow<List<Transaction>>

    @Query("DELETE from 'transaction' WHERE localDateTime <= :cutoffDate")
    fun deleteOldTransactions(cutoffDate: String)

    @Query("SELECT * from 'transaction' WHERE localDateTime <= :cutoffDate")
    fun oldTransactionsNumber(cutoffDate: String): List<Transaction>

    @Query("SELECT orderNo FROM 'transaction' ORDER BY id DESC LIMIT 1")
    fun getLastOrderNo(): Int?

    @Query("SELECT should_reset FROM reset_state WHERE id = 1")
    fun shouldResetOrderNo(): Boolean

    @Query("UPDATE reset_state SET should_reset = 0 WHERE id = 1")
    fun setResetFlagFalse()

    @Query("UPDATE reset_state SET should_reset = 1 WHERE id = 1")
    fun setResetFlagTrue()

    @Insert
    suspend fun insertReversal(reversal: PendingReversal): Long

    @Update
    suspend fun updateReversal(reversal: PendingReversal)

    @Delete
    suspend fun deleteReversal(reversal: PendingReversal)

    /**
     * Deletes every queued reversal awaiting transmission.
     *
     * @return the number of pending reversal rows removed.
     */
    @Query("DELETE FROM pending_reversals")
    suspend fun deleteAllPendingReversals(): Int

    @Query("SELECT * FROM pending_reversals WHERE acquirerId = :acquirerId ORDER BY createdAt ASC")
    suspend fun getPendingReversals(acquirerId: String): List<PendingReversal>

    @Query("SELECT * FROM pending_reversals ORDER BY createdAt ASC")
    suspend fun getAllPendingReversals(): List<PendingReversal>
}
