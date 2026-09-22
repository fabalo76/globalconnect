package one.globalconnect.paymentapp.uicpos.pos.host.protocol

import com.uic.pos.iso8583.config.IsoConfigParser
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.paymentapp.uicpos.pos.errcode.Constants
import one.globalconnect.paymentapp.uicpos.pos.host.HostProtocolContext
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.TransLog
import one.globalconnect.paymentapp.BuildConfig
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import one.globalconnect.paymentapp.uicpos.pos.host.HostProtocolRegistry

@RunWith(Parameterized::class)
class IsswitchTest(private val protocolName: String) {
    private fun protocol() = requireNotNull(HostProtocolRegistry.protocolFor(createAcquirer().HostProtocol))
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun protocols() = listOf(arrayOf("isswitch"), arrayOf("banpaisgl"), arrayOf("banpaisio"))
    }

    @Test
    fun `installment query and final sale carry distinct message types and plan fields`() {
        val factory = IsoConfigParser.fromFile(isoConfigFile())
        for ((code, processing, tag45) in listOf(
            Triple("InstallmentQuery", "310000", "0".repeat(22) + "S"),
            Triple("ExtrasQuery", "310000", "0".repeat(22) + "C"),
            Triple("QuotaSale", "000000", "06EXT1" + "0".repeat(16) + "S"),
            Triple("ExtrasSale", "010000", "06EXT1" + "0".repeat(16) + "C"),
        )) {
            val query = code.endsWith("Query")
            val message = protocol().buildIsoMessage(HostProtocolContext(
                procInfo = ProcInfo(TransLog = TransLog(TxnType = code,
                    TxnAmt = if (query) "0.00" else "12.25", PaymentPlan = tag45,
                    RefNbr = if (query) "" else "613410000040")),
                acquirer = createAcquirer(), terminal = createTerminal(onlinePinCap = false), isoFactory = factory,
                stanSupplier = { "000001" },
                timestampSupplier = { LocalDateTime.of(2026, 9, 21, 12, 0) },
            ))
            val decoded = factory.parse(message.toByteArray(), 0)
            assertEquals(if (query) "0100" else "0200", decoded.messageType)
            assertEquals(processing, decoded.getFieldValue(3))
            assertEquals(if (query) "000000000000" else "000000001225", decoded.getFieldValue(4))
            assertEquals(tag45, PrivateUseData63.parse(decoded.getFieldValue(63))["45"])
            if (!query) assertEquals("613410000040", decoded.getFieldValue(37))
        }
    }

    @Test
    fun `field 55 sends 9F6E unchanged only when supplied by the kernel`() {
        val isoFactory = IsoConfigParser.fromFile(isoConfigFile())
        for (optionalTag in listOf("", "9F6E0401020304", "9F6E0701020304050607")) {
            val expected = "9F2608ABCDEF1234567890${optionalTag}9F3602001A"
            val message = protocol().buildIsoMessage(
                HostProtocolContext(
                    procInfo = ProcInfo(TransLog = TransLog(
                        TxnType = Constants.HOST_TRANS_SALE,
                        TxnAmt = "20.00",
                        CardDataSource = "CONTACTLESS",
                        Field55 = "5A085413330089700067$expected",
                    )),
                    acquirer = createAcquirer(),
                    terminal = createTerminal(onlinePinCap = false),
                    isoFactory = isoFactory,
                    stanSupplier = { "000008" },
                    timestampSupplier = { LocalDateTime.of(2026, 9, 10, 19, 18, 19) },
                ),
            )

            assertEquals(expected, message.getFieldValue(55))
            assertEquals(expected, isoFactory.parse(message.toByteArray(), 0).getFieldValue(55))
        }
    }

    @Test
    fun `packed entry mode preserves PIN capability for all capture methods`() {
        val isoFactory = IsoConfigParser.fromFile(isoConfigFile())
        val capabilities = listOf(
            TMS_Terminal(onlinePinCap = true, offlineEncrPinCap = false, offlineClearPinCap = false),
            TMS_Terminal(onlinePinCap = false, offlineEncrPinCap = true, offlineClearPinCap = false),
            TMS_Terminal(onlinePinCap = false, offlineEncrPinCap = false, offlineClearPinCap = true),
            TMS_Terminal(onlinePinCap = false, offlineEncrPinCap = false, offlineClearPinCap = false),
        )
        for ((source, capture) in listOf("SWIPE" to "02", "CHIP" to "05", "CONTACTLESS" to "07", "MANUAL" to "01")) {
            for (terminal in capabilities) {
                val pinDigit = if (terminal.onlinePinCap || terminal.offlineEncrPinCap || terminal.offlineClearPinCap) "1" else "2"
                val expected = capture + pinDigit
                val message = protocol().buildIsoMessage(
                    HostProtocolContext(
                        procInfo = ProcInfo(TransLog = TransLog(
                            TxnType = Constants.HOST_TRANS_SALE,
                            TxnAmt = "20.00",
                            CardDataSource = source,
                            PINBlock = null,
                        )),
                        acquirer = createAcquirer(sendAqEntryCap = false, acqEntryCap = ""),
                        terminal = terminal,
                        isoFactory = isoFactory,
                        stanSupplier = { "000008" },
                        timestampSupplier = { LocalDateTime.of(2026, 9, 10, 19, 18, 19) },
                    ),
                )
                val packedField = java.io.ByteArrayOutputStream()
                isoFactory.getParser(22).write(packedField, message.getFieldValue(22))
                org.junit.Assert.assertArrayEquals(
                    "$source entry mode $expected must retain the PIN digit on the wire",
                    byteArrayOf(0, (capture.last().digitToInt() * 16 + pinDigit.toInt()).toByte()),
                    packedField.toByteArray(),
                )
                assertEquals(expected, isoFactory.parse(message.toByteArray(), 0).getFieldValue(22))
            }
        }
    }

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
            Field55 = "9F2608ABCDEF123456789F3602001A950500000000009A03123456",
            PINBlock = "50E55547A5027551",
            KSN = "FFFF9876543210E00008"
        )

        val procInfo = ProcInfo(TransLog = transLog)

        val context = HostProtocolContext(
            procInfo = procInfo,
            acquirer = acquirer,
            terminal = createTerminal(onlinePinCap = true),
            isoFactory = isoFactory,
            stanSupplier = { "000123" },
            timestampSupplier = { LocalDateTime.of(2024, 1, 2, 3, 4, 5) }
        )

        val message = protocol().buildIsoMessage(context)

        assertEquals("0200", message.messageType)
        assertEquals("000000", message.getFieldValue(3))
        assertEquals("000000012345", message.getFieldValue(4))
        assertEquals("000123", message.getFieldValue(11))
        assertEquals("030405", message.getFieldValue(12))
        assertEquals("0102", message.getFieldValue(13))
        assertEquals("051", message.getFieldValue(22))
        assertEquals("005", message.getFieldValue(24))
        assertEquals("00000001", message.getFieldValue(41)?.trim())
        assertEquals("000000000000001", message.getFieldValue(42)?.trim())
        assertEquals("840", message.getFieldValue(49))

        assertEquals("4111111111111111", message.getFieldValue(2))
        assertEquals("3012", message.getFieldValue(14))
        assertEquals("4111111111111111D30122010000000000000", message.getFieldValue(35))
        assertEquals("50E55547A5027551", message.getFieldValue(52))
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
        assertEquals("FFFF9876543210E00008", tags["33"])
        assertEquals("000000000150", tags["82"])
        assertEquals("E0", tags["1C"])
    }

    @Test
    fun `buildIsoMessage removes PAN and track tags from field 55`() {
        val isoFactory = IsoConfigParser.fromFile(isoConfigFile())
        val retainedTags = "9F2608ABCDEF12345678909F3602001A"
        val sensitiveTags = "5A085413330089700067560411223344570455667788"
        val transLog = TransLog(
            TxnType = Constants.HOST_TRANS_SALE,
            TxnAmt = "10.00",
            InvoiceId = "000068",
            CardNbr = "5413330089700067",
            Track2 = ";5413330089700067=49122010123456789?",
            CardDataSource = "CHIP",
            Field55 = retainedTags.substringBefore("9F36") + sensitiveTags + "9F3602001A",
        )

        val message = protocol().buildIsoMessage(
            HostProtocolContext(
                procInfo = ProcInfo(TransLog = transLog),
                acquirer = createAcquirer(),
                terminal = createTerminal(onlinePinCap = true),
                isoFactory = isoFactory,
                stanSupplier = { "000068" },
                timestampSupplier = { LocalDateTime.of(2026, 8, 29, 9, 20, 45) },
            )
        )

        assertEquals(retainedTags, message.getFieldValue(55))
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
            terminal = createTerminal(onlinePinCap = true),
            isoFactory = isoFactory,
            stanSupplier = { "000123" },
            timestampSupplier = { LocalDateTime.of(2024, 2, 3, 4, 5, 6) }
        )

        val message = protocol().buildIsoMessage(context)

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

        val message = protocol().buildIsoMessage(
            HostProtocolContext(
                procInfo = ProcInfo(TransLog = transLog),
                acquirer = acquirer,
                terminal = createTerminal(onlinePinCap = true),
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

    @Test
    fun `offline PIN change maps to zero amount contact ICC request with PIN data`() {
        val isoFactory = IsoConfigParser.fromFile(isoConfigFile())
        val field55 = "9F02060000000000009F2701809F3403010002"
        val transLog = TransLog(
            TxnType = Constants.HOST_TRANS_OFFLINE_PIN_CHANGE,
            TxnAmt = "0.00",
            InvoiceId = "000321",
            CardNbr = "5413330089020045",
            CardDataSource = "CHIP",
            TxnInterface = "1",
            Field55 = field55,
            PINBlock = "50E55547A5027551",
            KSN = "FFFF9876543210E00008",
        )

        val message = protocol().buildIsoMessage(
            HostProtocolContext(
                procInfo = ProcInfo(TransLog = transLog),
                acquirer = createAcquirer(),
                terminal = createTerminal(onlinePinCap = true),
                isoFactory = isoFactory,
                stanSupplier = { "000321" },
                timestampSupplier = { LocalDateTime.of(2024, 2, 3, 4, 5, 6) },
            ),
        )

        assertEquals("0200", message.messageType)
        assertEquals("920000", message.getFieldValue(3))
        assertEquals("000000000000", message.getFieldValue(4))
        assertEquals("051", message.getFieldValue(22))
        assertEquals("50E55547A5027551", message.getFieldValue(52))
        assertEquals(field55, message.getFieldValue(55))
        assertEquals("FFFF9876543210E00008", PrivateUseData63.parse(message.getFieldValue(63))["33"])
    }

    @Test
    fun `PIN unblock maps to processing code 91 without field 52`() {
        val isoFactory = IsoConfigParser.fromFile(isoConfigFile())
        val field55 = "9F02060000000000009F2701809F34031F0002"
        val transLog = TransLog(
            TxnType = Constants.HOST_TRANS_PIN_UNBLOCK,
            TxnAmt = "0.00",
            InvoiceId = "000322",
            CardNbr = "5413330089020102",
            CardDataSource = "CHIP",
            TxnInterface = "1",
            Field55 = field55,
        )

        val message = protocol().buildIsoMessage(
            HostProtocolContext(
                procInfo = ProcInfo(TransLog = transLog),
                acquirer = createAcquirer(),
                terminal = createTerminal(onlinePinCap = false),
                isoFactory = isoFactory,
                stanSupplier = { "000322" },
                timestampSupplier = { LocalDateTime.of(2026, 8, 22, 15, 6, 45) },
            ),
        )

        assertEquals("0200", message.messageType)
        assertEquals("910000", message.getFieldValue(3))
        assertEquals("000000000000", message.getFieldValue(4))
        // PIN capability is independent from the presence of a PIN block. The terminal still
        // supports offline PIN even though online PIN is disabled for this test transaction.
        assertEquals("051", message.getFieldValue(22))
        assertFalse(message.hasField(52))
        assertEquals(field55, message.getFieldValue(55))
    }

    @Test
    fun `loyalty sale matches A10 Banpais processing contract`() {
        val message = protocol().buildIsoMessage(
            HostProtocolContext(
                procInfo = ProcInfo(
                    TransLog = TransLog(
                        TxnType = Constants.HOST_TRANS_LOYALTYSALE,
                        TxnAmt = "122.22",
                        InvoiceId = "000101",
                        CardDataSource = "CONTACTLESS",
                        Track2 = ";5413330089700067=49122010123456789?",
                    ),
                ),
                acquirer = createAcquirer(),
                terminal = createTerminal(onlinePinCap = true),
                isoFactory = IsoConfigParser.fromFile(isoConfigFile()),
                stanSupplier = { "000101" },
                timestampSupplier = { LocalDateTime.of(2026, 9, 6, 12, 0, 0) },
            ),
        )

        assertEquals("0200", message.messageType)
        assertEquals("009500", message.getFieldValue(3))
        assertEquals("000000012222", message.getFieldValue(4))
        assertEquals("071", message.getFieldValue(22))
    }

    @Test
    fun `loyalty balance matches A10 request and omits amount`() {
        val message = protocol().buildIsoMessage(
            HostProtocolContext(
                procInfo = ProcInfo(
                    TransLog = TransLog(
                        TxnType = Constants.HOST_TRANS_LOYALTYBALANCE,
                        TxnAmt = "0.00",
                        InvoiceId = "000102",
                        CardDataSource = "CONTACTLESS",
                        Track2 = ";5413330089700067=49122010123456789?",
                    ),
                ),
                acquirer = createAcquirer(),
                terminal = createTerminal(onlinePinCap = true, sendAppVersion = true),
                isoFactory = IsoConfigParser.fromFile(isoConfigFile()),
                stanSupplier = { "000102" },
                timestampSupplier = { LocalDateTime.of(2026, 9, 6, 12, 1, 0) },
            ),
        )

        assertEquals("0100", message.messageType)
        assertEquals("309500", message.getFieldValue(3))
        assertFalse(message.hasField(4))
        assertEquals("071", message.getFieldValue(22))
        assertEquals(BuildConfig.VERSION_NAME, PrivateUseData63.parse(message.getFieldValue(63))["1D"])
    }

    private fun createAcquirer(
        sendAqEntryCap: Boolean = false,
        acqEntryCap: String = "",
    ): TMS_Acquirer = TMS_Acquirer(
        acquirer_id = "VENTAS",
        acquirerName = "VENTAS",
        terminalId = "00000001",
        merchantId = "000000000000001",
        hostProtocol = protocolName,
        enableSale = true,
        currencyCode = 840,
        nii = 5,
        sendAqEntryCap = sendAqEntryCap,
        acqEntryCap = acqEntryCap,
    )

    /**
     * Creates terminal configuration for host protocol tests.
     *
     * @param onlinePinCap whether the test terminal advertises online PIN capability.
     * @return terminal configuration containing the requested capability.
     */
    private fun createTerminal(
        onlinePinCap: Boolean,
        sendAppVersion: Boolean = false,
    ): TMS_Terminal = TMS_Terminal(
        onlinePinCap = onlinePinCap,
        sendAppVersion = sendAppVersion,
    )

    private fun isoConfigFile(): File {
        val appRelative = File("src/main/assets/iso8583_ISSWITCH_config.xml")
        if (appRelative.exists()) return appRelative
        return File("app/src/main/assets/iso8583_ISSWITCH_config.xml")
    }
}
