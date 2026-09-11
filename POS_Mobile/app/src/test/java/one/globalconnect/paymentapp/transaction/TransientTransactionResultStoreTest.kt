package one.globalconnect.paymentapp.transaction

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransientTransactionResultStoreTest {
    /** Clears process-local test data after each test. */
    @After
    fun tearDown() {
        TransientTransactionResultStore.clear()
    }

    /** Verifies non-batch result tokens are unique and can only be consumed once. */
    @Test
    fun storedResultIsConsumedExactlyOnce() {
        val first = Transaction(type = TransactionType.OFFLINE_PIN_CHANGE, authCode = "ABC123")
        val second = Transaction(type = TransactionType.PIN_UNBLOCK, authCode = "DEF456")

        val firstToken = TransientTransactionResultStore.store(first)
        val secondToken = TransientTransactionResultStore.store(second)

        assertNotEquals(firstToken, secondToken)
        assertEquals(first, TransientTransactionResultStore.consume(firstToken))
        assertNull(TransientTransactionResultStore.consume(firstToken))
        assertEquals(second, TransientTransactionResultStore.consume(secondToken))
    }
}
