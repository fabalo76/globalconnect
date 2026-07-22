package one.globalconnect.paymentapp.settlement.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settlement_state")
data class SettlementState(
    @PrimaryKey val acquirerId: String,
    val acquirerName: String,
    val currencySymbol: String,
    val pending: Boolean,
    val pendingSince: String?,
    val lastSnapshot: SettlementSnapshot?,
)
