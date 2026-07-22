package one.globalconnect.paymentapp.systeminfo

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import one.globalconnect.paymentapp.uicpos.pos.model.InfoMgmt

@Dao
interface InfoMgmtDao {

    @Query("SELECT * from InfoMgmt")
    fun getInfoMgmt(): InfoMgmt?
    @Insert
    suspend fun insertAll(vararg infoMgmt: InfoMgmt)

    @Delete
    suspend fun delete(vararg infoMgmt: InfoMgmt)

    @Update
    suspend fun update(vararg infoMgmt: InfoMgmt)
}