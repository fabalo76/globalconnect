package one.globalconnect.paymentapp.uicpos.pos.host

import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.transaction.toTransactionString
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry.TransactionAttribute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePinChangeTransactionConfigTest {
    @Test
    fun offlinePinChangeUsesDedicated0200Mapping() {
        val config = requireNotNull(
            TransactionConfigRegistry.configFor(
                TransactionType.OFFLINE_PIN_CHANGE.toTransactionString(),
            ),
        )

        assertEquals("0200", config.messageType)
        assertEquals("920000", config.processingCode)
        assertFalse(config.needAmount)
        assertTrue(config.hasAttribute(TransactionAttribute.NEEDS_REVERSAL))
        assertFalse(config.hasAttribute(TransactionAttribute.WRITES_RECORD))
    }

    @Test
    fun offlinePinChangeReversalUses0400WithoutGeneratingAnotherReversal() {
        val config = requireNotNull(
            TransactionConfigRegistry.configFor(
                TransactionType.REVERSAL_OFFLINE_PIN_CHANGE.toTransactionString(),
            ),
        )

        assertEquals("0400", config.messageType)
        assertEquals("920000", config.processingCode)
        assertFalse(config.hasAttribute(TransactionAttribute.NEEDS_REVERSAL))
    }

    @Test
    fun pinUnblockUses0200AndProcessingCode91() {
        val config = requireNotNull(
            TransactionConfigRegistry.configFor(
                TransactionType.PIN_UNBLOCK.toTransactionString(),
            ),
        )

        assertEquals("0200", config.messageType)
        assertEquals("910000", config.processingCode)
        assertFalse(config.needAmount)
        assertTrue(config.hasAttribute(TransactionAttribute.NEEDS_REVERSAL))
        assertFalse(config.hasAttribute(TransactionAttribute.WRITES_RECORD))
    }
}
