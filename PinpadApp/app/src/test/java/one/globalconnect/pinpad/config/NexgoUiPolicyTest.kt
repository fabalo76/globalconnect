package one.globalconnect.pinpad.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NexgoUiPolicyTest {
    @Test fun n6ProLiteUnlockClearsFirmwareDisableFlags() {
        for (model in listOf("N6ProLite", "N6 Pro Lite", "n6prolite")) {
            assertFalse(nexgoUiArgument(model, false))
            assertTrue(nexgoUiArgument(model, true))
        }
    }
    @Test fun otherModelsKeepExistingSdkConvention() {
        assertTrue(nexgoUiArgument("CT20P", false))
        assertFalse(nexgoUiArgument("CT20P", true))
    }
}
