package one.globalconnect.pinpad.protocol

import one.globalconnect.pinpad.device.PinpadDeviceInfoProvider
import one.globalconnect.pinpad.security.KeyLoadAuthorizer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PINPADSessionControllerTest {
    private val codec = PINPADFrameCodec()
    private val controller = PINPADSessionController(
        deviceInfoProvider = FakeDeviceInfoProvider(),
        codec = codec,
    )

    @Test
    fun z2PromptTextDecodesUtf8Accents() {
        val decodePromptText = PINPADSessionController::class.java
            .getDeclaredMethod("decodePromptText", ByteArray::class.java)
            .apply { isAccessible = true }

        val decoded = decodePromptText.invoke(
            controller,
            "Línea 1".toByteArray(StandardCharsets.UTF_8),
        )

        assertEquals("Línea 1", decoded)
    }

    @Test
    fun getSerialNumberSendsAckThenResponseThenEotOnHostAck() {
        val firstResponses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "06")),
        )

        assertEquals(PINPADControl.ACK, firstResponses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(firstResponses[1]))
        assertEquals("06", response.frame.commandId)
        assertEquals("SN12345678901234", response.frame.payloadAscii)

        val finalResponses = controller.onInbound(PINPADInbound.Control(PINPADControl.ACK))
        assertEquals(PINPADControl.EOT, finalResponses.single().single())
    }

    @Test
    fun communicationTestUsesProcessingEchoHandshake() {
        val firstResponses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "09")),
        )
        assertEquals(PINPADControl.ACK, firstResponses[0].single())
        val processing = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(firstResponses[1]))
        assertEquals("09", processing.frame.commandId)
        assertEquals("${PINPADControl.SUB.toInt().toChar()}PROCESSING", processing.frame.payloadAscii)

        val echoResponses = controller.onInbound(PINPADInbound.Frame(processing.frame))
        assertEquals(PINPADControl.ACK, echoResponses[0].single())
        val result = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(echoResponses[1]))
        assertEquals("090", result.frame.commandId + result.frame.payloadAscii)

        val finalResponses = controller.onInbound(PINPADInbound.Control(PINPADControl.ACK))
        assertEquals(PINPADControl.EOT, finalResponses.single().single())
    }

    @Test
    fun firmwareVersionResponseUsesA10Prefix() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "19", "1".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Administration, response.frame.frameType)
        assertEquals("19", response.frame.commandId)
        assertEquals(".A10CT20PFW1", response.frame.payloadAscii)
    }

    @Test
    fun setLocalTimeIsCompatibilityDummyThatReturnsSuccess() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(
                PINPADFrame(
                    PINPADFrameType.Administration,
                    "18",
                    "202608241164614".toByteArray(),
                ),
            ),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Administration, response.frame.frameType)
        assertEquals("18", response.frame.commandId)
        assertEquals("0", response.frame.payloadAscii)

        val finalResponses = controller.onInbound(PINPADInbound.Control(PINPADControl.ACK))
        assertEquals(PINPADControl.EOT, finalResponses.single().single())
    }

    @Test
    fun firmwareComponentFourReturnsApplicationVersion() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "19", "4".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("19", response.frame.commandId)
        assertEquals(".A10CT20PAPP0.2.0", response.frame.payloadAscii)
    }

    @Test
    fun firmwareVersionResponseKeepsMeaningfulSubVersionAndHash() {
        val customController = PINPADSessionController(
            deviceInfoProvider = object : FakeDeviceInfoProvider() {
                override fun firmwareVersion(part: Char, option: Char?) =
                    one.globalconnect.pinpad.device.FirmwareVersion(version = "FW2", subVersion = "01", hash = "ABCDEF")
            },
            codec = codec,
        )
        val responses = customController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "19", "1".toByteArray())),
        )

        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(".A10CT20PFW2.01.ABCDEF", response.frame.payloadAscii)
    }

    @Test
    fun firmwareVersionResponseNormalizesLegacyA10PrefixWithModel() {
        val customController = PINPADSessionController(
            deviceInfoProvider = object : FakeDeviceInfoProvider() {
                override fun firmwareVersion(part: Char, option: Char?) =
                    one.globalconnect.pinpad.device.FirmwareVersion(version = "A104.14.186-g86bb067")
            },
            codec = codec,
        )
        val responses = customController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "19", "1".toByteArray())),
        )

        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(".A10CT20P4.14.186-g86bb067", response.frame.payloadAscii)
    }

    @Test
    fun queryKcvReturnsZ65ErrorWhenDeviceIsUnavailable() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "Z64", "0".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("Z65", response.frame.commandId)
        assertEquals("0?", response.frame.payloadAscii)
    }

    @Test
    fun clearKeyLoadWithoutAuthorizedModeReturnsOnlyEot() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val authorizer = FakeKeyLoadAuthorizer()
        val commandController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            keyLoadAuthorizer = authorizer,
            codec = codec,
            asyncResponseSender = asyncResponses::add,
        )

        val payload = "1123456789ABCDE90123456789ABCDEF0\u001CP0E"
        val responses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "02", payload.toByteArray())),
        )

        assertTrue(asyncResponses.isEmpty())
        assertEquals(1, responses.size)
        assertEquals(PINPADControl.EOT, responses.single().single())
        assertNull(authorizer.requestedCommand)
    }

    @Test
    fun malformedLoadKeyRequestWithoutAuthorizedModeReturnsOnlyEot() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val commandController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            keyLoadAuthorizer = FakeKeyLoadAuthorizer(),
            codec = codec,
            asyncResponseSender = asyncResponses::add,
        )

        val responses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "02")),
        )

        assertTrue(asyncResponses.isEmpty())
        assertEquals(PINPADControl.EOT, responses.single().single())
    }

    @Test
    fun dukptKeyLoadsWithoutAuthorizedModeReturnOnlyEot() {
        val payload = "EA4A38ADB99BEFEA7ACB13F4312CE0DAFFFF2BED86174F000000"

        listOf("90", "94").forEach { commandId ->
            val authorizer = FakeKeyLoadAuthorizer()
            val commandController = PINPADSessionController(
                deviceInfoProvider = FakeDeviceInfoProvider(),
                keyLoadAuthorizer = authorizer,
                codec = codec,
            )

            val responses = commandController.onInbound(
                PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, commandId, payload.toByteArray())),
            )

            assertEquals(1, responses.size, commandId)
            assertEquals(PINPADControl.EOT, responses.single().single(), commandId)
            assertEquals(0, authorizer.clearKeyActivityCount, commandId)
        }
    }

    @Test
    fun clearKeyLoadExecutesOnlyAfterModeWasAuthorized() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val authorizer = FakeKeyLoadAuthorizer(clearKeyModeActive = true)
        val commandController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            keyLoadAuthorizer = authorizer,
            codec = codec,
            asyncResponseSender = asyncResponses::add,
        )
        val payload = "1123456789ABCDE90123456789ABCDEF0\u001CP0E"

        val responses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "02", payload.toByteArray())),
        )

        assertEquals(1, responses.size)
        assertEquals(PINPADControl.ACK, asyncResponses.single().single())
        val rejected = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses.single()))
        assertEquals("02", rejected.frame.commandId)
        assertEquals("?A", rejected.frame.payloadAscii)
        assertEquals(1, authorizer.clearKeyActivityCount)
        assertNull(authorizer.requestedCommand)
    }

    @Test
    fun keyInjectionModeDropsCommandsOutsideWhitelistWithOnlyEot() {
        val authorizer = FakeKeyLoadAuthorizer(clearKeyModeActive = true)
        val commandController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            keyLoadAuthorizer = authorizer,
            codec = codec,
        )

        val responses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "19", "1".toByteArray())),
        )

        assertEquals(PINPADControl.EOT, responses.single().single())
        assertEquals(0, authorizer.clearKeyActivityCount)
    }

    @Test
    fun keyInjectionModeAllowsWhitelistedCommandsAndRecordsActivity() {
        val authorizer = FakeKeyLoadAuthorizer(clearKeyModeActive = true)
        val commandController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            keyLoadAuthorizer = authorizer,
            codec = codec,
        )

        val responses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "06")),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("06", response.frame.commandId)
        assertEquals(1, authorizer.clearKeyActivityCount)
    }

    @Test
    fun keyInjectionModeAllowsDukptKeyLoadsAndRecordsActivity() {
        val payload = "EA4A38ADB99BEFEA7ACB13F4312CE0DAFFFF2BED86174F000000"

        listOf("90", "94").forEach { commandId ->
            val authorizer = FakeKeyLoadAuthorizer(clearKeyModeActive = true)
            val commandController = PINPADSessionController(
                deviceInfoProvider = FakeDeviceInfoProvider(),
                keyLoadAuthorizer = authorizer,
                codec = codec,
            )

            val responses = commandController.onInbound(
                PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, commandId, payload.toByteArray())),
            )

            assertEquals(PINPADControl.ACK, responses[0].single(), commandId)
            val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
            assertEquals("91", response.frame.commandId, commandId)
            assertEquals(1, authorizer.clearKeyActivityCount, commandId)
        }
    }

    @Test
    fun keyInjectionModeAllowsDukptStatusQueryAndRecordsActivity() {
        val authorizer = FakeKeyLoadAuthorizer(clearKeyModeActive = true)
        val commandController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            keyLoadAuthorizer = authorizer,
            codec = codec,
        )

        val responses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "98", "01".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("99", response.frame.commandId)
        assertEquals(1, authorizer.clearKeyActivityCount)
    }

    @Test
    fun secretKeyPasswordGateIsUsedOnlyWhenAuthorizerRequiresIt() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val authorizer = FakeKeyLoadAuthorizer(setOf("20"))
        val commandController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            keyLoadAuthorizer = authorizer,
            codec = codec,
            asyncResponseSender = asyncResponses::add,
        )

        val responses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "20", "0123456789ABCDEFF".toByteArray())),
        )

        assertTrue(responses.isEmpty())
        assertEquals(PINPADControl.ACK, asyncResponses.single().single())
        authorizer.complete(false)
        assertEquals(PINPADControl.EOT, asyncResponses[1].single())
    }

    @Test
    fun validSecretKeyLoadsAcknowledgeAfterFormatValidationBeforeDeviceWork() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val commandController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            codec = codec,
            asyncResponseSender = asyncResponses::add,
        )

        val masterResponses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "20", "0123456789ABCDEFF".toByteArray())),
        )
        assertEquals(PINPADControl.ACK, asyncResponses.removeAt(0).single())
        assertEquals(PINPADControl.EOT, masterResponses.single().single())

        val sessionResponses = commandController.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "21", "1123456789ABCDEFF".toByteArray())),
        )
        assertEquals(PINPADControl.ACK, asyncResponses.removeAt(0).single())
        assertEquals(PINPADControl.EOT, sessionResponses.single().single())
    }

    @Test
    fun malformedSecretKeyLoadsReturnAckThenEot() {
        val responses20 = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "20", "21234".toByteArray())),
        )
        assertEquals(PINPADControl.ACK, responses20[0].single())
        assertEquals(PINPADControl.EOT, responses20[1].single())

        val responses21 = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "21", "A1234".toByteArray())),
        )
        assertEquals(PINPADControl.ACK, responses21[0].single())
        assertEquals(PINPADControl.EOT, responses21[1].single())
    }

    @Test
    fun msrOutputFormatEchoesQ6AndIsReturnedByQ7() {
        val q6Responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "Q6", ".2".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, q6Responses[0].single())
        val q6Echo = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(q6Responses[1]))
        assertEquals(PINPADFrameType.Transaction, q6Echo.frame.frameType)
        assertEquals("Q6", q6Echo.frame.commandId)
        assertEquals(".2", q6Echo.frame.payloadAscii)

        val q7Responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "Q7")),
        )
        assertEquals(PINPADControl.ACK, q7Responses[0].single())
        val q7Response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(q7Responses[1]))
        assertEquals(PINPADFrameType.Transaction, q7Response.frame.frameType)
        assertEquals("Q7", q7Response.frame.commandId)
        assertEquals("2", q7Response.frame.payloadAscii)
    }

    @Test
    fun textToSpeechRejectsMalformedBase64Payload() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(
                PINPADFrame(
                    PINPADFrameType.Transaction,
                    "M17",
                    "es-MX\u001Cnot-base64!".toByteArray(),
                ),
            ),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("M17", response.frame.commandId)
        assertEquals("1", response.frame.payloadAscii)
    }

    @Test
    fun invalidQ6ReturnsEotAfterAck() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "Q6", ".9".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        assertEquals(PINPADControl.EOT, responses[1].single())
    }

    @Test
    fun msrAutoArmReturnsStatus() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "QB", "1".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("QB", response.frame.commandId)
        assertEquals("0", response.frame.payloadAscii)
    }

    @Test
    fun startMsrReadAcknowledgesImmediately() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "Q1")),
        )

        assertEquals(1, responses.size)
        assertEquals(PINPADControl.ACK, responses.single().single())
    }

    @Test
    fun qkReturnsFatalWhenDeviceIsUnavailable() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "QK")),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("QK", response.frame.commandId)
        assertEquals("00", response.frame.payloadAscii)
    }

    @Test
    fun emvConfigQueryReturnsT0AWhenDeviceIsUnavailable() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "T09", "1".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("T0A", response.frame.commandId)
        assertEquals("11", response.frame.payloadAscii)
    }

    @Test
    fun contactTransactionStartReturnsT16FailureWhenDeviceIsUnavailable() {
        val payload = "${PINPADControl.SUB.toInt().toChar()}000000001000"
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "T15", payload.toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("T16", response.frame.commandId)
        assertEquals("11", response.frame.payloadAscii)
    }

    @Test
    fun pcdConfigQueryReturnsT5AWhenDeviceIsUnavailable() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "T59", "2".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("T5A", response.frame.commandId)
        assertEquals("21", response.frame.payloadAscii)
    }

    @Test
    fun completeEmvConfigQueryReturnsT93WhenDeviceIsUnavailable() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "T92")),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("T93", response.frame.commandId)
        assertEquals("1", response.frame.payloadAscii)
    }

    @Test
    fun completeEmvConfigClearReturnsT95WhenDeviceIsUnavailable() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "T94", "ALL".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("T95", response.frame.commandId)
        assertEquals("11", response.frame.payloadAscii)
    }

    @Test
    fun displayFontSizeReturnsB2Status() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "B1", "2".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("B2", response.frame.commandId)
        assertEquals("0", response.frame.payloadAscii)
    }

    @Test
    fun displayFontColorReturnsB4Status() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "B3", "FFFFFF1E90FF".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals(PINPADFrameType.Transaction, response.frame.frameType)
        assertEquals("B4", response.frame.commandId)
        assertEquals("0", response.frame.payloadAscii)
    }

    @Test
    fun idlePromptIsAckOnly() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "Z8", "BANCO DE BOGOTA".toByteArray())),
        )

        assertEquals(1, responses.size)
        assertEquals(PINPADControl.ACK, responses.single().single())
    }

    @Test
    fun promptTableQueryReturnsLowercaseSpanishForConfigTool() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "1F")),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("1F", response.frame.commandId)
        assertEquals("es\u001Cen\u001Ces", response.frame.payloadAscii)
        val wrapperResponse = response.frame.commandId + response.frame.payloadAscii
        val languages = wrapperResponse.substring(5).split('\u001C')
        assertEquals("es", wrapperResponse.substring(2, 4))
        assertTrue(languages.size > 1)
        assertTrue("es" in languages)
    }

    @Test
    fun promptLanguageSelectionReturnsOk() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "12", "0".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("12", response.frame.commandId)
        assertEquals("0", response.frame.payloadAscii)
    }

    @Test
    fun jpegIdleLogoSetupDoesNotSendFinalEot() {
        val j7Responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "J7", "idle_logo".toByteArray())),
        )
        assertEquals(PINPADControl.ACK, j7Responses[0].single())
        val j7 = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(j7Responses[1]))
        assertEquals("J7", j7.frame.commandId)
        assertEquals("0", j7.frame.payloadAscii)
        val j7FinalResponses = controller.onInbound(PINPADInbound.Control(PINPADControl.ACK))
        assertTrue(j7FinalResponses.isEmpty())

        val j8Responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Transaction, "J8", "1".toByteArray())),
        )
        assertEquals(PINPADControl.ACK, j8Responses[0].single())
        val j8 = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(j8Responses[1]))
        assertEquals("J8", j8.frame.commandId)
        assertEquals("0", j8.frame.payloadAscii)
        val finalResponses = controller.onInbound(PINPADInbound.Control(PINPADControl.ACK))
        assertTrue(finalResponses.isEmpty())
    }

    @Test
    fun administrationFrameResponsesReleaseFinalEotAfterHostAck() {
        val commands = listOf(
            CommandCase(PINPADFrameType.Administration, "02", "0"),
            CommandCase(PINPADFrameType.Administration, "04", "0"),
            CommandCase(PINPADFrameType.Administration, "05", "SN12345678901234"),
            CommandCase(PINPADFrameType.Administration, "06"),
            CommandCase(PINPADFrameType.Administration, "08", "0"),
            CommandCase(PINPADFrameType.Administration, "12", "0"),
            CommandCase(PINPADFrameType.Administration, "13", "4"),
            CommandCase(PINPADFrameType.Administration, "17"),
            CommandCase(PINPADFrameType.Administration, "18", "202608241164614"),
            CommandCase(PINPADFrameType.Administration, "19", "1"),
            CommandCase(PINPADFrameType.Administration, "1C"),
            CommandCase(PINPADFrameType.Administration, "1F"),
            CommandCase(PINPADFrameType.Administration, "1M", "1"),
            CommandCase(PINPADFrameType.Administration, "1P", "\u001A1\u001C1\u001C1"),
            CommandCase(PINPADFrameType.Administration, "M03", "123456789010"),
            CommandCase(PINPADFrameType.Administration, "M04", "0"),
        )

        commands.forEach { command ->
            val commandController = PINPADSessionController(
                deviceInfoProvider = FakeDeviceInfoProvider(),
                codec = codec,
            )
            val responses = commandController.onInbound(
                PINPADInbound.Frame(PINPADFrame(command.frameType, command.commandId, command.payload.toByteArray())),
            )
            val frameResponse = responses.firstOrNull { it.size > 1 } ?: return@forEach
            assertIs<PINPADFrameCodec.DecodeResult.Valid>(
                codec.decode(frameResponse),
                "${command.commandId} should return a valid frame",
            )

            val finalResponses = commandController.onInbound(PINPADInbound.Control(PINPADControl.ACK))
            assertEquals(
                PINPADControl.EOT,
                finalResponses.singleOrNull()?.singleOrNull(),
                "${command.commandId} SI/SO response should release final EOT after ACK",
            )
        }
    }

    @Test
    fun administrationConnectionTestsDoNotReleaseFinalEotAfterHostAck() {
        listOf("11", "14").forEach { commandId ->
            val commandController = PINPADSessionController(
                deviceInfoProvider = FakeDeviceInfoProvider(),
                codec = codec,
            )
            val responses = commandController.onInbound(
                PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, commandId)),
            )
            assertEquals(1, responses.size)
            assertEquals(PINPADControl.ACK, responses.single().single())
            assertTrue(commandController.onInbound(PINPADInbound.Control(PINPADControl.ACK)).isEmpty())
        }
    }

    @Test
    fun transactionFrameResponsesDoNotReleaseFinalEotAfterHostAck() {
        val commands = listOf(
            CommandCase(PINPADFrameType.Transaction, "60", "1234567890123456"),
            CommandCase(PINPADFrameType.Transaction, "62", "D0001"),
            CommandCase(PINPADFrameType.Transaction, "22", "12345678\u001C0"),
            CommandCase(PINPADFrameType.Transaction, "23", ".12345678\u001C0"),
            CommandCase(PINPADFrameType.Transaction, "24", "12345678\u001C00412NPIN\u001CTHANKS\u001CPROCESSING"),
            CommandCase(PINPADFrameType.Transaction, "70", "1234567890123456\u001CD0001"),
            CommandCase(PINPADFrameType.Transaction, "7G", ".1234567890123456\u001C00000000000000000001"),
            CommandCase(PINPADFrameType.Transaction, "7H", "1234567890123456"),
            CommandCase(PINPADFrameType.Transaction, "90", "ABCDEF0123456789FEDCBA9876543210FFFF9876543210E00000"),
            CommandCase(PINPADFrameType.Transaction, "94", "ABCDEF0123456789FEDCBA9876543210FFFF9876543210E00000"),
            CommandCase(PINPADFrameType.Transaction, "98", "01"),
            CommandCase(PINPADFrameType.Transaction, "Q6", ".2"),
            CommandCase(PINPADFrameType.Transaction, "Q7"),
            CommandCase(PINPADFrameType.Transaction, "QB", "1"),
            CommandCase(PINPADFrameType.Transaction, "QK"),
            CommandCase(PINPADFrameType.Transaction, "B1", "2"),
            CommandCase(PINPADFrameType.Transaction, "B3", "FFFFFF000000"),
            CommandCase(PINPADFrameType.Transaction, "Z60", ".1234567890123456\u001C0000000000000000"),
            CommandCase(PINPADFrameType.Transaction, "Z62", ".1234567890123456\u001C00000000000000000412NPIN"),
            CommandCase(PINPADFrameType.Transaction, "Z64", "0"),
            CommandCase(PINPADFrameType.Transaction, "Z66", "40\u001C3132333435363738"),
            CommandCase(PINPADFrameType.Transaction, "J0"),
            CommandCase(PINPADFrameType.Transaction, "J1"),
            CommandCase(PINPADFrameType.Transaction, "J2", "0\u001Cidle_logo"),
            CommandCase(PINPADFrameType.Transaction, "J3", "\u001Cidle_logo"),
            CommandCase(PINPADFrameType.Transaction, "J4", "0"),
            CommandCase(PINPADFrameType.Transaction, "J5", "0\u001Cidle_logo"),
            CommandCase(PINPADFrameType.Transaction, "J7", "idle_logo"),
            CommandCase(PINPADFrameType.Transaction, "J8", "0"),
            CommandCase(PINPADFrameType.Transaction, "J9", "idle_logo"),
            CommandCase(PINPADFrameType.Transaction, "JA", "0"),
            CommandCase(PINPADFrameType.Transaction, "M10"),
            CommandCase(PINPADFrameType.Transaction, "M11"),
            CommandCase(PINPADFrameType.Transaction, "M12", "0"),
            CommandCase(PINPADFrameType.Transaction, "M13", "0\u001Cwelcome.mp3"),
            CommandCase(PINPADFrameType.Transaction, "M14", "welcome.mp3"),
            CommandCase(PINPADFrameType.Transaction, "M15", "50"),
            CommandCase(PINPADFrameType.Transaction, "M16", "welcome.mp3"),
            CommandCase(PINPADFrameType.Transaction, "M17", "es-MX\u001CSG9sYQ=="),
            CommandCase(PINPADFrameType.Transaction, "T01", "1"),
            CommandCase(PINPADFrameType.Transaction, "T03", "1"),
            CommandCase(PINPADFrameType.Transaction, "T05", "1"),
            CommandCase(PINPADFrameType.Transaction, "T07", "1"),
            CommandCase(PINPADFrameType.Transaction, "T09", "1"),
            CommandCase(PINPADFrameType.Transaction, "T0B", "1"),
            CommandCase(PINPADFrameType.Transaction, "T51", "1"),
            CommandCase(PINPADFrameType.Transaction, "T53", "1"),
            CommandCase(PINPADFrameType.Transaction, "T55", "1"),
            CommandCase(PINPADFrameType.Transaction, "T59", "1"),
            CommandCase(PINPADFrameType.Transaction, "T5B", "1"),
            CommandCase(PINPADFrameType.Transaction, "T5D", "1"),
            CommandCase(PINPADFrameType.Transaction, "T5F", "1"),
            CommandCase(PINPADFrameType.Transaction, "T5H", "1"),
            CommandCase(PINPADFrameType.Transaction, "T11"),
            CommandCase(PINPADFrameType.Transaction, "T13"),
            CommandCase(PINPADFrameType.Transaction, "T15", "\u001A000000001000"),
            CommandCase(PINPADFrameType.Transaction, "T17"),
            CommandCase(PINPADFrameType.Transaction, "T19"),
            CommandCase(PINPADFrameType.Transaction, "T1D", "1"),
            CommandCase(PINPADFrameType.Transaction, "T21", "9F02"),
            CommandCase(PINPADFrameType.Transaction, "T25"),
            CommandCase(PINPADFrameType.Transaction, "T27"),
            CommandCase(PINPADFrameType.Transaction, "T29"),
            CommandCase(PINPADFrameType.Transaction, "T31"),
            CommandCase(PINPADFrameType.Transaction, "T33"),
            CommandCase(PINPADFrameType.Transaction, "T34"),
            CommandCase(PINPADFrameType.Transaction, "T35"),
            CommandCase(PINPADFrameType.Transaction, "T37", "\u001A1"),
            CommandCase(PINPADFrameType.Transaction, "T38"),
            CommandCase(PINPADFrameType.Transaction, "T3C"),
            CommandCase(PINPADFrameType.Transaction, "T61", "\u001A000000001000"),
            CommandCase(PINPADFrameType.Transaction, "T63", "9F02"),
            CommandCase(PINPADFrameType.Transaction, "T65", "0"),
            CommandCase(PINPADFrameType.Transaction, "T67"),
            CommandCase(PINPADFrameType.Transaction, "T6C"),
            CommandCase(PINPADFrameType.Transaction, "T71"),
            CommandCase(PINPADFrameType.Transaction, "T72"),
            CommandCase(PINPADFrameType.Transaction, "T73", "7100"),
            CommandCase(PINPADFrameType.Transaction, "T75", "\u001AA00000000300000151"),
            CommandCase(PINPADFrameType.Transaction, "T77", "\u001A4761739001010010"),
            CommandCase(PINPADFrameType.Transaction, "T81", "\u001A000000001000"),
            CommandCase(
                PINPADFrameType.Transaction,
                "T90",
                "T\u001CZmlsZS50eHQ=\u001COUYzMyBiIDA4",
            ),
            CommandCase(PINPADFrameType.Transaction, "T92"),
            CommandCase(PINPADFrameType.Transaction, "T94", "ALL"),
        )

        commands.forEach { command ->
            val commandController = PINPADSessionController(
                deviceInfoProvider = FakeDeviceInfoProvider(),
                codec = codec,
            )
            val responses = commandController.onInbound(
                PINPADInbound.Frame(PINPADFrame(command.frameType, command.commandId, command.payload.toByteArray())),
            )
            val frameResponse = responses.firstOrNull { it.size > 1 } ?: return@forEach
            assertIs<PINPADFrameCodec.DecodeResult.Valid>(
                codec.decode(frameResponse),
                "${command.commandId} should return a valid frame",
            )

            val finalResponses = commandController.onInbound(PINPADInbound.Control(PINPADControl.ACK))
            assertTrue(finalResponses.isEmpty(), "${command.commandId} STX/ETX response should not release final EOT")
        }
    }

    /** Verifies malformed T37 payloads return the A10 format-error result. */
    @Test
    fun t37RejectsMissingOperationDelimiter() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(
                PINPADFrame(PINPADFrameType.Transaction, "T37", "1".toByteArray()),
            ),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("T38", response.frame.commandId)
        assertEquals("12", response.frame.payloadAscii)
    }

    /** Without a device command layer, documented unblock and verify operations report startup failure. */
    @Test
    fun t37ReportsUnsupportedOperationsAsFatal() {
        listOf('2', '3').forEach { operation ->
            val operationController = PINPADSessionController(
                deviceInfoProvider = FakeDeviceInfoProvider(),
                codec = codec,
            )
            val responses = operationController.onInbound(
                PINPADInbound.Frame(
                    PINPADFrame(
                        PINPADFrameType.Transaction,
                        "T37",
                        "\u001A$operation".toByteArray(),
                    ),
                ),
            )

            val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
            assertEquals("T38", response.frame.commandId)
            assertEquals("1100000000", response.frame.payloadAscii)
        }
    }

    @Test
    fun t28OnlineAuthorizationDataIsCompactTlvAndLimitedTo256Bytes() {
        val method = PINPADSessionController::class.java.getDeclaredMethod(
            "formatT28OnlineAuthorizationData",
            String::class.java,
        )
        method.isAccessible = true
        val pinBlockTlv = "DF01081234567890ABCDEF"
        val tlvHex = pinBlockTlv + List(40) { "9F0206000000004000" }.joinToString(separator = "")

        val payload = method.invoke(controller, tlvHex) as String

        assertTrue(PINPADControl.SUB.toInt().toChar() !in payload)
        assertTrue(payload.length <= 512)
        assertTrue(payload.startsWith(pinBlockTlv))
        assertEquals(27, Regex("9F0206000000004000").findAll(payload).count())
    }

    @Test
    fun baudRateChangeCompletesOnlyAfterFinalEotIsReleased() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "13", "81".toByteArray())),
        )

        assertEquals(PINPADControl.ACK, responses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("13", response.frame.commandId)
        assertEquals("0", response.frame.payloadAscii)
        assertNull(controller.consumeCompletedSerialPortChange())

        val finalResponses = controller.onInbound(PINPADInbound.Control(PINPADControl.ACK))

        assertEquals(PINPADControl.EOT, finalResponses.single().single())
        assertEquals(
            SerialPortChange(baudRate = 115_200, dataBits = 8, stopBits = 1, parity = "N"),
            controller.consumeCompletedSerialPortChange(),
        )
        assertNull(controller.consumeCompletedSerialPortChange())
    }

    @Test
    fun baudRateChangeRejectsUnsupportedFlowControlMode() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "13", "8A".toByteArray())),
        )

        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("1", response.frame.payloadAscii)
        controller.onInbound(PINPADInbound.Control(PINPADControl.ACK))
        assertNull(controller.consumeCompletedSerialPortChange())
    }

    @Test
    fun baudRateChangeRejectsTrailingPayload() {
        val responses = controller.onInbound(
            PINPADInbound.Frame(PINPADFrame(PINPADFrameType.Administration, "13", "81X".toByteArray())),
        )

        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(responses[1]))
        assertEquals("1", response.frame.payloadAscii)
    }

    @Test
    fun invalidSignatureRequestUsesAckS2ErrorAndFinalEotHandshake() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val signatureController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            codec = codec,
            asyncResponseSender = asyncResponses::add,
        )

        val immediate = signatureController.onInbound(
            PINPADInbound.Frame(
                PINPADFrame(
                    PINPADFrameType.Transaction,
                    "S1",
                    "001\u001CH\u001CP".toByteArray(),
                ),
            ),
        )

        assertTrue(immediate.isEmpty())
        assertEquals(PINPADControl.ACK, asyncResponses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(asyncResponses[1]))
        assertEquals("S2", response.frame.commandId)
        assertEquals("400000000", response.frame.payloadAscii)

        val finalResponses = signatureController.onInbound(PINPADInbound.Control(PINPADControl.ACK))
        assertEquals(PINPADControl.EOT, finalResponses.single().single())
    }

    @Test
    fun invalidPhotoRequestUsesAckPh2ErrorAndFinalEotHandshake() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val cameraController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            codec = codec,
            asyncResponseSender = asyncResponses::add,
        )

        val immediate = cameraController.onInbound(
            PINPADInbound.Frame(
                PINPADFrame(
                    PINPADFrameType.Transaction,
                    "PH1",
                    "001\u001CF\u001C80".toByteArray(),
                ),
            ),
        )

        assertTrue(immediate.isEmpty())
        assertEquals(PINPADControl.ACK, asyncResponses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(asyncResponses[1]))
        assertEquals("PH2", response.frame.commandId)
        assertEquals("400000000", response.frame.payloadAscii)
        assertEquals(
            PINPADControl.EOT,
            cameraController.onInbound(PINPADInbound.Control(PINPADControl.ACK)).single().single(),
        )
    }

    @Test
    fun invalidQrScanRequestUsesAckQr4ErrorAndFinalEotHandshake() {
        val asyncResponses = CopyOnWriteArrayList<ByteArray>()
        val cameraController = PINPADSessionController(
            deviceInfoProvider = FakeDeviceInfoProvider(),
            codec = codec,
            asyncResponseSender = asyncResponses::add,
        )

        val immediate = cameraController.onInbound(
            PINPADInbound.Frame(
                PINPADFrame(PINPADFrameType.Transaction, "QR3", "001\u001CF".toByteArray()),
            ),
        )

        assertTrue(immediate.isEmpty())
        assertEquals(PINPADControl.ACK, asyncResponses[0].single())
        val response = assertIs<PINPADFrameCodec.DecodeResult.Valid>(codec.decode(asyncResponses[1]))
        assertEquals("QR4", response.frame.commandId)
        assertEquals("4", response.frame.payloadAscii)
        assertEquals(
            PINPADControl.EOT,
            cameraController.onInbound(PINPADInbound.Control(PINPADControl.ACK)).single().single(),
        )
    }

    private open class FakeDeviceInfoProvider : PinpadDeviceInfoProvider {
        override fun modelName(): String = "CT20P"

        override fun serialNumber(): String = "SN12345678901234"

        override fun firmwareVersion(part: Char, option: Char?): one.globalconnect.pinpad.device.FirmwareVersion {
            return one.globalconnect.pinpad.device.FirmwareVersion(version = if (part == '4') "APP0.2.0" else "FW1")
        }

        override fun hardwareCapabilities(): List<String> = listOf("ICC", "MSR", "PCD")
    }

    private class FakeKeyLoadAuthorizer(
        private val protectedCommands: Set<String> = emptySet(),
        clearKeyModeActive: Boolean = false,
    ) : KeyLoadAuthorizer {
        var requestedCommand: String? = null
        var clearKeyActivityCount = 0
        private var clearKeyModeActive = clearKeyModeActive
        private var callback: ((Boolean) -> Unit)? = null

        override fun requiresAuthorization(commandId: String): Boolean = commandId in protectedCommands

        override fun requestAuthorization(commandId: String, onResult: (Boolean) -> Unit): Boolean {
            requestedCommand = commandId
            callback = onResult
            return true
        }

        override fun beginClearKeyInjectionMode(): Boolean {
            clearKeyModeActive = true
            return true
        }

        override fun isClearKeyInjectionModeActive(): Boolean = clearKeyModeActive

        override fun recordClearKeyInjectionActivity() {
            clearKeyActivityCount += 1
        }

        override fun endClearKeyInjectionMode(reason: String): Boolean {
            val wasActive = clearKeyModeActive
            clearKeyModeActive = false
            return wasActive
        }

        override fun onKeypadKey(key: PinpadKeypadKey): Boolean = false

        override fun cancel(): Boolean = false

        fun complete(authorized: Boolean) {
            callback?.invoke(authorized)
        }
    }

    private data class CommandCase(
        val frameType: PINPADFrameType,
        val commandId: String,
        val payload: String = "",
    )
}
