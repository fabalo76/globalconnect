package one.globalconnect.paymentapp.transaction

import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePinChangeContractTest {
    @Test
    fun configurationRequiresTerminalSwitchAndPinCapabilities() {
        val acquirers = listOf(TMS_Acquirer(pinType = 3, pinMasterKeyIndex = "01"))

        assertEquals(
            OfflinePinChangeAvailability.DISABLED_BY_TERMINAL,
            OfflinePinChangeContract.configurationAvailability(TMS_Terminal(), acquirers),
        )
        assertEquals(
            OfflinePinChangeAvailability.ONLINE_PIN_UNAVAILABLE,
            OfflinePinChangeContract.configurationAvailability(
                TMS_Terminal(enableOfflinePinChange = true, onlinePinCap = false),
                acquirers,
            ),
        )
        assertEquals(
            OfflinePinChangeAvailability.OFFLINE_PIN_UNAVAILABLE,
            OfflinePinChangeContract.configurationAvailability(
                TMS_Terminal(
                    enableOfflinePinChange = true,
                    offlineClearPinCap = false,
                    offlineEncrPinCap = false,
                ),
                acquirers,
            ),
        )
    }

    @Test
    fun configuredTransactionIsAvailableWithAcquirerPinProfile() {
        val terminal = TMS_Terminal(enableOfflinePinChange = true)
        val acquirers = listOf(TMS_Acquirer(pinType = 3, pinMasterKeyIndex = "01"))

        assertTrue(OfflinePinChangeContract.isConfiguredForMenu(terminal, acquirers))
        assertEquals(
            OfflinePinChangeAvailability.AVAILABLE,
            OfflinePinChangeContract.configurationAvailability(terminal, acquirers),
        )
    }

    @Test
    fun pinUnblockRequiresFeatureSwitchAndNoCvmButNoAcquirerPinKey() {
        assertEquals(
            OfflinePinChangeAvailability.DISABLED_BY_TERMINAL,
            OfflinePinChangeContract.pinUnblockConfigurationAvailability(TMS_Terminal()),
        )
        assertEquals(
            OfflinePinChangeAvailability.NO_CVM_UNAVAILABLE,
            OfflinePinChangeContract.pinUnblockConfigurationAvailability(
                TMS_Terminal(enableOfflinePinUnblock = true, noCVMCap = false),
            ),
        )
        assertTrue(
            OfflinePinChangeContract.isPinUnblockConfiguredForMenu(
                TMS_Terminal(enableOfflinePinUnblock = true, noCVMCap = true),
            ),
        )
        assertFalse(
            OfflinePinChangeContract.isPinUnblockConfiguredForMenu(
                TMS_Terminal(enableOfflinePinChange = true, noCVMCap = true),
            ),
        )
    }

    @Test
    fun acquirerWithoutPinKeySlotIsRejected() {
        val terminal = TMS_Terminal(enableOfflinePinChange = true)

        assertEquals(
            OfflinePinChangeAvailability.ACQUIRER_PIN_KEY_UNAVAILABLE,
            OfflinePinChangeContract.configurationAvailability(
                terminal,
                listOf(TMS_Acquirer(pinType = 3)),
            ),
        )
    }

    @Test
    fun response85IsApprovedOnlyForOfflinePinChange() {
        assertTrue(
            OfflinePinChangeContract.isHostApproved(TransactionType.OFFLINE_PIN_CHANGE, "85"),
        )
        assertFalse(OfflinePinChangeContract.isHostApproved(TransactionType.SALE, "85"))
        assertTrue(OfflinePinChangeContract.isHostApproved(TransactionType.SALE, "00"))
    }

    @Test
    fun successfulIssuerScriptAcceptsExpectedFinalAac() {
        val completionTags = mapOf(
            "9F27" to "00",
            "95" to "0000008000",
            "9B" to "EC00",
        )

        assertTrue(
            OfflinePinChangeContract.isExpectedSuccessfulAacCompletion(
                transactionType = TransactionType.OFFLINE_PIN_CHANGE,
                responseCode = "85",
                tags = completionTags,
            ),
        )
    }

    @Test
    fun issuerScriptFailureBitRejectsFinalAac() {
        val completionTags = mapOf(
            "9F27" to "00",
            "95" to "0000008020",
            "9B" to "EC00",
        )

        assertFalse(
            OfflinePinChangeContract.isExpectedSuccessfulAacCompletion(
                transactionType = TransactionType.OFFLINE_PIN_CHANGE,
                responseCode = "85",
                tags = completionTags,
            ),
        )
    }

    @Test
    fun expectedAacRequiresPinChangeResponseAndScriptProcessingEvidence() {
        val completionTags = mapOf(
            "9F27" to "00",
            "95" to "0000008000",
            "9B" to "E800",
        )

        assertFalse(
            OfflinePinChangeContract.isExpectedSuccessfulAacCompletion(
                transactionType = TransactionType.OFFLINE_PIN_CHANGE,
                responseCode = "85",
                tags = completionTags,
            ),
        )
        assertFalse(
            OfflinePinChangeContract.isExpectedSuccessfulAacCompletion(
                transactionType = TransactionType.SALE,
                responseCode = "85",
                tags = completionTags + ("9B" to "EC00"),
            ),
        )
        assertFalse(
            OfflinePinChangeContract.isExpectedSuccessfulAacCompletion(
                transactionType = TransactionType.OFFLINE_PIN_CHANGE,
                responseCode = "00",
                tags = completionTags + ("9B" to "EC00"),
            ),
        )
    }

    @Test
    fun offlinePinCvmAcceptsOnlySuccessfulPlaintextOrEncipheredIccPin() {
        assertTrue(OfflinePinChangeContract.isSuccessfulOfflinePinCvm("010002"))
        assertTrue(OfflinePinChangeContract.isSuccessfulOfflinePinCvm("410002"))
        assertTrue(OfflinePinChangeContract.isSuccessfulOfflinePinCvm("040002"))
        assertFalse(OfflinePinChangeContract.isSuccessfulOfflinePinCvm("020002"))
        assertFalse(OfflinePinChangeContract.isSuccessfulOfflinePinCvm("010001"))
        assertFalse(OfflinePinChangeContract.isSuccessfulOfflinePinCvm(null))
    }

    @Test
    fun issuerResponseRequiresAuthenticationDataAndScriptTemplate() {
        val validField55 = "910A11223344556677889900710786058424000210"

        assertTrue(OfflinePinChangeContract.hasRequiredIssuerResponse(validField55))
        assertFalse(
            OfflinePinChangeContract.hasRequiredIssuerResponse("910A11223344556677889900"),
        )
        assertFalse(OfflinePinChangeContract.hasRequiredIssuerResponse("710786058424000210"))
        assertFalse(OfflinePinChangeContract.hasRequiredIssuerResponse("710A86058424000210"))
    }

    @Test
    fun pinUnblockIssuerResponseRequires0012AndMacOnlyUnblockScript() {
        val field55 = "910A11223344556677880012710F860D84240000080102030405060708"

        assertTrue(OfflinePinChangeContract.hasRequiredPinUnblockIssuerResponse(field55))
        assertFalse(
            OfflinePinChangeContract.hasRequiredPinUnblockIssuerResponse(
                field55.replace("0012", "0000"),
            ),
        )
        assertFalse(
            OfflinePinChangeContract.hasRequiredPinUnblockIssuerResponse(
                field55.replace("8424000008", "8424000210"),
            ),
        )
    }

    @Test
    fun arqcRequestRequiresZeroAmountAndSuccessfulOfflinePinCvm() {
        val tags = mutableMapOf(
            "9F02" to "000000000000",
            "9F03" to "000000000000",
            "9F1A" to "0340",
            "95" to "0000000000",
            "5F2A" to "0340",
            "9A" to "240203",
            "9C" to "00",
            "9F37" to "01020304",
            "82" to "5800",
            "9F36" to "0001",
            "9F10" to "0110A000",
            "9F26" to "1122334455667788",
            "9F27" to "80",
            "9F34" to "010002",
        )

        assertTrue(OfflinePinChangeContract.isValidArqcRequest(tags))
        tags["9F02"] = "000000000100"
        assertFalse(OfflinePinChangeContract.isValidArqcRequest(tags))
        tags["9F02"] = "000000000000"
        tags["9F34"] = "020002"
        assertFalse(OfflinePinChangeContract.isValidArqcRequest(tags))
    }

    @Test
    fun pinUnblockArqcRequiresSuccessfulNoCvm() {
        val tags = mutableMapOf(
            "9F02" to "000000000000",
            "9F03" to "000000000000",
            "9F1A" to "0340",
            "95" to "0000000000",
            "5F2A" to "0340",
            "9A" to "260822",
            "9C" to "00",
            "9F37" to "5C50126A",
            "82" to "3000",
            "9F36" to "0002",
            "9F10" to "0110A000",
            "9F26" to "13D3EB24D3E7B4DA",
            "9F27" to "80",
            "9F34" to "1F0002",
        )

        assertTrue(OfflinePinChangeContract.isValidPinUnblockArqcRequest(tags))
        tags["9F34"] = "010002"
        assertFalse(OfflinePinChangeContract.isValidPinUnblockArqcRequest(tags))
        tags["9F34"] = "3F0002"
        assertFalse(OfflinePinChangeContract.isValidPinUnblockArqcRequest(tags))
    }
}
