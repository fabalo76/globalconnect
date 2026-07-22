package one.globalconnect.paymentapp.signature

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Signature::class], version = 1, exportSchema = false)
abstract class SignatureDatabase : RoomDatabase() {

    abstract fun signatureDao(): SignatureDao

    companion object {
        @Volatile
        private var Instance: SignatureDatabase? = null

        fun getDatabase(context: Context): SignatureDatabase {
            // if the Instance is not null, return it, otherwise create a new database instance.
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(context, SignatureDatabase::class.java, "signature_database")
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { Instance = it }
            }
        }
    }
}