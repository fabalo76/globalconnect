package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmvCvmResultTest {

    @Test
    fun `MTIP value 410302 is successful offline plaintext PIN`() {
        assertEquals(CVM_TEXT_OFFLINE_PIN, resolveEmvCvmText("410302", onlinePinRequested = false))
    }

    @Test
    fun `successful online PIN is recognized from 9F34`() {
        assertEquals(CVM_TEXT_ONLINE_PIN, resolveEmvCvmText("420300", onlinePinRequested = true))
    }

    @Test
    fun `successful no CVM is recognized from 9F34`() {
        assertEquals(CVM_TEXT_NO_CVM, resolveEmvCvmText("1F0002", onlinePinRequested = false))
    }

    @Test
    fun `MTIP contactless signature with unknown result requires receipt signature`() {
        val cvmText = resolveKernelCvmText("EMV_CVMR_SIGNATURE")
            ?: resolveEmvCvmText("5E0300", onlinePinRequested = false)
        val presentation = resolveReceiptCvmPresentation(
            transaction = Transaction(
                cardEntryMethod = "EMV_CONTACTLESS",
                CVMText = cvmText,
            ),
            configuredSignatureRequired = true,
        )

        assertEquals(CVM_TEXT_SIGNATURE, cvmText)
        assertFalse(presentation.noSignatureRequiredEmv)
        assertTrue(presentation.signatureRequired)
        assertEquals(CVM_TEXT_SIGNATURE, resolveEmvCvmText("5E0300", onlinePinRequested = false))
    }

    @Test
    fun `Mastercard mobile confirmation is reported as verified CDCVM`() {
        assertEquals(CVM_TEXT_CDCVM, resolveKernelCvmText("EMV_CVMR_CONFVERIFIED"))
        assertEquals(CVM_TEXT_CDCVM, resolveKernelCvmText("EMV_CVMR_CDCVM"))
    }

    @Test
    fun `MTIP unsupported cardholder verification suppresses signature`() {
        val cvmText = resolveEmvCvmText("3F0000", onlinePinRequested = false)
        val presentation = resolveReceiptCvmPresentation(
            transaction = Transaction(
                cardEntryMethod = "EMV",
                CVMText = cvmText,
                signatureRequired = false,
            ),
            configuredSignatureRequired = true,
        )

        assertEquals(CVM_TEXT_NOT_PERFORMED, cvmText)
        assertEquals(ReceiptPinVerification.NONE, presentation.pinVerification)
        assertTrue(presentation.noSignatureRequiredEmv)
        assertFalse(presentation.noSignatureRequiredCvm)
        assertFalse(presentation.noSignatureRequiredCdcvm)
        assertFalse(presentation.signatureRequired)
    }

    @Test
    fun `offline PIN receipt prints ICC verification and suppresses signature`() {
        val presentation = resolveReceiptCvmPresentation(
            transaction = Transaction(
                cardEntryMethod = "EMV",
                CVMText = CVM_TEXT_OFFLINE_PIN,
                signatureRequired = true,
            ),
            configuredSignatureRequired = true,
        )

        assertEquals(ReceiptPinVerification.OFFLINE, presentation.pinVerification)
        assertTrue(presentation.noSignatureRequiredEmv)
        assertFalse(presentation.signatureRequired)
    }

    @Test
    fun `online PIN receipt suppresses configured signature`() {
        val presentation = resolveReceiptCvmPresentation(
            transaction = Transaction(cardEntryMethod = "EMV", CVMText = CVM_TEXT_ONLINE_PIN),
            configuredSignatureRequired = true,
        )

        assertEquals(ReceiptPinVerification.ONLINE, presentation.pinVerification)
        assertTrue(presentation.noSignatureRequiredEmv)
        assertFalse(presentation.signatureRequired)
    }

    @Test
    fun `chip no CVM receipt suppresses configured signature without PIN wording`() {
        val presentation = resolveReceiptCvmPresentation(
            transaction = Transaction(cardEntryMethod = "EMV_CONTACTLESS", CVMText = CVM_TEXT_NO_CVM),
            configuredSignatureRequired = true,
        )

        assertEquals(ReceiptPinVerification.NONE, presentation.pinVerification)
        assertTrue(presentation.noSignatureRequiredEmv)
        assertTrue(presentation.noSignatureRequiredCvm)
        assertFalse(presentation.noSignatureRequiredCdcvm)
        assertFalse(presentation.signatureRequired)
    }

    @Test
    fun `verified CDCVM contactless receipt suppresses signature`() {
        val presentation = resolveReceiptCvmPresentation(
            transaction = Transaction(
                cardEntryMethod = "EMV_CONTACTLESS",
                CVMText = CVM_TEXT_CDCVM,
            ),
            configuredSignatureRequired = true,
        )

        assertEquals(ReceiptPinVerification.NONE, presentation.pinVerification)
        assertTrue(presentation.noSignatureRequiredEmv)
        assertFalse(presentation.noSignatureRequiredCvm)
        assertTrue(presentation.noSignatureRequiredCdcvm)
        assertFalse(presentation.signatureRequired)
    }

    @Test
    fun `magstripe no CVM does not claim an EMV signature exemption`() {
        val presentation = resolveReceiptCvmPresentation(
            transaction = Transaction(cardEntryMethod = "SWIPE", CVMText = CVM_TEXT_NO_CVM),
            configuredSignatureRequired = true,
        )

        assertFalse(presentation.noSignatureRequiredEmv)
        assertTrue(presentation.signatureRequired)
    }
}
