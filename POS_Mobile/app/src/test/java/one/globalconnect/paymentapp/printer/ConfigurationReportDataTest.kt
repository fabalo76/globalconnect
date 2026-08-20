package one.globalconnect.paymentapp.printer

import com.nexgo.oaf.apiv3.device.pinpad.WorkKeyTypeEnum
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import one.globalconnect.tms.paymentapp.TMS_Issuer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationReportDataTest {

    @Test
    fun `configuration report is limited to terminal and acquirer operational settings`() {
        val database = TMSDATA(
            applicationId = "PAYMENT_APP",
            schemaVersion = 4,
            host_connection_info = listOf(
                TMS_HostConnectionInfo(
                    config_id = "host-1",
                    tlsClientPrivateKeyPem = "PRIVATE SECRET",
                    tlsPrivateKeyPassword = "PASSWORD SECRET",
                ),
            ),
            terminal = listOf(
                TMS_Terminal(
                    onlinePinCap = false,
                    bankPassword = "1234",
                    acquirer = listOf(
                        TMS_Acquirer(
                            acquirer_id = "acq-1",
                            hostConnectionInfoRef = "host-1",
                            pinType = 1,
                            masterKeyId = "01",
                            sessionKeyA = "KEY SECRET A",
                            sessionKeyB = "KEY SECRET B",
                            issuer = listOf(TMS_Issuer(issuer_id = "issuer-1")),
                        ),
                    ),
                ),
            ),
        )

        val sections = buildConfigurationReportSections(database, "123", "456")
        val printable = sections.flatMap { section ->
            listOf(section.title) + section.fields.flatMap { listOf(it.label, it.value) }
        }.joinToString("\n")

        assertTrue(printable.contains("TERMINAL 1 CAPABILITIES"))
        assertTrue(printable.contains("ACQUIRER 1 [acq-1]"))
        assertTrue(printable.contains("Transaction server\nhost-1"))
        assertTrue(printable.contains("PIN type\n1"))
        assertTrue(printable.contains("Key index\n2"))
        assertFalse(printable.contains("ADMIN"))
        assertFalse(printable.contains("PASSWORD POLICY"))
        assertFalse(printable.contains("PIN PROFILE"))
        assertFalse(printable.contains("ISSUER"))
        assertFalse(printable.contains("HOST 1"))
        assertFalse(printable.contains("EMV CONTACT"))
        assertFalse(printable.contains("CARD RANGE"))
        assertFalse(printable.contains("PRIVATE SECRET"))
        assertFalse(printable.contains("PASSWORD SECRET"))
        assertFalse(printable.contains("KEY SECRET"))
        assertFalse(printable.contains("1234"))
    }

    @Test
    fun `pin pad status reports KCV and DUKPT presence only`() {
        val statuses = readPinPadKeyStatuses(
            masterKeyKcv = { index -> when (index) { 0 -> "11111111"; 2 -> "22222222"; else -> null } },
            workingKeyKcv = { index, type ->
                if (index == 2 && type == WorkKeyTypeEnum.PINKEY) "33333333" else null
            },
            dukptLoaded = { it == 4 },
        )

        assertEquals(listOf("TLK", "TMK", "TPK", "TIK"), statuses.map { it.type })
        assertEquals("33333333", statuses.first { it.type == "TPK" }.kcv)
        assertEquals(null, statuses.first { it.type == "TIK" }.kcv)
    }
}
