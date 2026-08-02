package one.globalconnect.pinpad.protocol

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignatureCaptureProtocolTest {
    @Test
    fun parsesTimeoutOrientationAndFormat() {
        val payload = "060${FS_CHAR}H${FS_CHAR}P"

        val request = SignatureCaptureProtocol.parseRequest(payload)

        assertEquals(
            SignatureCaptureRequest(
                timeoutSeconds = 60,
                orientation = SignatureOrientation.Horizontal,
                imageFormat = SignatureImageFormat.Png,
            ),
            request,
        )
    }

    @Test
    fun rejectsOutOfRangeTimeoutAndUnknownOptions() {
        assertNull(SignatureCaptureProtocol.parseRequest("004${FS_CHAR}H${FS_CHAR}P"))
        assertNull(SignatureCaptureProtocol.parseRequest("060${FS_CHAR}X${FS_CHAR}P"))
        assertNull(SignatureCaptureProtocol.parseRequest("060${FS_CHAR}H${FS_CHAR}B"))
    }

    @Test
    fun packetizesCapturedImageIntoNumberedS2Payloads() {
        val image = ByteArray(2_100) { (it % 251).toByte() }

        val packets = SignatureCaptureProtocol.responsePayloads(
            SignatureCaptureResult.Captured(image),
        )

        assertEquals(3, packets.size)
        assertEquals("100010003", packets[0].take(9))
        assertEquals("100020003", packets[1].take(9))
        assertEquals("100030003", packets[2].take(9))
        val encoded = packets.joinToString("") { it.drop(9) }
        assertContentEquals(image, Base64.getDecoder().decode(encoded))
    }

    @Test
    fun createsSingleStatusPacketForNonCapturedResults() {
        assertEquals(listOf("200000000"), SignatureCaptureProtocol.responsePayloads(SignatureCaptureResult.Cancelled))
        assertEquals(listOf("300000000"), SignatureCaptureProtocol.responsePayloads(SignatureCaptureResult.Timeout))
        assertEquals(listOf("400000000"), SignatureCaptureProtocol.responsePayloads(SignatureCaptureResult.Error))
    }

    private companion object {
        const val FS_CHAR = '\u001C'
    }
}
