package one.globalconnect.paymentapp.uicpos.pos.host.protocol

import com.uic.pos.iso8583.config.IsoConfigParser
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.paymentapp.uicpos.pos.errcode.Constants
import one.globalconnect.paymentapp.uicpos.pos.host.HostProtocolContext
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.TransLog
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IsswitchTest {

    @Test
    fun `buildIsoMessage populates expected ISO fields`() {
        val isoFactory = IsoConfigParser.fromFile(isoConfigFile())
        val acquirer = createAcquirer(sendAqEntryCap = true, acqEntryCap = "E0")

        val transLog = TransLog(
            TxnType = Constants.HOST_TRANS_SALE,
            TxnAmt = "123.45",
            Tax1Amt = "1.00",
            OriginalTax1Amt = "1.50",
            CashbackAmt = "10.00",
            CardNbr = "4111111111111111",
            ExpireMonth = "12",
            ExpireYear = "2030",
            InvoiceId = "123456",
            CardDataSource = "CHIP",
            CVV = "123",
            PaymentPlan = "PLN1",
            Track2 = ";4111111111111111=30122010000000000000?",
            Field55 = "9F2608ABCDEF123456789F3602001A950500000000009A03123456"
        )

        val procInfo = ProcInfo(TransLog = transLog)

        val context = HostProtocolContext(
            procInfo = procInfo,
            acquirer = acquirer,
            isoFactory = isoFactory,
            stanSupplier = { "000123" },
            timestampSupplier = { LocalDateTime.of(2024, 1, 2, 3, 4, 5) }
        )

        val message = Isswitch().buildIsoMessage(context)

        assertEquals("0200", message.messageType)
        assertEquals("000000", message.getFieldValue(3))
        assertEquals("000000012345", message.getFieldValue(4))
        assertEquals("000123", message.getFieldValue(11))
        assertEquals("030405", message.getFieldValue(12))
        assertEquals("0102", message.getFieldValue(13))
        assertEquals("0050", message.getFieldValue(22))
        assertEquals("005", message.getFieldValue(24))
        assertEquals("00000001", message.getFieldValue(41)?.trim())
        assertEquals("000000000000001", message.getFieldValue(42)?.trim())
        assertEquals("840", message.getFieldValue(49))

        assertEquals("4111111111111111", message.getFieldValue(2))
        assertEquals("3012", message.getFieldValue(14))
        assertEquals("4111111111111111D30122010000000000000", message.getFieldValue(35))
        assertEquals("9F2608ABCDEF123456789F3602001A950500000000009A03123456", message.getFieldValue(55))

        val field62 = message.getFieldValue(62)
        assertNotNull(field62)
        assertEquals("123456", field62)

        val field63 = message.getFieldValue(63)
        assertNotNull(field63)
        val tags = PrivateUseData63.parse(field63)
        assertEquals("123", tags["16"])
        assertEquals("000000000100", tags["39"])
        assertEquals("000000001000", tags["41"])
        assertEquals("PLN1", tags["45"])
        assertEquals("000000000150", tags["82"])
        assertEquals("E0", tags["1C"])
    }

    @Test
    fun `buildIsoMessage uses message type override for batch upload`() {
        val isoFactory = IsoConfigParser.fromFile(isoConfigFile())
        val acquirer = createAcquirer()

        val transLog = TransLog(
            TxnType = Constants.HOST_TRANS_SALE,
            TxnAmt = "10.00",
            InvoiceId = "123456",
            OriginalMessageType = "0200",
            OriginalStan = "654321",
            MessageTypeOverride = "0320",
        )

        val procInfo = ProcInfo(TransLog = transLog)

        val context = HostProtocolContext(
            procInfo = procInfo,
            acquirer = acquirer,
            isoFactory = isoFactory,
            stanSupplier = { "000123" },
            timestampSupplier = { LocalDateTime.of(2024, 2, 3, 4, 5, 6) }
        )

        val message = Isswitch().buildIsoMessage(context)

        assertEquals("0320", message.messageType)
        assertEquals("0200654321", message.getFieldValue(60))
    }

    @Test
    fun `buildIsoMessage applies derived void code and includes original host identifiers`() {
        val isoFactory = IsoConfigParser.fromFile(isoConfigFile())
        val acquirer = createAcquirer()
        val transLog = TransLog(
            TxnType = Constants.HOST_TRANS_SALE,
            TxnAmt = "10.00",
            InvoiceId = "123456",
            RefNbr = "123456789012",
            AuthCode = "ABC123",
            MessageTypeOverride = "0200",
            ProcessingCodeOverride = "020000",
        )

        val message = Isswitch().buildIsoMessage(
            HostProtocolContext(
                procInfo = ProcInfo(TransLog = transLog),
                acquirer = acquirer,
                isoFactory = isoFactory,
                stanSupplier = { "000124" },
                timestampSupplier = { LocalDateTime.of(2024, 2, 3, 4, 5, 6) },
            )
        )

        assertEquals("0200", message.messageType)
        assertEquals("020000", message.getFieldValue(3))
        assertEquals("123456789012", message.getFieldValue(37))
        assertEquals("ABC123", message.getFieldValue(38))
    }

    private fun createAcquirer(
        sendAqEntryCap: Boolean = false,
        acqEntryCap: String = "",
    ): TMS_Acquirer = TMS_Acquirer(
        acquirer_id = "VENTAS",
        acquirerName = "VENTAS",
        terminalId = "00000001",
        merchantId = "000000000000001",
        hostProtocol = "isswitch",
        enableSale = true,
        currencyCode = 840,
        nii = 5,
        sendAqEntryCap = sendAqEntryCap,
        acqEntryCap = acqEntryCap,
    )

    private fun isoConfigFile(): File {
        val appRelative = File("src/main/assets/iso8583_ISSWITCH_config.xml")
        if (appRelative.exists()) return appRelative
        return File("app/src/main/assets/iso8583_ISSWITCH_config.xml")
    }
}
