package one.globalconnect.paymentapp.settlement.storage

import kotlinx.coroutines.flow.Flow

interface SettlementStateRepository {
    fun observeStates(): Flow<List<SettlementState>>
    suspend fun markPending(acquirerId: String, acquirerName: String, currencySymbol: String)
    suspend fun clearPending(acquirerId: String)
    suspend fun storeSnapshot(snapshot: SettlementSnapshot)
    suspend fun getState(acquirerId: String): SettlementState?
    suspend fun isPending(acquirerId: String): Boolean
}
