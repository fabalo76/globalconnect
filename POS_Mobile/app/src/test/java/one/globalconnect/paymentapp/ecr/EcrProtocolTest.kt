package one.globalconnect.paymentapp.ecr

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class EcrProtocolTest {
    private fun sale() = EcrMessage("20",fields=linkedMapOf("80" to "POS-001","40" to "000000001234","49" to "840","P1" to "0"))
    @Test fun serviceSaleRoundTrip() {
        val request=sale(); val bytes=request.encode()
        assertEquals(bytes.size-4,String(bytes.copyOfRange(0,4)).toInt())
        assertEquals(request,EcrMessage.decode(bytes))
        assertEquals("12.34",EcrSale.from(request).base)
        assertEquals("0.00",EcrSale.from(request).tax1)
    }
    @Test fun bareDeviceInformationIsTwelveBytes() {
        val request=EcrMessage("D1")
        assertEquals(12,request.encode().size)
        assertEquals(request,EcrMessage.decode(request.encode()))
    }
    @Test fun lrcExcludesStxAndIncludesEtx() {
        val bytes=sale().encode(); val frame=EcrMessage.frame(bytes)
        assertEquals(2,frame.first().toInt())
        assertArrayEquals(bytes,EcrMessage.readPayload(ByteArrayInputStream(frame.drop(1).toByteArray())))
    }
    @Test fun fragmentedReadWorks() {
        val bytes=sale().encode(); val frame=EcrMessage.frame(bytes)
        val stream=object: ByteArrayInputStream(frame.drop(1).toByteArray()) {
            override fun read(b: ByteArray,off:Int,len:Int):Int=super.read(b,off,minOf(1,len))
        }
        assertArrayEquals(bytes,EcrMessage.readPayload(stream))
    }
    @Test fun rejectsBadChecksum() {
        val frame=EcrMessage.frame(sale().encode()); frame[frame.lastIndex]=(frame.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { EcrMessage.readPayload(ByteArrayInputStream(frame.drop(1).toByteArray())) }
    }
    @Test fun rejectsLengthMismatch() {
        val bytes=sale().encode();bytes[0]=57
        assertThrows(IllegalArgumentException::class.java) { EcrMessage.decode(bytes) }
    }
    @Test fun rejectsMissingIdZeroNegativeAndUnsupportedFields() {
        for(fields in listOf(mapOf("40" to "000000000001"),mapOf("80" to "A","40" to "000000000000"),
            mapOf("80" to "A","40" to "-00000000001"),sale().fields+mapOf("ZZ" to "1"))) {
            assertThrows(Exception::class.java) { EcrSale.from(EcrMessage("20",fields=fields)) }
        }
    }
    @Test fun rejectsDuplicateFields() {
        val bytes=sale().encode(); val extra="80001X".toByteArray()+byteArrayOf(28)
        val duplicate=(bytes.size-4+extra.size).toString().padStart(4,'0').toByteArray()+bytes.drop(4).toByteArray()+extra
        assertThrows(IllegalArgumentException::class.java) { EcrMessage.decode(duplicate) }
    }
    @Test fun loyaltySaleUsesDedicatedFlowAndRequiresPositiveAmount() {
        val request = sale().copy(command = "31")
        val parsed = EcrSale.from(EcrMessage.decode(request.encode()))
        assertEquals(one.globalconnect.paymentapp.transaction.TransactionType.LOYALTY_SALE, parsed.transactionType)
        assertEquals("12.34", parsed.base)
        assertThrows(Exception::class.java) { EcrSale.from(request.copy(fields = request.fields + ("40" to "000000000000"))) }
    }
    @Test fun loyaltyBalanceAcceptsLegacyOmittedAmountAndServiceZeroAmount() {
        for (fields in listOf(mapOf("80" to "BAL-1"), mapOf("80" to "BAL-1", "40" to "000000000000", "P1" to "0"))) {
            val parsed = EcrSale.from(EcrMessage("33", fields = fields))
            assertEquals(one.globalconnect.paymentapp.transaction.TransactionType.LOYALTY_BALANCE, parsed.transactionType)
            assertEquals("0.00", parsed.base)
        }
    }
    @Test fun loyaltyBalanceRejectsChargesAndTaxes() {
        for (tag in listOf("40", "44", "45")) {
            assertThrows(Exception::class.java) { EcrSale.from(EcrMessage("33", fields = mapOf("80" to "BAL-1", tag to "000000000001"))) }
        }
    }
    @Test fun pointsUseLegacyMiscAmountScaleWithoutLosingPrecision() {
        assertEquals("1234500", EcrReportData.cents("12345"))
        assertEquals("99999999999900", EcrReportData.cents("999999999999"))
        assertEquals("0", EcrReportData.cents("0"))
    }
}
