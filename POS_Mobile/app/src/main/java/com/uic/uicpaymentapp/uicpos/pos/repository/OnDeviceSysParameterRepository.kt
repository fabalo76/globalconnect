package com.uic.uicpaymentapp.uicpos.pos.repository

import com.uic.uicpaymentapp.uicpos.pos.model.SysParam
import kotlinx.coroutines.flow.Flow

class OnDeviceSysParameterRepository(private val sysParamDao: SysParamDao) :
    SystemParameterRepository {
    override suspend fun getSysParamList(): List<SysParam> {
        return sysParamDao.getSysParamList()
    }

    override fun getSysParam(): Flow<SysParam?> {
        return sysParamDao.getSysParam()
    }

    override suspend fun update(sysParam: SysParam): Int {
        return sysParamDao.update(sysParam)
    }

    override suspend fun addNew(sysParam: SysParam) {
        sysParamDao.insert(sysParam)
    }
}