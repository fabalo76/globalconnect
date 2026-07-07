package com.uic.uicpaymentapp.signature

interface SignatureRepository {
    suspend fun insert(vararg signature: Signature)

    suspend fun deleteOldSignatures(cutoffDate: String)

    suspend fun getSignatureFromTransactionId(transactionId: String): Signature?
}