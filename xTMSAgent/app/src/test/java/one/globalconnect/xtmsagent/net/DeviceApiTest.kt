package one.globalconnect.xtmsagent.net

import one.globalconnect.xtmsagent.TMSFunc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceApiTest {
    private val originalConfig = TMSFunc.tmsCfg

    @After
    fun restoreConfig() {
        TMSFunc.tmsCfg = originalConfig
    }

    @Test
    fun primaryUrlUsesOnlyCanonicalApiHost() {
        TMSFunc.tmsCfg = TMSFunc.tms(
            api_host = "api.dev.globalconnect.one",
            api_host_fallback = "raw.execute-api.us-east-1.amazonaws.com",
            web_port_ssl = true,
            web_port = 443,
        )

        assertEquals(
            "https://api.dev.globalconnect.one:443/v1/devices/N960W900629/iot-credentials",
            DeviceApi.primaryUrl("/v1/devices/N960W900629/iot-credentials"),
        )
    }

    @Test
    fun primaryUrlPreservesAbsoluteUrls() {
        val url = "https://api.dev.globalconnect.one/v1/devices/N960W900629/iot-credentials"

        assertEquals(url, DeviceApi.primaryUrl(url))
    }
}
