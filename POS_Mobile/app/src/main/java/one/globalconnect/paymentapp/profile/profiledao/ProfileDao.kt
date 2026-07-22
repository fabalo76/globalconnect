package one.globalconnect.paymentapp.profile.profiledao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import one.globalconnect.paymentapp.profile.Profile

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg profile: Profile)

    @Delete
    suspend fun delete(vararg profile: Profile)

    @Update
    suspend fun update(vararg profile: Profile)

    @Query("SELECT * FROM 'Profile'")
    suspend fun get(): Profile?
}