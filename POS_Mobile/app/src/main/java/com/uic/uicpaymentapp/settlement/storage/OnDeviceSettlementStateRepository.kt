package com.uic.uicpaymentapp.settlement.storage

import android.util.Log
import com.uic.uicpaymentapp.records.dateTimeFormatter
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

private const val TAG = "SettlementStateRepo"

class OnDeviceSettlementStateRepository(
    private val dao: SettlementStateDao,
) : SettlementStateRepository {

    override fun observeStates(): Flow<List<SettlementState>> = dao.observeStates()

    override suspend fun markPending(acquirerId: String, acquirerName: String, currencySymbol: String) {
        if (acquirerId.isBlank()) return
        val existing = runCatching { dao.get(acquirerId) }
            .onFailure { error -> Log.e(TAG, "Unable to load settlement state for acquirer=$acquirerId", error) }
            .getOrNull()
        val pendingSince = if (existing?.pending == true && !existing.pendingSince.isNullOrBlank()) {
            existing.pendingSince
        } else {
            LocalDateTime.now().format(dateTimeFormatter)
        }
        val updated = SettlementState(
            acquirerId = acquirerId,
            acquirerName = acquirerName,
            currencySymbol = currencySymbol,
            pending = true,
            pendingSince = pendingSince,
            lastSnapshot = existing?.lastSnapshot,
        )
        runCatching { dao.upsert(updated) }
            .onFailure { error -> Log.e(TAG, "Unable to mark settlement pending for acquirer=$acquirerId", error) }
    }

    override suspend fun clearPending(acquirerId: String) {
        if (acquirerId.isBlank()) return
        val existing = runCatching { dao.get(acquirerId) }
            .onFailure { error -> Log.e(TAG, "Unable to load settlement state for acquirer=$acquirerId", error) }
            .getOrNull() ?: return
        if (!existing.pending && existing.pendingSince.isNullOrBlank()) {
            return
        }
        val updated = existing.copy(pending = false, pendingSince = null)
        runCatching { dao.upsert(updated) }
            .onFailure { error -> Log.e(TAG, "Unable to clear settlement pending flag for acquirer=$acquirerId", error) }
    }

    override suspend fun storeSnapshot(snapshot: SettlementSnapshot) {
        val existing = runCatching { dao.get(snapshot.acquirerId) }
            .onFailure { error -> Log.e(TAG, "Unable to load settlement state for acquirer=${snapshot.acquirerId}", error) }
            .getOrNull()
        val updated = SettlementState(
            acquirerId = snapshot.acquirerId,
            acquirerName = snapshot.acquirerName,
            currencySymbol = snapshot.currencySymbol,
            pending = existing?.pending ?: false,
            pendingSince = existing?.pendingSince,
            lastSnapshot = snapshot,
        )
        runCatching { dao.upsert(updated) }
            .onFailure { error -> Log.e(TAG, "Unable to store settlement snapshot for acquirer=${snapshot.acquirerId}", error) }
    }

    override suspend fun getState(acquirerId: String): SettlementState? {
        if (acquirerId.isBlank()) return null
        return runCatching { dao.get(acquirerId) }
            .onFailure { error -> Log.e(TAG, "Unable to load settlement state for acquirer=$acquirerId", error) }
            .getOrNull()
    }

    override suspend fun isPending(acquirerId: String): Boolean {
        if (acquirerId.isBlank()) return false
        return runCatching { dao.isPending(acquirerId) }
            .onFailure { error -> Log.e(TAG, "Unable to check settlement state for acquirer=$acquirerId", error) }
            .getOrNull() ?: false
    }
}
