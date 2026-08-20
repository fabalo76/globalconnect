package one.globalconnect.paymentapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmsParamsUpdateNotifierTest {

    @Test
    fun `posts notification when main activity is closed`() {
        assertTrue(shouldPostParamsUpdateNotification(mainActivityVisible = false))
    }

    @Test
    fun `does not post notification while main activity is visible`() {
        assertFalse(shouldPostParamsUpdateNotification(mainActivityVisible = true))
    }
}
