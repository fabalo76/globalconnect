package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantReceiptPrintStateTest {
    @Test
    fun `successful print blocks further merchant copies`() {
        val state = MerchantReceiptPrintState(false)
        assertTrue(state.tryStart())
        assertFalse(state.tryStart())
        state.complete(true)
        assertEquals(MerchantReceiptPrintStatus.PRINTED, state.status.value)
        assertFalse(state.tryStart())
    }

    @Test
    fun `paper or printer failure permits retry`() {
        val state = MerchantReceiptPrintState(false)
        assertTrue(state.tryStart())
        state.complete(false)
        assertEquals(MerchantReceiptPrintStatus.READY, state.status.value)
        assertTrue(state.tryStart())
    }

    @Test
    fun `restored printed result stays disabled`() {
        val state = MerchantReceiptPrintState(true)
        assertFalse(state.tryStart())
        state.complete(false)
        assertEquals(MerchantReceiptPrintStatus.PRINTED, state.status.value)
    }
}
