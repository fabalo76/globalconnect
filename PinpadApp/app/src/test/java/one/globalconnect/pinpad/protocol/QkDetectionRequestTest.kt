package one.globalconnect.pinpad.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QkDetectionRequestTest {
    @Test fun bareCommandKeepsAllReaders() {
        assertEquals(QkDetectionRequest(), QkDetectionRequest.parse(""))
    }
    @Test fun allNonemptyMasksSelectExactlyTheirReaders() {
        for (mask in 1..7) {
            val bits = mask.toString(2).padStart(3, '0')
            val request = assertNotNull(QkDetectionRequest.parse(bits))
            assertEquals(bits[0] == '1', request.swipe)
            assertEquals(bits[1] == '1', request.chip)
            assertEquals(bits[2] == '1', request.contactless)
        }
    }
    @Test fun preservesDisplayTextAndEmptyPositions() {
        val request = assertNotNull(QkDetectionRequest.parse("011\u001cDevolución\u001c€ 1.234,56"))
        assertEquals("Devolución", request.transactionName)
        assertEquals("€ 1.234,56", request.formattedAmount)
        assertEquals("111", QkDetectionRequest.parse("\u001c\u001cUS$10.00")?.interfaces)
        assertNull(QkDetectionRequest.parse("100\u001c")?.transactionName)
    }
    @Test fun rejectsMalformedRequests() {
        for (payload in listOf("000", "01", "1011", "1a1", "111\u001ca\u001cb\u001cc", "111\u001cbad\nname", "111\u001c" + "x".repeat(65))) {
            assertNull(QkDetectionRequest.parse(payload), payload)
        }
    }
}
