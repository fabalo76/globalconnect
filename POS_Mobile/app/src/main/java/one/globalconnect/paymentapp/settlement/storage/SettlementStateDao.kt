package one.globalconnect.paymentapp.settlement.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettlementStateDao {
    @Upsert
    suspend fun upsert(state: SettlementState)

    @Query("SELECT * FROM settlement_state WHERE acquirerId = :acquirerId LIMIT 1")
    suspend fun get(acquirerId: String): SettlementState?

    @Query("SELECT pending FROM settlement_state WHERE acquirerId = :acquirerId LIMIT 1")
    suspend fun isPending(acquirerId: String): Boolean?

    @Query("SELECT * FROM settlement_state")
    fun observeStates(): Flow<List<SettlementState>>
}
