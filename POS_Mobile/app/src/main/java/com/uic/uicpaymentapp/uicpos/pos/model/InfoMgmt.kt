package com.uic.uicpaymentapp.uicpos.pos.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class InfoMgmt(
    // request
    var Id: String?      = "",
    var DateTime:String? = "",
    var TimeZone:String? = "",

    // response
    var SysVer: String?            = "",
    var PaymentEngineVer:String?   = "",
    var PaymentProcessorId:String? = "",
    var PaymentProcessorName:String? = "",
    var DeviceVer: String?         = "",
    var SerialNo: String?          = "",
    var LicenseId: String?         = "",
    var SiteId: String?            = "",
    var DeviceId: String?          = "",
    var DeveloperId: String?       = "",
    var VersionNo: String?         = "",
    var ClerkId: String?           = "",
    var GroupId: String?           = "",
    var MerchantId: String?        = "",
    var TerminalId: String?        = "",
    var MerchantName: String?      = "",
    var MerchantAddress: String?   = "",
    var DeviceType: String?        = "",
    var DeviceName: String?        = "",
    var DeviceFeatureList: String? = "",
    var AcquirerNii: String?       = "",
) {
    @PrimaryKey(autoGenerate = true)
    var databaseId: Int = 0
}
