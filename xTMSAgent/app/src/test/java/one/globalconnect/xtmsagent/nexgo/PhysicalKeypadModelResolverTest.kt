package one.globalconnect.xtmsagent.nexgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalKeypadModelResolverTest {
    @Test
    fun `CT20 and CT20P models use their physical keypad`() {
        assertTrue(PhysicalKeypadModelResolver.hasPhysicalKeypad("CT20", null))
        assertTrue(PhysicalKeypadModelResolver.hasPhysicalKeypad("CT20P", null))
        assertTrue(PhysicalKeypadModelResolver.hasPhysicalKeypad("ct20-p", null))
        assertTrue(PhysicalKeypadModelResolver.hasPhysicalKeypad(null, "CT20P_REV2"))
    }

    @Test
    fun `touchscreen terminal models retain the on-screen keyboard`() {
        assertFalse(PhysicalKeypadModelResolver.hasPhysicalKeypad("N6S", null))
        assertFalse(PhysicalKeypadModelResolver.hasPhysicalKeypad("N82", null))
        assertFalse(PhysicalKeypadModelResolver.hasPhysicalKeypad("N96", null))
        assertFalse(PhysicalKeypadModelResolver.hasPhysicalKeypad(null, null))
    }
}
