package com.uic.uicpaymentapp.dao

import android.content.Context
import android.database.sqlite.SQLiteException as AndroidSQLiteException
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.uic.uicpaymentapp.security.EncryptionUtil
import com.uic.uicpaymentapp.settlement.storage.SettlementState
import com.uic.uicpaymentapp.settlement.storage.SettlementStateDao
import com.uic.uicpaymentapp.transaction.PendingReversal
import com.uic.uicpaymentapp.transaction.ResetState
import com.uic.uicpaymentapp.transaction.Transaction
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(
    entities = [Transaction::class, ResetState::class, PendingReversal::class, SettlementState::class],
    version = 8,
    exportSchema = false
)
@TypeConverters(AppTypeConverters::class)
abstract class TransactionDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun settlementStateDao(): SettlementStateDao

    companion object {
        private const val TAG = "TransactionDatabase"
        private const val DATABASE_NAME = "transaction_database"
        private const val TARGET_PAGE_SIZE = 16_384
        private const val DATABASE_PREFS_NAME = "transaction_database_prefs"
        private const val KEY_CIPHER_PAGE_SIZE = "cipher_page_size"
        private const val NO_RECORDED_PAGE_SIZE = -1

        @Volatile
        private var Instance: TransactionDatabase? = null

        fun getDatabase(context: Context): TransactionDatabase {
            return Instance ?: synchronized(this) {
                Instance ?: buildDatabase(context).also { Instance = it }
            }
        }

        private fun buildDatabase(context: Context, attempt: Int = 0): TransactionDatabase {
            val appContext = context.applicationContext
            val passphrase: ByteArray = EncryptionUtil.getDatabasePassphrase(appContext)
            val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
            val isFreshDatabase = !databaseFile.exists() || databaseFile.length() == 0L
            val storedCipherPageSize = getStoredCipherPageSize(appContext)
            val shouldConfigurePageSize = isFreshDatabase || storedCipherPageSize == TARGET_PAGE_SIZE

            val factory = SupportOpenHelperFactory(
                passphrase,
                createCipherHook(shouldConfigurePageSize),
                false
            )

            val builder = Room.databaseBuilder(
                appContext,
                TransactionDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .addCallback(createDatabaseCallback(appContext))
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

            return try {
                builder.build()
            } catch (error: Throwable) {
                if (attempt == 0 && error.isDatabaseOpenError()) {
                    Log.w(
                        TAG,
                        "Failed to open encrypted transaction database. Recreating a fresh database instance.",
                        error
                    )
                    resetDatabaseFiles(appContext)
                    buildDatabase(appContext, attempt + 1)
                } else {
                    throw error
                }
            }
        }

        private fun createCipherHook(shouldConfigurePageSize: Boolean): SQLiteDatabaseHook {
            return object : SQLiteDatabaseHook {
                override fun preKey(connection: SQLiteConnection) {
                    // no-op
                }

                override fun postKey(connection: SQLiteConnection) {
                    if (shouldConfigurePageSize) {
                        connection.ensureCipherPageSize()
                    }
                }
            }
        }

        private fun SQLiteConnection.ensureCipherPageSize() {
            execute("PRAGMA cipher_page_size = $TARGET_PAGE_SIZE", emptyArray<Any>(), null)
        }

        private fun createDatabaseCallback(context: Context) = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL("INSERT INTO reset_state (id, should_reset) VALUES (1, 0)")
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                enforcePageSize(context, db)
            }
        }

        private fun enforcePageSize(context: Context, db: SupportSQLiteDatabase) {
            val currentSize = db.query("PRAGMA page_size;").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).toInt() else null
            } ?: return

            val finalSize = if (currentSize != TARGET_PAGE_SIZE) {
                val migrationResult = runCatching {
                    db.execSQL("PRAGMA cipher_page_size = $TARGET_PAGE_SIZE;")
                    db.execSQL("VACUUM;")
                }

                if (migrationResult.isFailure) {
                    Log.w(
                        TAG,
                        "Unable to migrate SQLCipher page size to $TARGET_PAGE_SIZE",
                        migrationResult.exceptionOrNull()
                    )
                    currentSize
                } else {
                    db.query("PRAGMA page_size;").use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0).toInt() else TARGET_PAGE_SIZE
                    }
                }
            } else {
                currentSize
            }

            persistCipherPageSize(context, finalSize)
        }

        private fun resetDatabaseFiles(context: Context) {
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            val deleted = context.deleteDatabase(DATABASE_NAME)
            if (!deleted && databaseFile.exists()) {
                runCatching { databaseFile.delete() }
            }
            listOf("-journal", "-shm", "-wal").forEach { suffix ->
                val sidecar = File(databaseFile.path + suffix)
                if (sidecar.exists()) {
                    runCatching { sidecar.delete() }
                }
            }
            clearStoredCipherPageSize(context)
        }

        private fun Throwable.isDatabaseOpenError(): Boolean {
            return this is AndroidSQLiteException ||
                (cause?.isDatabaseOpenError() == true)
        }

        private fun getStoredCipherPageSize(context: Context): Int {
            return context
                .getSharedPreferences(DATABASE_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CIPHER_PAGE_SIZE, NO_RECORDED_PAGE_SIZE)
        }

        private fun persistCipherPageSize(context: Context, pageSize: Int) {
            context
                .getSharedPreferences(DATABASE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CIPHER_PAGE_SIZE, pageSize)
                .apply()
        }

        private fun clearStoredCipherPageSize(context: Context) {
            context
                .getSharedPreferences(DATABASE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CIPHER_PAGE_SIZE)
                .apply()
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN stan TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN acquirerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN issuerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN cardRangeId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN cardRangeName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN authorizationId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN emvCryptoInformation TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN emvCryptogram TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN applicationLabel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN cardExpirationDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN retrievalReferenceNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN externalReferenceNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN folioNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN originalTransactionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN tipProcessingInformation TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN signatureRequired INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN signatureCaptured INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN paymentPlan TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN alternateHostResponse TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN additionalHostPrintData TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `transaction` ADD COLUMN paymentPlanQueryResponse TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_reversals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `acquirerId` TEXT NOT NULL,
                        `transactionType` TEXT NOT NULL,
                        `stan` TEXT NOT NULL,
                        `originalMessageType` TEXT NOT NULL,
                        `processingCode` TEXT NOT NULL,
                        `header` TEXT,
                        `field_values` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `lastAttemptAt` TEXT,
                        `attempts` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `lastResponseCode` TEXT,
                        `invoiceNumber` TEXT NOT NULL,
                        `transactionAmount` TEXT NOT NULL,
                        `maskedPan` TEXT NOT NULL,
                        `cardBrand` TEXT NOT NULL
                    )
                    """
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `settlement_state` (
                        `acquirerId` TEXT NOT NULL,
                        `acquirerName` TEXT NOT NULL,
                        `currencySymbol` TEXT NOT NULL,
                        `pending` INTEGER NOT NULL,
                        `pendingSince` TEXT,
                        `lastSnapshot` TEXT,
                        PRIMARY KEY(`acquirerId`)
                    )
                    """
                )
            }
        }
    }
}

