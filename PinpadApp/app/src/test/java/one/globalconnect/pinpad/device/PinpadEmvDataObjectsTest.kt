package one.globalconnect.pinpad.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PinpadEmvDataObjectsTest {
    @Test
    fun parsesDataFormatTableFromConfigToolShape() {
        val sub = '\u001A'
        val fs = '\u001C'
        val payload = "1" +
            "${sub}DF01${fs}208080" +
            "${sub}DF02${fs}208101" +
            "${sub}DF03${fs}210101" +
            "${sub}50000010${fs}201010" +
            "${sub}50000011${fs}401081" +
            "${sub}50000004${fs}201040" +
            "${sub}50000005${fs}201040" +
            "${sub}50000007${fs}601010" +
            "${sub}DF71${fs}208080" +
            "${sub}DF72${fs}208101"

        val table = assertNotNull(PinpadEmvDataObjects.parseDataFormatTable(payload))

        assertEquals(10, table.size)
        assertEquals("208080", table["DF01"])
        assertEquals("401081", table["50000011"])
        assertEquals("208101", table["DF72"])
    }

    @Test
    fun parsesCustomTagDataFormatDefinitions() {
        val sub = '\u001A'
        val payload = "1" +
            "${sub}DF01 B 08 08 0" +
            "${sub}DF02 B 08 10 1" +
            "${sub}DF03 B 10 10 1"

        val definitions = assertNotNull(PinpadEmvDataObjects.parseDataFormatDefinitions(payload))

        assertEquals("B", definitions["DF01"]?.format)
        assertEquals(8, definitions["DF01"]?.minLength)
        assertEquals(8, definitions["DF01"]?.maxLength)
        assertFalse(definitions["DF01"]?.isVariableLength ?: true)
        assertEquals("208101", definitions["DF02"]?.rule)
        assertTrue(definitions["DF02"]?.isVariableLength ?: false)
        assertEquals("210101", definitions["DF03"]?.rule)
    }

    @Test
    fun findsLongPrivateTerminalAssignmentTags() {
        val tlv = assertNotNull(PinpadEmvDataObjects.encodeTlv("50000004", "DF01")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("50000005", "DF02")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("50000022", "DF03"))

        assertEquals(
            "DF01",
            PinpadEmvDataObjects.findEncodedTlvValue(tlv, "50000004")
                ?.let { with(PinpadEmvDataObjects) { it.toHex() } },
        )
        assertEquals(
            "DF02",
            PinpadEmvDataObjects.findEncodedTlvValue(tlv, "50000005")
                ?.let { with(PinpadEmvDataObjects) { it.toHex() } },
        )
        assertEquals(
            "DF03",
            PinpadEmvDataObjects.findEncodedTlvValue(tlv, "50000022")
                ?.let { with(PinpadEmvDataObjects) { it.toHex() } },
        )
    }

    @Test
    fun filtersA10TerminalMetaTagsFromKernelTlv() {
        val tlv = assertNotNull(PinpadEmvDataObjects.encodeTlv("FFFF8101", "01")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("9F1A", "0188")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("50000004", "DF01")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("9F33", "E0F8C8"))

        val filtered = assertNotNull(
            PinpadEmvDataObjects.filterTlvRecords(tlv) { tag ->
                tag.startsWith("500000") || tag.startsWith("FFFF81")
            },
        )

        assertEquals(
            assertNotNull(PinpadEmvDataObjects.encodeTlv("9F1A", "0188")) +
                assertNotNull(PinpadEmvDataObjects.encodeTlv("9F33", "E0F8C8")),
            filtered,
        )
    }

    @Test
    fun formatsQueriedTransactionDataInRequestedTagOrder() {
        val sub = '\u001A'
        val fs = '\u001C'
        val tlv = assertNotNull(PinpadEmvDataObjects.encodeTlv("95", "0400008001")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("9F34", "1E0300")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("5A", "5466370916158200"))

        val formatted = PinpadEmvDataObjects.tlvToDataObjectText(tlv, listOf("95", "5A", "9F34", "9B"))

        assertEquals(
            "95${fs}05${fs}0400008001" +
                "${sub}5A${fs}08${fs}5466370916158200" +
                "${sub}9F34${fs}03${fs}1E0300",
            formatted,
        )
    }

    @Test
    fun extractsA10TerminalPrivateTagValues() {
        val tlv = assertNotNull(PinpadEmvDataObjects.encodeTlv("9F1A", "0188")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("50000004", "DF01")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("50000005", "DF02")) +
            assertNotNull(PinpadEmvDataObjects.encodeTlv("FFFF8101", "01"))

        val privateTags = assertNotNull(
            PinpadEmvDataObjects.tlvRecordValueMap(tlv) { tag ->
                tag.startsWith("500000") || tag.startsWith("FFFF81")
            },
        )

        assertEquals(mapOf("50000004" to "DF01", "50000005" to "DF02", "FFFF8101" to "01"), privateTags)
    }

    @Test
    fun parsesPcdApplicationPacketFromConfigToolShape() {
        val sub = '\u001A'
        val fs = '\u001C'
        val payload = "11${sub}00${sub}030000${sub}A0000000031010${sub}9F7A${fs}2${fs}01"

        val packet = assertNotNull(PinpadEmvDataObjects.parsePcdApplicationPacket(payload))

        assertEquals(1, packet.packetNo)
        assertEquals(1, packet.totalPackets)
        assertEquals("00", packet.txn)
        assertEquals("030000", packet.kernelId)
        assertEquals("A0000000031010", packet.aid)
        assertTrue(packet.tlvHex.orEmpty().contains("9F7A0101"))
    }

    @Test
    fun parsesApplicationContinuationWithoutPacketHeader() {
        val sub = '\u001A'
        val fs = '\u001C'
        val first = assertNotNull(
            PinpadEmvDataObjects.parsePacket(
                "12${sub}A0000000031010${sub}9F06${fs}2${fs}A0000000031010",
                hasAidInFirstPacket = true,
            ),
        )
        val continuation = assertNotNull(
            PinpadEmvDataObjects.parsePacket(
                "${sub}FFFF820A${fs}2${fs}829F369F07",
                hasAidInFirstPacket = true,
                inferredPacketNo = 2,
                inferredTotalPackets = first.totalPackets,
            ),
        )

        assertEquals(1, first.packetNo)
        assertEquals(2, first.totalPackets)
        assertEquals("A0000000031010", first.aid)
        assertEquals(2, continuation.packetNo)
        assertEquals(2, continuation.totalPackets)
        assertEquals(null, continuation.aid)
        assertTrue(continuation.tlvHex.orEmpty().contains("FFFF820A05829F369F07"))
    }
}
