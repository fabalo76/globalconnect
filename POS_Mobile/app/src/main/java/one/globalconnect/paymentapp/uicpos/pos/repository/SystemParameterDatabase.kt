package one.globalconnect.paymentapp.uicpos.pos.repository

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam

@Database(entities = [SysParam::class], version = 5, exportSchema = false)
abstract class SystemParameterDatabase : RoomDatabase() {
    abstract fun systemParameterDao(): SysParamDao

    companion object {
        @Volatile
        private var Instance: SystemParameterDatabase? = null

        fun getDatabase(context: Context): SystemParameterDatabase {
            // if the Instance is not null, return it, otherwise create a new database instance.
            return Instance ?: synchronized(this) {
                val MIGRATION_1_2 = object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE SysParam ADD COLUMN TipOption1 TEXT NOT NULL DEFAULT '15'")
                        db.execSQL("ALTER TABLE SysParam ADD COLUMN TipOption2 TEXT NOT NULL DEFAULT '18'")
                        db.execSQL("ALTER TABLE SysParam ADD COLUMN TipOption3 TEXT NOT NULL DEFAULT '20'")
                        db.execSQL("ALTER TABLE SysParam ADD COLUMN TipOption4 TEXT NOT NULL DEFAULT '22'")
                        db.execSQL("UPDATE 'SysParam' SET 'CmdTout' = '150' WHERE CAST(CmdTout AS INTEGER) < 150")
                    }
                }
                val MIGRATION_2_3 = object : Migration(2, 3) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE SysParam ADD COLUMN OptionsEnabled INTEGER DEFAULT 1 NOT NULL")
                        db.execSQL("ALTER TABLE SysParam ADD COLUMN BatchPrint INTEGER DEFAULT 1 NOT NULL")
                    }
                }
                val MIGRATION_3_4 = object : Migration(3, 4) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE SysParam ADD COLUMN DetailedBatchReport INTEGER DEFAULT 0 NOT NULL")
                    }
                }
                val MIGRATION_4_5 = object : Migration(4, 5) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                                CREATE TABLE IF NOT EXISTS `SysParam_new` (
                                    `SourceMAC` TEXT NOT NULL,
                                    `DestMAC` TEXT NOT NULL,
                                    `CurrCode` TEXT NOT NULL,
                                    `CmdTout` TEXT NOT NULL,
                                    `CurrencyName` TEXT NOT NULL,
                                    `DeciPosAmt` INTEGER NOT NULL,
                                    `EmployeePassword` TEXT NOT NULL,
                                    `AdminPassword` TEXT NOT NULL,
                                    `TipMethod` INTEGER NOT NULL,
                                    `TransactionMode` TEXT NOT NULL,
                                    `SignatureMode` TEXT NOT NULL,
                                    `SurchargePercent` TEXT NOT NULL,
                                    `Language` TEXT NOT NULL,
                                    `MerchantId` TEXT NOT NULL,
                                    `DeviceId` TEXT NOT NULL,
                                    `TerminalId` TEXT NOT NULL,
                                    `BatchPrint` INTEGER NOT NULL,
                                    `DetailedBatchReport` INTEGER NOT NULL,
                                    `OptionsEnabled` INTEGER NOT NULL,
                                    `TipOption1` TEXT NOT NULL,
                                    `TipOption2` TEXT NOT NULL,
                                    `TipOption3` TEXT NOT NULL,
                                    `TipOption4` TEXT NOT NULL,
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
                                )
                            """
                        )
                        db.execSQL(
                            """
                                INSERT INTO `SysParam_new` (
                                    `SourceMAC`,
                                    `DestMAC`,
                                    `CurrCode`,
                                    `CmdTout`,
                                    `CurrencyName`,
                                    `DeciPosAmt`,
                                    `EmployeePassword`,
                                    `AdminPassword`,
                                    `TipMethod`,
                                    `TransactionMode`,
                                    `SignatureMode`,
                                    `SurchargePercent`,
                                    `Language`,
                                    `MerchantId`,
                                    `DeviceId`,
                                    `TerminalId`,
                                    `BatchPrint`,
                                    `DetailedBatchReport`,
                                    `OptionsEnabled`,
                                    `TipOption1`,
                                    `TipOption2`,
                                    `TipOption3`,
                                    `TipOption4`,
                                    `id`
                                )
                                SELECT
                                    `SourceMAC`,
                                    `DestMAC`,
                                    `CurrCode`,
                                    `CmdTout`,
                                    `CurrencyName`,
                                    `DeciPosAmt`,
                                    `EmployeePassword`,
                                    `AdminPassword`,
                                    `TipMethod`,
                                    `TransactionMode`,
                                    `SignatureMode`,
                                    `SurchargePercent`,
                                    `Language`,
                                    `MerchantId`,
                                    `DeviceId`,
                                    `TerminalId`,
                                    `BatchPrint`,
                                    `DetailedBatchReport`,
                                    `OptionsEnabled`,
                                    `TipOption1`,
                                    `TipOption2`,
                                    `TipOption3`,
                                    `TipOption4`,
                                    `id`
                                FROM `SysParam`
                            """
                        )
                        db.execSQL("DROP TABLE SysParam")
                        db.execSQL("ALTER TABLE SysParam_new RENAME TO SysParam")
                    }
                }
                Room.databaseBuilder(
                    context,
                    SystemParameterDatabase::class.java,
                    "system_parameter_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
//                    .fallbackToDestructiveMigration()
                    .build()
                    .also {
                        Instance = it
                    }
            }
        }
    }
}

