package one.globalconnect.xtmsagent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentAppAutoLauncherTest {
    @Test
    fun `queues only once for the same physical boot`() {
        assertEquals(
            true,
            shouldQueuePaymentLaunch(152, false, 151, 151),
        )
        assertEquals(
            false,
            shouldQueuePaymentLaunch(152, true, 152, 151),
        )
        assertEquals(
            false,
            shouldQueuePaymentLaunch(152, false, 152, 152),
        )
    }

    @Test
    fun `selects payment application matching agent flavor`() {
        assertEquals(
            "one.globalconnect.paymentapp.globalconnect",
            selectPaymentPackage(
                "one.globalconnect.xtmsagent.globalconnect",
                listOf(
                    "one.globalconnect.paymentapp.other",
                    "one.globalconnect.paymentapp.globalconnect",
                ),
            ),
        )
    }

    @Test
    fun `accepts sole payment application for legacy deployments`() {
        assertEquals(
            "legacy.payment.app",
            selectPaymentPackage(
                "one.globalconnect.xtmsagent.globalconnect",
                listOf("legacy.payment.app"),
            ),
        )
    }

    @Test
    fun `rejects ambiguous applications without matching flavor`() {
        assertNull(
            selectPaymentPackage(
                "one.globalconnect.xtmsagent.globalconnect",
                listOf("payment.one", "payment.two"),
            ),
        )
    }
}
