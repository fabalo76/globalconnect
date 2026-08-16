package one.globalconnect.paymentapp.cardreader.nexgo

import org.junit.Assert.assertEquals
import org.junit.Test

class EmvConfigBuilderTest {
    @Test
    fun terminalDisablementOverridesEnabledAidCapability() {
        assertEquals(0, EmvConfigBuilder.effectiveOnlinePinCap(false, 1))
    }

    @Test
    fun enabledTerminalHonorsEachAidCapability() {
        assertEquals(1, EmvConfigBuilder.effectiveOnlinePinCap(true, 1))
        assertEquals(0, EmvConfigBuilder.effectiveOnlinePinCap(true, 0))
    }

    @Test
    fun disabledOnlinePinIsRemovedFromKernelCvmCapability() {
        assertEquals(0x20, NexgoApi.applyOnlinePinCvmCapability(0x60, false))
        assertEquals(0x40, NexgoApi.applyOnlinePinCvmCapability(0x40, true))
    }

    @Test
    fun disabledOnlinePinIsRemovedFromTerminalTransactionQualifiers() {
        assertEquals(0x32, NexgoApi.applyOnlinePinTtqCapability(0x36, false))
        assertEquals(0x36, NexgoApi.applyOnlinePinTtqCapability(0x36, true))
    }

    @Test
    fun onlyDukptPinTypesAreSupportedByCurrentInternalPinPadFlow() {
        assertEquals(false, NexgoApi.supportsOnlinePinType(0))
        assertEquals(false, NexgoApi.supportsOnlinePinType(1))
        assertEquals(true, NexgoApi.supportsOnlinePinType(3))
        assertEquals(true, NexgoApi.supportsOnlinePinType(4))
    }
}
