package one.globalconnect.paymentapp.systeminfo

import one.globalconnect.paymentapp.uicpos.pos.model.InfoMgmt

class onDeviceInfoMgmtRepository(private val infoMgmtDao: InfoMgmtDao): InfoMgmtRepository {
    override suspend fun getInfoMgmt(): InfoMgmt? {
        return infoMgmtDao.getInfoMgmt()
    }
    override suspend fun insertAll(vararg infoMgmt: InfoMgmt) {
        infoMgmtDao.insertAll(*infoMgmt)
    }

    override suspend fun delete(vararg infoMgmt: InfoMgmt) {
        infoMgmtDao.delete(*infoMgmt)
    }

    override suspend fun update(vararg infoMgmt: InfoMgmt) {
        infoMgmtDao.update(*infoMgmt)
    }

}