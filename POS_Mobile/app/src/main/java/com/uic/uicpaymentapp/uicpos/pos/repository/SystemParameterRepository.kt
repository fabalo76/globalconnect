package com.uic.uicpaymentapp.uicpos.pos.repository

import com.uic.uicpaymentapp.uicpos.pos.model.SysParam
import kotlinx.coroutines.flow.Flow

interface SystemParameterRepository {

    suspend fun getSysParamList(): List<SysParam>

    fun getSysParam(): Flow<SysParam?>

    suspend fun update(sysParam: SysParam): Int

    suspend fun addNew(sysParam: SysParam)
}