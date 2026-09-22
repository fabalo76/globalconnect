package one.globalconnect.paymentapp.ecr

import org.junit.Assert.*
import org.junit.Test

class EcrRequestIdentityTest {
    private val sale = EcrMessage("20", fields = mapOf(
        "80" to "0921115450_01", "RQ" to "Krscc1ujX5EGoOk", "40" to "000000001225",
    ))

    @Test fun differentRequestsForSamePosReferenceHaveDifferentIdentity() {
        val next = sale.copy(fields = sale.fields + ("RQ" to "another-request"))
        assertNotEquals(sale.journalIdentity(), next.journalIdentity())
        assertEquals(sale.journalIdentity(), sale.copy().journalIdentity())
        assertEquals("0921115450_01", EcrSale.from(sale).id)
    }

    @Test fun reusedRequestIdKeepsIdentityEvenWhenPayloadChanges() {
        val changed = sale.copy(fields = sale.fields + ("80" to "another-pos-id"))
        assertEquals(sale.journalIdentity(), changed.journalIdentity())
        assertFalse(sale.encode().contentEquals(changed.encode()))
        assertEquals(sale.journalIdentity(), sale.copy(command = "42").journalIdentity())
    }

    @Test fun replyEchoesBothIdsAndLegacyKeysRemainCompatible() {
        val reply = EcrMessage("20", indicator = 1, fields = mapOf("80" to sale.fields.getValue("80")))
            .withRequestIdFrom(sale)
        val decoded = EcrMessage.decode(reply.encode())
        assertEquals(sale.fields["RQ"], decoded.fields["RQ"])
        assertEquals(sale.fields["80"], decoded.fields["80"])
        assertEquals("0921115450_01", sale.copy(fields = sale.fields - "RQ").journalIdentity())
        assertEquals("42:0921115450_01", sale.copy(command = "42", fields = sale.fields - "RQ").journalIdentity())
    }

    @Test fun invalidExplicitRequestIdCannotFallBackToPosReference() {
        listOf("", " ", "x".repeat(65), "bad\nrequest").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                EcrSale.from(sale.copy(fields = sale.fields + ("RQ" to id)))
            }
        }
    }

    @Test fun voidAcceptsSeparateRequestIdentity() {
        EcrVoid.validate(EcrMessage("42", fields = mapOf("80" to "original-pos-id", "RQ" to "void-request", "65" to "23")))
    }
}
