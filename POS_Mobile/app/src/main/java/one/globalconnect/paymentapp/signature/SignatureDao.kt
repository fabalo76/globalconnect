package one.globalconnect.paymentapp.signature

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SignatureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg signature: Signature)

    @Query("DELETE from `Signature` WHERE date <= :cutoffDate")
    suspend fun deleteOldTranscations(cutoffDate: String)

    @Query("SELECT * FROM `Signature` WHERE transactionId = :transactionId")
    fun getSignatureFromTransactionId(transactionId: String): Signature?
}