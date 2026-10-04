package one.globalconnect.pinpad.logging

import org.junit.Assert.*
import org.junit.Test

class DetailedLogRecorderTest {
    @Test fun loggingStorageFailureCannotInterruptTransactionCallbacks() {
        var storageFails = true
        val errors = mutableListOf<Exception>()
        val lines = mutableListOf<String>()
        val recorder = DetailedLogRecorder({ true }, { true }, {
            if (storageFails) throw java.io.IOException("unavailable")
            lines.add(it)
        }, errors::add)
        recorder.record("EMV callback")
        assertEquals(1, errors.size)
        storageFails = false
        recorder.record("EMV callback resumed")
        assertEquals(listOf("DETAIL EMV callback resumed"), lines)
    }

    @Test fun portalCaptureAndTerminalVerbosityRemainIndependent() {
        var enabled = false
        var verbose = false
        val lines = mutableListOf<String>()
        val recorder = DetailedLogRecorder({ enabled }, { verbose }, lines::add)
        recorder.recordNormal("TRANSPORT normal")
        recorder.record("EMV_STEP T61 start")
        assertTrue(lines.isEmpty())
        verbose = true // Terminal switch alone must never enable capture.
        recorder.recordNormal("TRANSPORT normal")
        recorder.record("EMV_STEP T61 start")
        assertTrue(lines.isEmpty())
        verbose = false
        enabled = true // Portal enable works with the terminal switch off.
        recorder.recordNormal("TRANSPORT normal")
        recorder.record("EMV_STEP T61 start")
        assertEquals(listOf("TRANSPORT normal"), lines)
        verbose = true
        recorder.record("EMV_STEP T61 onFinish result=Fail(-1) 95=0000008001 9F34=1F0302")
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("onFinish result=Fail(-1)"))
        assertTrue(lines.last().contains("9F34=1F0302"))
        verbose = false // Turning extra detail off must keep portal capture active.
        recorder.recordNormal("EMV_OUTCOME normal completion")
        recorder.record("must not be captured")
        assertEquals(3, lines.size)
        assertEquals("EMV_OUTCOME normal completion", lines.last())
        enabled = false // Portal stop, expiry, or reboot controls both levels.
        verbose = true
        recorder.recordNormal("must not be captured")
        recorder.record("must not be captured")
        assertEquals(3, lines.size)
    }

    @Test fun cardPayloadsKeysAndPinInputAreExcluded() {
        val messages = listOf(
            "TX hex=30313233 ascii=1234 payload=5428750652119390",
            "EMV setTlv tag=57 value=5428750652119390D30112060000",
            "5A=5428750652119390 9F26=0123456789ABCDEF pinBlock=12345678",
            "sessionKey=0123456789ABCDEFFEDCBA9876543210 pin=1234 account=12345",
            "PIN key event keyCode=49 digits=1",
            "COMMAND keypad key=Digit1",
        )
        for (message in messages) {
            val safe = DiagnosticLogSanitizer.sanitize(message)
            assertFalse(safe, safe.contains("5428750652119390"))
            assertFalse(safe, safe.contains("0123456789ABCDEF"))
            assertFalse(safe, safe.contains("pin=1234"))
            assertFalse(safe, safe.contains("account=12345"))
            assertFalse(safe, safe.contains("keyCode=49"))
            assertFalse(safe, safe.contains("Digit1"))
        }
    }

    @Test fun publicApplicationIdsAndSafeTerminalCapabilitiesRemainUseful() {
        assertEquals("EMV aid=A0:00:00:00:04:10:10", DiagnosticLogSanitizer.sanitize("EMV aid=A0000000041010"))
        assertEquals("EMV setTlv tag=9F33 value=E0F8C8", DiagnosticLogSanitizer.sanitize("EMV setTlv tag=9F33 value=E0F8C8"))
        assertEquals("EMV setTlv tag=9F10 value=[redacted]", DiagnosticLogSanitizer.sanitize("EMV setTlv tag=9F10 value=1234"))
        assertFalse(DiagnosticLogSanitizer.sanitize("message=\"PIN 1234 entered\"").contains("1234"))
    }

    @Test fun logEntriesCannotInjectNewLinesOrGrowWithoutBound() {
        val safe = DiagnosticLogSanitizer.sanitize("event\nforged\rline " + "x".repeat(3000))
        assertFalse(safe.contains('\n'))
        assertFalse(safe.contains('\r'))
        assertTrue(safe.length <= 1800)
    }
}
