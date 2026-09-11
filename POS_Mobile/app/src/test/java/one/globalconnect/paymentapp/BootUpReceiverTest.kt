package one.globalconnect.paymentapp

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootUpReceiverTest {

    @Test
    fun `boot completed starts the payment application`() {
        assertTrue(BootUpReceiver.shouldAutoStart(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `unrelated and missing actions do not start the payment application`() {
        assertFalse(BootUpReceiver.shouldAutoStart(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(BootUpReceiver.shouldAutoStart(null))
    }

    @Test
    fun `maps payment flavor package to matching xtms agent`() {
        assertEquals(
            "one.globalconnect.xtmsagent.globalconnect",
            BootUpReceiver.agentPackageFor("one.globalconnect.paymentapp.globalconnect"),
        )
    }
}
