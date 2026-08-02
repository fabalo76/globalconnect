package one.globalconnect.pinpad.protocol

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraQrProtocolTest {
    @Test
    fun parsesPhotoRequestAndPacketizesJpeg() {
        val request = CameraQrProtocol.parsePhotoRequest("060${FS}F${FS}85")
        assertEquals(PhotoCaptureRequest(60, CameraFacing.Front, 85), request)

        val jpeg = ByteArray(1_500) { (it % 251).toByte() }
        val packets = CameraQrProtocol.photoResponsePayloads(PhotoCaptureResult.Captured(jpeg))
        assertTrue(packets.size > 1)
        assertTrue(packets.all { it.startsWith("1") })
        val decoded = Base64.getDecoder().decode(packets.joinToString("") { it.drop(9) })
        assertTrue(jpeg.contentEquals(decoded))
    }

    @Test
    fun rejectsInvalidPhotoRequest() {
        assertNull(CameraQrProtocol.parsePhotoRequest("004${FS}F${FS}85"))
        assertNull(CameraQrProtocol.parsePhotoRequest("060${FS}X${FS}85"))
        assertNull(CameraQrProtocol.parsePhotoRequest("060${FS}F${FS}09"))
    }

    @Test
    fun parsesQrDisplayAndScanRequests() {
        val value = "https://globalconnect.one/á"
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        assertEquals(
            QrDisplayRequest(45, value),
            CameraQrProtocol.parseQrDisplayRequest("045${FS}$encoded"),
        )
        assertEquals(
            QrScanRequest(30, CameraFacing.Back),
            CameraQrProtocol.parseQrScanRequest("030${FS}B"),
        )
    }

    @Test
    fun encodesQrScanResultAsUtf8Base64() {
        val payload = CameraQrProtocol.qrScanResponsePayload(QrScanResult.Scanned("Pago #123"))
        assertTrue(payload.startsWith("1$FS"))
        val decoded = Base64.getDecoder().decode(payload.substringAfter(FS))
            .toString(StandardCharsets.UTF_8)
        assertEquals("Pago #123", decoded)
        assertEquals("2", CameraQrProtocol.qrScanResponsePayload(QrScanResult.Cancelled))
        assertIs<QrDisplayResult.Completed>(QrDisplayResult.Completed)
    }

    @Test
    fun threeCharacterCameraAndQrCommandsRoundTrip() {
        val codec = PINPADFrameCodec()
        listOf("PH1", "PH2", "QR1", "QR2", "QR3", "QR4").forEach { command ->
            val frame = PINPADFrame(PINPADFrameType.Transaction, command, "data".toByteArray())
            val decoded = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(codec.encode(frame)))
            assertEquals(command, decoded.frame.commandId)
            assertEquals("data", decoded.frame.payloadAscii)
        }
    }

    private companion object {
        const val FS = '\u001C'
    }
}
