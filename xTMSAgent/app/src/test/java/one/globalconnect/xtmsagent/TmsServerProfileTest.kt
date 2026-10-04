package one.globalconnect.xtmsagent

import org.junit.Assert.*
import org.junit.Test

class TmsServerProfileTest {
    @Test fun demoHasNoAwsFallbackOrCredentials() {
        val config = TmsServerProfile.DEMO.defaults()
        assertEquals("demo.globalconnect.one", config.apiHost)
        assertEquals(config.apiHost, config.mqttHost)
        assertEquals("", config.api_host_fallback)
        assertEquals("", config.download_secret)
        assertEquals(443, config.web_port)
        val json = com.google.gson.Gson().toJson(TMSFunc.cfg(tms = config))
        assertEquals("", TMSFunc.parseConfig(json, "N96_TEST")!!.tms.download_secret)
    }

    @Test fun demoCannotReuseLegacyAwsCertificatesOrTaskStorage() {
        val aws = TmsServerProfile.AWS.defaults()
        val demo = TmsServerProfile.DEMO.defaults()
        assertEquals("aws_iot", TmsServerProfile.storageName("aws_iot", aws))
        assertNotEquals(TmsServerProfile.storageName("aws_iot", aws), TmsServerProfile.storageName("aws_iot", demo))
        assertNotEquals(TmsServerProfile.storageName("pending_task_acks", aws), TmsServerProfile.storageName("pending_task_acks", demo))
        assertNotEquals(TmsServerProfile.scope(demo), TmsServerProfile.scope(demo.copy(web_port = 8443)))
    }
}
