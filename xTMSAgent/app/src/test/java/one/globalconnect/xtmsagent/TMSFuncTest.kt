package one.globalconnect.xtmsagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TMSFuncTest {
    @Test
    fun parseConfigRejectsMalformedJson() {
        assertNull(TMSFunc.parseConfig("{not-json", "CT20P101003"))
    }

    @Test
    fun parseConfigNormalizesUnsafeValues() {
        val parsed = TMSFunc.parseConfig(
            """
            {
              "launcher_theme": {
                "background_color": "not-a-color",
                "foreground_color": "112233",
                "font_color": "#44556677",
                "font_size": 500
              },
              "tms_cfg": {
                "tcp_port": -1,
                "web_port": 99999,
                "conn_timeout": 0,
                "resp_timeout": 9999,
                "attempt_counter": 0
              },
              "mqtt_cfg": {
                "mqtt_port": 0,
                "keepalive": 1,
                "probe_timeout": 1,
                "status_interval": 1
              }
            }
            """.trimIndent(),
            " CT20P101003 ",
        )!!

        assertEquals("#FFF0F0F0", parsed.theme.background_color)
        assertEquals("#112233", parsed.theme.foreground_color)
        assertEquals("#44556677", parsed.theme.font_color)
        assertEquals(72, parsed.theme.font_size)
        assertEquals("CT20P101003", parsed.tms.sn)
        assertEquals(5050, parsed.tms.tcp_port)
        assertEquals(443, parsed.tms.web_port)
        assertEquals(1, parsed.tms.conn_timeout)
        assertEquals(600, parsed.tms.resp_timeout)
        assertEquals(1, parsed.tms.attempt_counter)
        assertEquals(8883, parsed.mqtt.mqtt_port)
        assertEquals(30, parsed.mqtt.keepalive)
        assertEquals(1_000, parsed.mqtt.probe_timeout)
        assertEquals(10, parsed.mqtt.status_interval)
    }

    @Test
    fun parseConfigRejectsNullSections() {
        assertNull(TMSFunc.parseConfig("{\"launcher_theme\":null}", "CT20P101003"))
    }
}
