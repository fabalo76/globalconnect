package one.globalconnect.xtmsagent.licensing

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class NexgoUiPolicyTest {
    @Test fun n6ProLiteUnlockClearsFirmwareDisableFlags() {
        assertFalse(nexgoUiArgument("N6ProLite", false))
        assertTrue(nexgoUiArgument("N6 Pro Lite", true))
    }
    @Test fun ct20FamilyUsesFirmwareDisableFlags() {
        for (model in listOf("CT20", "CT20P", "N96")) {
            assertFalse(nexgoUiArgument(model, false))
            assertTrue(nexgoUiArgument(model, true))
        }
    }
    @Test fun otherModelsKeepExistingSdkConvention() {
        assertTrue(nexgoUiArgument("N82", false))
        assertFalse(nexgoUiArgument("N82", true))
    }
}
