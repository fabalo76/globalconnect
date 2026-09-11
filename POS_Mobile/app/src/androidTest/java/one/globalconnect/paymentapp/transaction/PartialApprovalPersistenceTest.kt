package one.globalconnect.paymentapp.transaction

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.globalconnect.paymentapp.dao.TransactionDatabase
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.TransLog
import one.globalconnect.paymentapp.uicpos.pos.model.toTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PartialApprovalPersistenceTest {
    @Test
    fun conversionAndStoragePreserveOriginalAndApprovedAmounts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TransactionDatabase::class.java).build()
        try {
            val transaction = ProcInfo(TransLog = TransLog(
                TxnType = TransactionType.SALE.toTransactionString(),
                TxnAmt = "125.00",
                BaseAmt = "125.00",
                PartialApprovalOriginalAmount = "250.00",
                ARC = "10",
            )).toTransaction()
            database.transactionDao().insert(transaction)
            val restored = database.transactionDao().getLastTransaction()!!
            assertEquals("250.00", restored.partialApprovalOriginalAmount)
            assertEquals("125.00", restored.totalAmount)
            assertEquals("10", restored.ARC)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationPreservesExistingRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TransactionDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            // Recreate only the old transaction table in this isolated in-memory database.
            sql.execSQL("DROP TABLE `transaction`")
            sql.execSQL("CREATE TABLE `transaction` (id INTEGER PRIMARY KEY, totalAmount TEXT NOT NULL, ARC TEXT NOT NULL)")
            sql.execSQL("INSERT INTO `transaction` VALUES (16, '125.00', '10')")
            TransactionDatabase.MIGRATION_9_10.migrate(sql)
            sql.query("SELECT id, totalAmount, ARC, partialApprovalOriginalAmount FROM `transaction`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(16, cursor.getInt(0))
                assertEquals("125.00", cursor.getString(1))
                assertEquals("10", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals(1, cursor.count)
            }
        } finally {
            database.close()
        }
    }
}
