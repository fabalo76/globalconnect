package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_Terminal(
    var headerLine1: String = "",
    var headerLine2: String = "",
    var headerLine3: String = "",
    var headerLine4: String = "",
    var enableSale: Boolean = false,
    var enableCash: Boolean = false,
    var enablePayment: Boolean = false,
    var enableRefund: Boolean = false,
    var enableLoyalty: Boolean = false,
    var sendAppVersion: Boolean = false,
    var ctlsLoyaltyEnabled: Boolean = false,
    var enableInstallments: Boolean = false,
    var enableCheckInOut: Boolean = false,
    var tax1Enabled: Boolean = false,
    var tax1Mandatory: Boolean = false,
    var tax1MaxPercentage: Double = 0.0,
    var tax1DiscountPercentage: Double = 0.0,
    var tax2Enabled: Boolean = false,
    var tax2Mandatory: Boolean = false,
    var tax2MaxPercentage: Double = 0.0,
    var tipProcessingMode: String = "",
    var printReceipt: Boolean = false,
    var printCustomerCopy: Boolean = false,
    var printReports: Boolean = false,
    var printReversal: Boolean = false,
    var capkMode: String = "",
    var onlinePinCap: Boolean = true,
    var signatureCap: Boolean = true,
    var noCVMCap: Boolean = true,
    var offlineEncrPinCap: Boolean = true,
    var offlineClearPinCap: Boolean = true,
    var enableOfflinePinChange: Boolean = false,
    var enableOfflinePinUnblock: Boolean = false,
    var checkInType: String = DEFAULT_CHECK_IN_TYPE,
    var autoFolio: Boolean = false,
    var folioInputMode: String = DEFAULT_FOLIO_INPUT_MODE,
    var allowCheckinManualDataEntry: Boolean = false,
    var tranReportingMethod: String = "",
    var tranReportingBatchSize: Double = 0.0,
    var tranReportingIntervalSeconds: Double = 0.0,
    var adminMessagesEnabled: Boolean = false,
    var adminMessagesRequestPaper: Boolean = false,
    var adminMessagesRequestTechnicianVisit: Boolean = false,
    var adminMessagesRequestExecutiveCall: Boolean = false,
    var adminMessagesRequestTraining: Boolean = false,
    var bankPassword: String = "0000",
    var voidPassword: String = "",
    var clearPassword: String = "",
    var adjustPassword: String = "",
    var refundPassword: String = "",
    var reportPassword: String = "",
    var offlinePassword: String = "",
    var settlementPassword: String = "",
    var cashAdvancePassword: String = "",
    var ctls_application_config: List<TMS_CtlsApplicationConfig> = emptyList(),
    var contact_application_config: List<TMS_ContactApplicationConfig> = emptyList(),
    var acquirer: List<TMS_Acquirer> = emptyList()
) {
    val TermID: String get() = acquirer.firstOrNull()?.AcqTermID ?: ""
    val CAPKKEyConfig: String get() = capkMode
    val IsBCDLength: Boolean get() = false
    val password: Long get() = bankPassword.toLongOrNull() ?: 0L
    @get:JvmName("getLegacyAutoFolio")
    val AutoFolio: Boolean get() = autoFolio
    val MerchantTitle1: String get() = headerLine1
    val MerchantTitle2: String get() = headerLine2
    val MerchantTitle3: String get() = headerLine3
    val MerchantText1: String get() = headerLine1
    val MerchantText2: String get() = headerLine2
    val MerchantText3: String get() = headerLine3
    val TermCap: String get() = ""
    val AddTermCap: String get() = ""
    val TipMaxAdjusts: Long get() = 0L
    val CTLSAppsGroup: String get() = ""
    val ApplyTax: Boolean get() = tax1Enabled
    val ApplyTaxDisc: Boolean get() = tax1DiscountPercentage > 0.0
    val TaxDiscount: Double get() = tax1DiscountPercentage
    val ApplyTax2: Boolean get() = tax2Enabled
    val EnableManualEntry: Boolean get() = true
    val PrintReversalReceipt: Boolean get() = printReversal
    val RefundUseTax: Boolean get() = false
    @get:JvmName("getLegacyTax1Mandatory")
    val Tax1Mandatory: Boolean get() = tax1Mandatory
    val TIPProcs: Long get() = if (tipProcessingMode.equals("manual", ignoreCase = true)) 1L else 0L

    companion object {
        fun fromJson(json: JSONObject): TMS_Terminal {
            return TMS_Terminal(
                headerLine1 = TMS_Json.readString(json, "headerLine1"),
                headerLine2 = TMS_Json.readString(json, "headerLine2"),
                headerLine3 = TMS_Json.readString(json, "headerLine3"),
                headerLine4 = TMS_Json.readString(json, "headerLine4"),
                enableSale = TMS_Json.readBoolean(json, "enableSale"),
                enableCash = TMS_Json.readBoolean(json, "enableCash"),
                enablePayment = TMS_Json.readBoolean(json, "enablePayment"),
                enableRefund = TMS_Json.readBoolean(json, "enableRefund"),
                enableLoyalty = TMS_Json.readBoolean(json, "enableLoyalty"),
                sendAppVersion = TMS_Json.readBoolean(json, "sendAppVersion"),
                ctlsLoyaltyEnabled = TMS_Json.readBoolean(json, "ctlsLoyaltyEnabled"),
                enableInstallments = TMS_Json.readBoolean(json, "enableInstallments"),
                enableCheckInOut = TMS_Json.readBoolean(json, "enableCheckInOut"),
                tax1Enabled = TMS_Json.readBoolean(json, "tax1Enabled"),
                tax1Mandatory = TMS_Json.readBoolean(json, "tax1Mandatory"),
                tax1MaxPercentage = TMS_Json.readDouble(json, "tax1MaxPercentage"),
                tax1DiscountPercentage = TMS_Json.readDouble(json, "tax1DiscountPercentage"),
                tax2Enabled = TMS_Json.readBoolean(json, "tax2Enabled"),
                tax2Mandatory = TMS_Json.readBoolean(json, "tax2Mandatory"),
                tax2MaxPercentage = TMS_Json.readDouble(json, "tax2MaxPercentage"),
                tipProcessingMode = TMS_Json.readString(json, "tipProcessingMode"),
                printReceipt = TMS_Json.readBoolean(json, "printReceipt"),
                printCustomerCopy = TMS_Json.readBoolean(json, "printCustomerCopy"),
                printReports = TMS_Json.readBoolean(json, "printReports"),
                printReversal = TMS_Json.readBoolean(json, "printReversal"),
                capkMode = TMS_Json.readString(json, "capkMode"),
                onlinePinCap = TMS_Json.readBinaryFlagDefault(json, "onlinePinCap", 1) == 1,
                signatureCap = readCapabilityFlag(json, "signatureCap", "signaturePinCap"),
                noCVMCap = readCapabilityFlag(json, "noCVMCap", "noCVMPinCap"),
                offlineEncrPinCap = readCapabilityFlag(json, "offlineEncrPinCap", "offlineEncPinCap"),
                offlineClearPinCap = TMS_Json.readBinaryFlagDefault(json, "offlineClearPinCap", 1) == 1,
                enableOfflinePinChange = TMS_Json.readBoolean(json, "enableOfflinePinChange"),
                enableOfflinePinUnblock = TMS_Json.readBoolean(json, "enableOfflinePinUnblock"),
                checkInType = TMS_Json.readString(json, "checkInType").ifBlank { DEFAULT_CHECK_IN_TYPE },
                autoFolio = TMS_Json.readBoolean(json, "autoFolio"),
                folioInputMode = TMS_Json.readString(json, "folioInputMode").ifBlank { DEFAULT_FOLIO_INPUT_MODE },
                allowCheckinManualDataEntry = TMS_Json.readBoolean(json, "allowCheckinManualDataEntry"),
                tranReportingMethod = TMS_Json.readString(json, "tranReportingMethod"),
                tranReportingBatchSize = TMS_Json.readDouble(json, "tranReportingBatchSize"),
                tranReportingIntervalSeconds = TMS_Json.readDouble(json, "tranReportingIntervalSeconds"),
                adminMessagesEnabled = TMS_Json.readBoolean(json, "adminMessagesEnabled"),
                adminMessagesRequestPaper = TMS_Json.readBoolean(json, "adminMessagesRequestPaper"),
                adminMessagesRequestTechnicianVisit = TMS_Json.readBoolean(json, "adminMessagesRequestTechnicianVisit"),
                adminMessagesRequestExecutiveCall = TMS_Json.readBoolean(json, "adminMessagesRequestExecutiveCall"),
                adminMessagesRequestTraining = TMS_Json.readBoolean(json, "adminMessagesRequestTraining"),
                bankPassword = TMS_Json.readString(json, "bankPassword").ifBlank { "0000" },
                voidPassword = TMS_Json.readString(json, "voidPassword"),
                clearPassword = TMS_Json.readString(json, "clearPassword"),
                adjustPassword = TMS_Json.readString(json, "adjustPassword"),
                refundPassword = TMS_Json.readString(json, "refundPassword"),
                reportPassword = TMS_Json.readString(json, "reportPassword"),
                offlinePassword = TMS_Json.readString(json, "offlinePassword"),
                settlementPassword = TMS_Json.readString(json, "settlementPassword"),
                cashAdvancePassword = TMS_Json.readString(json, "cashAdvancePassword"),
                ctls_application_config = TMS_CtlsApplicationConfig.listFromJson(json.optJSONArray("ctls_application_config")),
                contact_application_config = TMS_ContactApplicationConfig.listFromJson(json.optJSONArray("contact_application_config")),
                acquirer = TMS_Acquirer.listFromJson(json.optJSONArray("acquirer"))
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_Terminal> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_Terminal>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }

        private fun readCapabilityFlag(json: JSONObject, canonicalName: String, alias: String): Boolean {
            val name = if (json.has(canonicalName)) canonicalName else alias
            return TMS_Json.readBinaryFlagDefault(json, name, 1) == 1
        }

        private const val DEFAULT_CHECK_IN_TYPE = "01"
        private const val DEFAULT_FOLIO_INPUT_MODE = "numeric"
    }
}
