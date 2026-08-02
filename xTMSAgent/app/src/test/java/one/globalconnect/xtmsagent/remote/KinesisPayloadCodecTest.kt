package one.globalconnect.xtmsagent.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

class KinesisPayloadCodecTest {
    @Test
    fun encodeUsesStandardBase64ExpectedByTheBrowserClient() {
        val payload = """{"candidate":"candidate:1 1 UDP 2122260223 192.0.2.1 54400 typ host","marker":"ÿÿ"}"""

        val encoded = KinesisPayloadCodec.encode(payload)

        assertEquals(
            Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8)),
            encoded,
        )
        assertFalse(encoded.contains('-'))
        assertFalse(encoded.contains('_'))
        assertEquals(payload, KinesisPayloadCodec.decode(encoded))
    }

    @Test
    fun decodeAcceptsLegacyUrlSafePayloadsDuringUpgrade() {
        val payload = """{"type":"offer","sdp":"v=0\r\n"}"""
        val encoded = Base64.getUrlEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))

        assertEquals(payload, KinesisPayloadCodec.decode(encoded))
    }
}
