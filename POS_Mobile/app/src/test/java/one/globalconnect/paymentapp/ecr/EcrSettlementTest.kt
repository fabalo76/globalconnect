package one.globalconnect.paymentapp.ecr

import one.globalconnect.paymentapp.settlement.*
import one.globalconnect.tms.paymentapp.*
import org.junit.Assert.*
import org.junit.Test

class EcrSettlementTest {
    private val request = EcrMessage("61", fields = mapOf("80" to "settle-1", "P1" to "0", "P3" to "1"))

    @Test fun resultDisplayUsesThreeSecondsForSuccessAndFiveForFailure() {
        assertEquals(3_000L, EcrSettlementResultState(true, emptyList()).displayMillis)
        assertEquals(5_000L, EcrSettlementResultState(false, emptyList()).displayMillis)
    }

    @Test fun acceptsServiceFlagsAndOptionalRequestIdentity() {
        EcrSettlement.validate(request)
        EcrSettlement.validate(request.copy(fields = mapOf("80" to "id", "RQ" to "request-id")))
    }

    @Test fun rejectsInvalidFlagsAndAcquirerSelection() {
        listOf(request.copy(indicator = 1), request.copy(more = true),
            request.copy(fields = request.fields + ("P3" to "2")),
            request.copy(fields = request.fields + ("P1" to "yes")),
            request.copy(fields = request.fields + ("DC" to "Bank")),
            request.copy(fields = request.fields - "80")).forEach {
            assertThrows(IllegalArgumentException::class.java) { EcrSettlement.validate(it) }
        }
    }

    @Test fun allAcquirersIncludesEmptyBatchesWithoutChangingForcedSettlementDefault() {
        val database = TMSDATA(terminal = listOf(TMS_Terminal(acquirer = listOf(
            TMS_Acquirer(acquirer_id = "A", acquirerName = "Bank A"),
            TMS_Acquirer(acquirer_id = "B", acquirerName = "Bank B")))))
        assertNull(buildAllAcquirersSettlementRequest(database, emptyList()))
        val targets = buildAllAcquirersSettlementRequest(database, emptyList(), includeEmpty = true)!!.targets
        assertEquals(setOf("A", "B"), targets.map { it.option.id }.toSet())
        assertTrue(targets.all { it.transactions.isEmpty() })
    }

    @Test fun mixedResultsRetainEachAcquirerAndFrameSequence() {
        val results = listOf(SettlementResult.Success("A", "Bank A", 1, "12.25", "$", "00", null),
            SettlementResult.Failure("B", "Bank B", "Host unavailable"))
        val fields = EcrSettlement.resultFields(results)
        assertEquals("Bank A|00|Settled^Bank B|96|Host unavailable^", fields.joinToString("") { it.getValue("SD") })
        val frames = EcrReportData.frames(request, fields, false, "96", "Partial failure")
        assertTrue(frames.dropLast(1).all { it.more })
        assertFalse(frames.last().more)
        assertEquals("96", frames.last().fields["00"])
        frames.forEachIndexed { index, frame ->
            assertEquals(frame, EcrMessage.decode(frame.encode()))
            assertEquals(index.toString(), frame.fields["S1"])
            assertEquals("settle-1", frame.fields["80"])
        }
    }
}
