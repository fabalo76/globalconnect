package com.uic.uicpaymentapp.transaction

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Stores the information required to generate and resend a reversal message when the
 * original financial transaction fails to complete.
 */
@Entity(tableName = "pending_reversals")
data class PendingReversal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val acquirerId: String,
    val transactionType: String,
    val stan: String,
    val originalMessageType: String,
    val processingCode: String,
    val header: String?,
    @ColumnInfo(name = "field_values") val fieldValues: Map<Int, String>,
    val createdAt: String,
    val lastAttemptAt: String? = null,
    val attempts: Int = 0,
    val reason: ReversalReason = ReversalReason.NO_RESPONSE,
    val lastResponseCode: String? = null,
    val invoiceNumber: String = "",
    val transactionAmount: String = "0.00",
    val maskedPan: String = "",
    val cardBrand: String = "",
)

/** Explains why the reversal was queued. */
enum class ReversalReason {
    PENDING,
    NO_RESPONSE,
    RESPONSE_91,
    RESPONSE_96,
    UNKNOWN
}

/**
 * Minimal data required to print a reversal receipt once the host confirms the reversal.
 */
data class ReversalReceiptData(
    val timestamp: LocalDateTime,
    val maskedPan: String,
    val cardBrand: String,
    val rrn: String,
    val invoiceNumber: String,
    val transactionTypeLabel: String,
    val totalAmountText: String,
)
