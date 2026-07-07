package com.uic.uicpaymentapp.systeminfo

import android.os.Build
import com.uic.tms.payment_app.TMSDATA
import com.uic.uicpaymentapp.BuildConfig
import com.uic.uicpaymentapp.uicpos.pos.model.InfoMgmt

/**
 * Builds an [InfoMgmt] record from the information available in the TMS database.
 */
fun buildInfoMgmtFromTms(tmsDatabase: TMSDATA): InfoMgmt {
    val primaryAcquirer = tmsDatabase.Acquirer.firstOrNull()
    val primaryTerminal = tmsDatabase.Terminal.firstOrNull()

    val merchantName = listOfNotNull(
        primaryTerminal?.MerchantTitle1,
        primaryTerminal?.MerchantTitle2,
        primaryTerminal?.MerchantTitle3,
    ).filter { it.isNotBlank() }
        .joinToString(separator = " ")

    val merchantAddress = listOfNotNull(
        primaryTerminal?.MerchantText1,
        primaryTerminal?.MerchantText2,
        primaryTerminal?.MerchantText3,
    ).filter { it.isNotBlank() }
        .joinToString(separator = "\n")

    val serialNumber = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Build.getSerial()
        } else {
            @Suppress("DEPRECATION")
            Build.SERIAL
        }
    } catch (_: SecurityException) {
        ""
    } catch (_: Exception) {
        ""
    }

    return InfoMgmt(
        PaymentProcessorId = primaryAcquirer?.AcqID ?: "",
        PaymentProcessorName = primaryAcquirer?.AcquirerName ?: "",
        MerchantId = primaryAcquirer?.MerchID ?: "",
        GroupId = primaryTerminal?.MerchantTitle2 ?: "",
        SiteId = primaryTerminal?.MerchantTitle3 ?: "",
        TerminalId = primaryTerminal?.TermID ?: "",
        MerchantName = merchantName,
        MerchantAddress = merchantAddress,
        DeviceType = Build.MANUFACTURER,
        DeviceName = Build.MODEL,
        DeviceVer = Build.VERSION.RELEASE ?: "",
        SysVer = BuildConfig.VERSION_NAME,
        SerialNo = serialNumber,
        DeviceFeatureList = primaryAcquirer?.HostProtocol?.toString() ?: "",
        AcquirerNii = primaryAcquirer?.NII?.toString() ?: "",
        LicenseId = "",
        DeviceId = primaryTerminal?.TermID ?: "",
        DeveloperId = "",
        VersionNo = "",
        ClerkId = "",
        PaymentEngineVer = "",
    )
}
