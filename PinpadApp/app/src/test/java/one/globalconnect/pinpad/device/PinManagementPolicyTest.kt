package one.globalconnect.pinpad.device

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinManagementPolicyTest {
    /** Verifies all documented T37 operation codes require the leading SUB delimiter. */
    @Test
    fun parsesExactT37Operations() {
        assertEquals(PinManagementPolicy.Operation.CHANGE, PinManagementPolicy.parseOperation("\u001A1"))
        assertEquals(PinManagementPolicy.Operation.UNBLOCK, PinManagementPolicy.parseOperation("\u001A2"))
        assertEquals(PinManagementPolicy.Operation.VERIFY, PinManagementPolicy.parseOperation("\u001A3"))
        assertNull(PinManagementPolicy.parseOperation("1"))
        assertNull(PinManagementPolicy.parseOperation("\u001A1extra"))
        assertNull(PinManagementPolicy.parseOperation("\u001A4"))
    }

    /** Verifies online PIN, signature, and no-CVM bits are removed during PIN change. */
    @Test
    fun restrictsTerminalCapabilitiesToConfiguredOfflinePinMethods() {
        assertContentEquals(
            byteArrayOf(0xE0.toByte(), 0x90.toByte(), 0xC8.toByte()),
            PinManagementPolicy.restrictTerminalCapabilities(
                byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte()),
            ),
        )
        assertNull(
            PinManagementPolicy.restrictTerminalCapabilities(
                byteArrayOf(0xE0.toByte(), 0x68.toByte(), 0xC8.toByte()),
            ),
        )
    }

    /** Verifies PIN unblock advertises No CVM and never advertises either offline-PIN method. */
    @Test
    fun restrictsPinUnblockCapabilitiesToNoCvm() {
        val restricted = PinManagementPolicy.restrictToNoCvmTerminalCapabilities(
            byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte()),
        )

        assertNotNull(restricted)
        assertEquals(0x08, restricted[1].toInt() and 0xFF)
    }

    /** Verifies the online request requires zero amount, ARQC, and successful offline PIN. */
    @Test
    fun validatesOfflinePinChangeArqc() {
        val tags = requiredArqcTags().toMutableMap()

        assertTrue(PinManagementPolicy.isValidChangeArqc(tags))
        tags["9F02"] = "000000000001"
        assertFalse(PinManagementPolicy.isValidChangeArqc(tags))
        tags["9F02"] = PinManagementPolicy.ZERO_AMOUNT
        tags["9F34"] = "020002"
        assertFalse(PinManagementPolicy.isValidChangeArqc(tags))
    }

    /** Verifies change expects AAC while approved unblock expects TC after issuer-script processing. */
    @Test
    fun acceptsOperationSpecificCompletionCryptogram() {
        val tags = mapOf("9F27" to "00", "95" to "0000000000", "9B" to "EC00")

        assertTrue(
            PinManagementPolicy.isSuccessfulMaintenanceCompletion(
                PinManagementPolicy.Operation.CHANGE,
                "85",
                tags,
            ),
        )
        assertTrue(
            PinManagementPolicy.isSuccessfulMaintenanceCompletion(
                PinManagementPolicy.Operation.UNBLOCK,
                "00",
                tags + ("9F27" to "40"),
            ),
        )
        assertFalse(
            PinManagementPolicy.isSuccessfulMaintenanceCompletion(
                PinManagementPolicy.Operation.UNBLOCK,
                "00",
                tags,
            ),
        )
        assertFalse(
            PinManagementPolicy.isSuccessfulMaintenanceCompletion(
                PinManagementPolicy.Operation.UNBLOCK,
                "85",
                tags + ("9F27" to "40"),
            ),
        )
        assertFalse(
            PinManagementPolicy.isSuccessfulMaintenanceCompletion(
                PinManagementPolicy.Operation.CHANGE,
                "85",
                tags + ("9F27" to "40"),
            ),
        )
        assertFalse(
            PinManagementPolicy.isSuccessfulMaintenanceCompletion(
                PinManagementPolicy.Operation.CHANGE,
                "85",
                tags + ("95" to "0000000020"),
            ),
        )
        assertFalse(
            PinManagementPolicy.isSuccessfulMaintenanceCompletion(
                PinManagementPolicy.Operation.CHANGE,
                "85",
                tags - "9B",
            ),
        )
    }

    /** Verifies unblock requires No CVM and rejects offline-PIN evidence. */
    @Test
    fun validatesOfflinePinUnblockArqc() {
        val tags = requiredArqcTags().toMutableMap()
        tags["9F34"] = "1F0002"

        assertTrue(PinManagementPolicy.isValidUnblockArqc(tags))
        tags["9F34"] = "3F0002"
        assertTrue(PinManagementPolicy.isValidUnblockArqc(tags))
        tags["9F34"] = "1F0001"
        assertFalse(PinManagementPolicy.isValidUnblockArqc(tags))
        tags["9F34"] = "010002"
        assertFalse(PinManagementPolicy.isValidUnblockArqc(tags))
    }

    /** Builds the complete minimum tag set used by the ARQC policy tests. */
    private fun requiredArqcTags(): Map<String, String> = mapOf(
        "9F02" to PinManagementPolicy.ZERO_AMOUNT,
        "9F03" to PinManagementPolicy.ZERO_AMOUNT,
        "9F1A" to "0340",
        "95" to "0000000000",
        "5F2A" to "0340",
        "9A" to "260822",
        "9C" to "00",
        "9F37" to "11223344",
        "82" to "5800",
        "9F36" to "0001",
        "9F10" to "0110A0000000000000000000000000000000",
        "9F26" to "1122334455667788",
        "9F27" to "80",
        "9F34" to "010002",
    )
}
