package one.globalconnect.pinpad.protocol

import one.globalconnect.pinpad.device.FirmwareVersion
import one.globalconnect.pinpad.device.PinpadDeviceInfoProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyPinpadProtocolHandlerTest {
    private val codec = PINPADFrameCodec()

    @Test
    fun hostNakRetriesPendingResponseFrame() {
        val handler = testHandler()
        try {
            val originalResponses = handler.onBytesReceived(serialNumberRequest())
            val originalFrame = originalResponses[1]

            val retryResponses = handler.onBytesReceived(byteArrayOf(PINPADControl.NAK))

            assertEquals(1, retryResponses.size)
            assertContentEquals(originalFrame, retryResponses.single())
        } finally {
            handler.shutdown()
        }
    }

    @Test
    fun repeatedHostNakKeepsRetransmittingPendingResponseFrame() {
        val handler = testHandler()
        try {
            val originalResponses = handler.onBytesReceived(serialNumberRequest())
            val originalFrame = originalResponses[1]

            repeat(5) {
                assertContentEquals(
                    originalFrame,
                    handler.onBytesReceived(byteArrayOf(PINPADControl.NAK)).single(),
                )
            }

            val finalResponses = handler.onBytesReceived(byteArrayOf(PINPADControl.ACK))
            assertEquals(PINPADControl.EOT, finalResponses.single().single())
        } finally {
            handler.shutdown()
        }
    }

    @Test
    fun missingHostAckRetriesTwoTimesThenSendsEot() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val handler = testHandler(
            asyncResponseSender = asyncResponses::add,
            ackTimeoutMs = 25L,
        )
        try {
            val originalResponses = handler.onBytesReceived(serialNumberRequest())
            val originalFrame = originalResponses[1]

            waitUntil { asyncResponses.size >= 3 }

            assertContentEquals(originalFrame, asyncResponses[0])
            assertContentEquals(originalFrame, asyncResponses[1])
            assertEquals(PINPADControl.EOT, asyncResponses[2].single())
            assertTrue(handler.onBytesReceived(byteArrayOf(PINPADControl.ACK)).isEmpty())
        } finally {
            handler.shutdown()
        }
    }

    @Test
    fun asyncResponseIsTrackedForHostNakRetry() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val handler = testHandler(asyncResponseSender = asyncResponses::add)
        try {
            val response = codec.encode(PINPADFrame(PINPADFrameType.Transaction, "T62", "0A1".toByteArray()))
            handler.sendAsyncResponse(response)

            assertContentEquals(response, asyncResponses.single())
            val retryResponses = handler.onBytesReceived(byteArrayOf(PINPADControl.NAK))
            assertContentEquals(response, retryResponses.single())
        } finally {
            handler.shutdown()
        }
    }

    @Test
    fun pipelinedAckBeforeNextFrameSuppressesStaleFinalEot() {
        val handler = testHandler()
        try {
            val originalResponses = handler.onBytesReceived(serialNumberRequest())
            assertEquals(PINPADControl.ACK, originalResponses[0].single())

            val pipelinedResponses = handler.onBytesReceived(byteArrayOf(PINPADControl.ACK) + serialNumberRequest())
            assertEquals(2, pipelinedResponses.size)
            assertEquals(PINPADControl.ACK, pipelinedResponses[0].single())
            val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(pipelinedResponses[1]))
            assertEquals("06", response.frame.commandId)

            val finalResponses = handler.onBytesReceived(byteArrayOf(PINPADControl.ACK))
            assertEquals(PINPADControl.EOT, finalResponses.single().single())
        } finally {
            handler.shutdown()
        }
    }

    @Test
    fun jpegIdleLogoPipelinedEnableDoesNotReceiveStaleEot() {
        val handler = testHandler()
        try {
            val j7Responses = handler.onBytesReceived(
                codec.encode(PINPADFrame(PINPADFrameType.Transaction, "J7", "idle_logo".toByteArray())),
            )
            assertEquals(PINPADControl.ACK, j7Responses[0].single())
            val j7 = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(j7Responses[1]))
            assertEquals("J7", j7.frame.commandId)
            assertEquals("0", j7.frame.payloadAscii)

            val j8Responses = handler.onBytesReceived(
                byteArrayOf(PINPADControl.ACK) +
                    codec.encode(PINPADFrame(PINPADFrameType.Transaction, "J8", "1".toByteArray())),
            )
            assertEquals(2, j8Responses.size)
            assertEquals(PINPADControl.ACK, j8Responses[0].single())
            val j8 = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(j8Responses[1]))
            assertEquals("J8", j8.frame.commandId)
            assertEquals("0", j8.frame.payloadAscii)

            val finalResponses = handler.onBytesReceived(byteArrayOf(PINPADControl.ACK))
            assertTrue(finalResponses.isEmpty())
        } finally {
            handler.shutdown()
        }
    }

    @Test
    fun serialPortChangeIsExposedOnlyAfterHostAckAndFinalEot() {
        val handler = testHandler()
        try {
            val responses = handler.onBytesReceived(
                codec.encode(PINPADFrame(PINPADFrameType.Administration, "13", "81".toByteArray())),
            )
            val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
            assertEquals("0", response.frame.payloadAscii)
            assertNull(handler.consumeCompletedSerialPortChange())

            val finalResponses = handler.onBytesReceived(byteArrayOf(PINPADControl.ACK))

            assertEquals(PINPADControl.EOT, finalResponses.single().single())
            assertEquals(
                SerialPortChange(baudRate = 115_200, dataBits = 8, stopBits = 1, parity = "N"),
                handler.consumeCompletedSerialPortChange(),
            )
        } finally {
            handler.shutdown()
        }
    }

    @Test
    fun pipelinedFrameCancelsPendingSerialPortChange() {
        val handler = testHandler()
        try {
            handler.onBytesReceived(
                codec.encode(PINPADFrame(PINPADFrameType.Administration, "13", "81".toByteArray())),
            )

            handler.onBytesReceived(byteArrayOf(PINPADControl.ACK) + serialNumberRequest())

            assertNull(handler.consumeCompletedSerialPortChange())
        } finally {
            handler.shutdown()
        }
    }

    private fun testHandler(
        asyncResponseSender: (ByteArray) -> Unit = {},
        ackTimeoutMs: Long = 500L,
    ): LegacyPinpadProtocolHandler {
        return LegacyPinpadProtocolHandler(
            parser = PINPADStreamParser(),
            sessionController = PINPADSessionController(
                deviceInfoProvider = FakeDeviceInfoProvider(),
                codec = codec,
            ),
            asyncResponseSender = asyncResponseSender,
            ackTimeoutMs = ackTimeoutMs,
        )
    }

    private fun serialNumberRequest(): ByteArray {
        return codec.encode(PINPADFrame(PINPADFrameType.Administration, "06"))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 1_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue(condition(), "condition was not satisfied before timeout")
    }

    private class FakeDeviceInfoProvider : PinpadDeviceInfoProvider {
        override fun modelName(): String = "CT20P"

        override fun serialNumber(): String = "SN12345678901234"

        override fun firmwareVersion(part: Char, option: Char?): FirmwareVersion = FirmwareVersion(version = "FW1")

        override fun hardwareCapabilities(): List<String> = listOf("ICC", "MSR", "PCD")
    }
}
