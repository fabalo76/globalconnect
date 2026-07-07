package com.uic.uicpaymentapp.uicpos.pos.repository

import androidx.room.*
import com.uic.uicpaymentapp.uicpos.pos.model.SysParam
import kotlinx.coroutines.flow.Flow

@Dao
interface SysParamDao {

    @Query("SELECT * from SysParam")
    fun getSysParam(): Flow<SysParam?>

    @Query("SELECT * from SysParam")
    fun getSysParamList(): List<SysParam>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sysParam: SysParam)

    @Update
    suspend fun update(sysParam: SysParam): Int

    @Delete
    suspend fun delete(sysParam: SysParam)
}