package one.globalconnect.paymentapp.profile.profiledao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import one.globalconnect.paymentapp.profile.Profile

/**
 * Database class with a singleton Instance object.
 */
@Database(entities = [Profile::class], version = 1, exportSchema = false)
abstract class ProfileDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var Instance: ProfileDatabase? = null

        fun getDatabase(context: Context): ProfileDatabase {
            // if the Instance is not null, return it, otherwise create a new database instance.
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(context, ProfileDatabase::class.java, "profile_database")
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { Instance = it }
            }
        }
    }
}
