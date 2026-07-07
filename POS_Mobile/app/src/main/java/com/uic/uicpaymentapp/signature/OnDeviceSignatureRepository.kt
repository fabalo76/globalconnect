package com.uic.uicpaymentapp.signature

class OnDeviceSignatureRepository(private val signatureDao: SignatureDao): SignatureRepository {
    override suspend fun insert(vararg signature: Signature) {
        signatureDao.insert(*signature)
    }

    override suspend fun deleteOldSignatures(cutoffDate: String) {
        signatureDao.deleteOldTranscations(cutoffDate)
    }

    override suspend fun getSignatureFromTransactionId(transactionId: String): Signature? {
        return signatureDao.getSignatureFromTransactionId(transactionId)
    }
}