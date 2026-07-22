package one.globalconnect.paymentapp.systeminfo

import one.globalconnect.paymentapp.uicpos.pos.model.InfoMgmt


interface InfoMgmtRepository {
    suspend fun getInfoMgmt(): InfoMgmt?
    suspend fun insertAll(vararg infoMgmt:  InfoMgmt)

    suspend fun delete(vararg infoMgmt:  InfoMgmt)

    suspend fun update(vararg infoMgmt:  InfoMgmt)
}