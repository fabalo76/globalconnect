package one.globalconnect.xtmsagent.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceApiTest {
    @Test
    fun credentialIdPrefixesTheExistingHmacToken() {
        val legacy = DeviceApi.deviceToken(" serial-01 ", "secret")
        val rotated = DeviceApi.deviceToken(" serial-01 ", "secret", "credential-id")

        assertEquals("credential-id:$legacy", rotated)
    }
}
