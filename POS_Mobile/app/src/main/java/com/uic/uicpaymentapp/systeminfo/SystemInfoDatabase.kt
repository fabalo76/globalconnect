package com.uic.uicpaymentapp.systeminfo

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.uic.uicpaymentapp.uicpos.pos.model.InfoMgmt

/**
 * Database class with a singleton Instance object.
 */
@Database(entities = [InfoMgmt::class], version = 1, exportSchema = false)
abstract class InfoMgmtDatabase : RoomDatabase() {

    abstract fun infoMgmtDao(): InfoMgmtDao

    companion object {
        @Volatile
        private var Instance: InfoMgmtDatabase? = null

        fun getDatabase(context: Context): InfoMgmtDatabase {
            // if the Instance is not null, return it, otherwise create a new database instance.
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(context, InfoMgmtDatabase::class.java, "info_mgmt_database")
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { Instance = it }
            }
        }
    }
}

