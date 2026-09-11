package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_EmvCtlsConfig(
    var config_id: String = "",
    var description: String = "",
    var aid: String = "",
    var kernelId: String = "",
    var transactionType: String = "",
    var currency: String = "",
    var floorLimit: String = "",
    var transactionLimit: String = "",
    var cvmLimit: String = "",
    var transactionLimitODCVM: String = "",
    var readerFloorLimit: String = "",
    var tacDefault: String = "",
    var tacDenial: String = "",
    var tacOnline: String = "",
    var statusCheck: String = "",
    var zeroAmtAccepted: String = "",
    var extendedSelection: String = "",
    var cvn17Support: String = "",
    var trackOutput: String = "",
    var zeroAmtCheck: String = "",
    var terminalCapabilities: String = "",
    var terminalType: String = "",
    var ttq: String = "",
    var cardDataInputCapa: String = "",
    var cvmCapaCvmRequired: String = "",
    var cvmCapaNoCvmRequired: String = "",
    var defaultUdol: String = "",
    var kernelConfig: String = "",
    var magstripeAvn: String = "",
    var magCvmCapaCvmRequired: String = "",
    var magCvmCapaNoCvmRequired: String = "",
    var tornLifeTime: String = "",
    var tornMaxRecords: String = "",
    var secCapability: String = "",
    var termRiskData: String = "",
    var onlinePinCap: Int = 1,
    var signatureCap: Boolean = true,
    var noCVMCap: Boolean = true,
    var manualKeyEntryCap: Boolean = true,
    var magneticStripeCap: Boolean = true,
    var contactChipCap: Boolean = true,
    var offlineClearPinCap: Boolean = true,
    var offlineEncrPinCap: Boolean = true,
    var sdaCap: Boolean = true,
    var ddaCap: Boolean = true,
    var cardCaptureCap: Boolean = false,
    var cdaCap: Boolean = true,
    var simplifiedCapabilityFlagsConfigured: Boolean = false,
    var extraTag01Name: String = "",
    var extraTag01Type: String = "",
    var extraTag01Value: String = "",
    var extraTag02Name: String = "",
    var extraTag02Type: String = "",
    var extraTag02Value: String = "",
    var extraTag03Name: String = "",
    var extraTag03Type: String = "",
    var extraTag03Value: String = "",
    var extraTag04Name: String = "",
    var extraTag04Type: String = "",
    var extraTag04Value: String = "",
    var extraTag05Name: String = "",
    var extraTag05Type: String = "",
    var extraTag05Value: String = ""
) {
    val AID: String get() = aid
    val TACDefault: String get() = tacDefault
    val TACDenial: String get() = tacDenial
    val TACOnline: String get() = tacOnline
    val FloorLimit: Long get() = floorLimit.toLongOrNull() ?: 0L
    val TransactionLimit: Long get() = transactionLimit.toLongOrNull() ?: 0L
    val CVMReqLimit: Long get() = cvmLimit.toLongOrNull() ?: 0L

    companion object {
        fun fromJson(json: JSONObject): TMS_EmvCtlsConfig {
            val aid = TMS_Json.readString(json, "aid")
            val legacyTerminalCapabilities = TMS_Json.readString(json, "terminalCapabilities")
            val onlinePinCap = TMS_Json.readBinaryFlagDefault(json, "onlinePinCap", 1)
            val signatureCap = TMS_Json.readBinaryFlagDefault(json, "signatureCap", 1) == 1
            val noCvmCap = TMS_Json.readBinaryFlagDefault(json, "noCVMCap", 1) == 1
            val simplifiedFlagsConfigured = TMS_ContactlessCapabilityFlags.hasSimplifiedFlags(json)
            val capabilityFlags = TMS_ContactlessCapabilityFlags.fromJson(
                json = json,
                legacyTerminalCapabilities = legacyTerminalCapabilities,
                onlinePin = onlinePinCap == 1,
                signature = signatureCap,
                noCvm = noCvmCap,
            )
            val derived = capabilityFlags.deriveTechnicalValues(aid)
            fun technicalValue(name: String, derivedValue: String): String =
                if (simplifiedFlagsConfigured) derivedValue else TMS_Json.readString(json, name)

            return TMS_EmvCtlsConfig(
                config_id = TMS_Json.readString(json, "config_id"),
                description = TMS_Json.readString(json, "description"),
                aid = aid,
                kernelId = technicalValue("kernelId", derived.kernelIdentifier),
                transactionType = TMS_Json.readString(json, "transactionType"),
                currency = TMS_Json.readString(json, "currency"),
                floorLimit = TMS_Json.readString(json, "floorLimit"),
                transactionLimit = TMS_Json.readString(json, "transactionLimit"),
                cvmLimit = TMS_Json.readString(json, "cvmLimit"),
                transactionLimitODCVM = TMS_Json.readString(json, "transactionLimitODCVM"),
                readerFloorLimit = TMS_Json.readString(json, "readerFloorLimit"),
                tacDefault = TMS_Json.readString(json, "tacDefault"),
                tacDenial = TMS_Json.readString(json, "tacDenial"),
                tacOnline = TMS_Json.readString(json, "tacOnline"),
                statusCheck = TMS_Json.readString(json, "statusCheck"),
                zeroAmtAccepted = TMS_Json.readString(json, "zeroAmtAccepted"),
                extendedSelection = TMS_Json.readString(json, "extendedSelection"),
                cvn17Support = TMS_Json.readString(json, "cvn17Support"),
                trackOutput = TMS_Json.readString(json, "trackOutput"),
                zeroAmtCheck = TMS_Json.readString(json, "zeroAmtCheck"),
                terminalCapabilities = technicalValue("terminalCapabilities", derived.terminalCapabilities),
                terminalType = technicalValue("terminalType", derived.terminalType),
                ttq = technicalValue("ttq", derived.ttq),
                cardDataInputCapa = technicalValue("cardDataInputCapa", derived.cardDataInputCapability),
                cvmCapaCvmRequired = technicalValue("cvmCapaCvmRequired", derived.cvmCapabilityRequired),
                cvmCapaNoCvmRequired = technicalValue("cvmCapaNoCvmRequired", derived.cvmCapabilityNoCvmRequired),
                defaultUdol = technicalValue("defaultUdol", derived.defaultUdol),
                kernelConfig = technicalValue("kernelConfig", derived.kernelConfiguration),
                magstripeAvn = technicalValue("magstripeAvn", derived.magstripeApplicationVersion),
                magCvmCapaCvmRequired = technicalValue(
                    "magCvmCapaCvmRequired",
                    derived.magstripeCvmCapabilityRequired,
                ),
                magCvmCapaNoCvmRequired = technicalValue(
                    "magCvmCapaNoCvmRequired",
                    derived.magstripeCvmCapabilityNoCvmRequired,
                ),
                tornLifeTime = technicalValue("tornLifeTime", derived.tornTransactionLifetime),
                tornMaxRecords = technicalValue("tornMaxRecords", derived.tornTransactionMaxRecords),
                secCapability = technicalValue("secCapability", derived.securityCapability),
                termRiskData = technicalValue("termRiskData", derived.terminalRiskManagementData),
                onlinePinCap = onlinePinCap,
                signatureCap = signatureCap,
                noCVMCap = noCvmCap,
                manualKeyEntryCap = capabilityFlags.manualKeyEntry,
                magneticStripeCap = capabilityFlags.magneticStripe,
                contactChipCap = capabilityFlags.contactChip,
                offlineClearPinCap = capabilityFlags.clearOfflinePin,
                offlineEncrPinCap = capabilityFlags.encipheredOfflinePin,
                sdaCap = capabilityFlags.sda,
                ddaCap = capabilityFlags.dda,
                cardCaptureCap = capabilityFlags.cardCapture,
                cdaCap = capabilityFlags.cda,
                simplifiedCapabilityFlagsConfigured = simplifiedFlagsConfigured,
                extraTag01Name = TMS_Json.readString(json, "extraTag01Name"),
                extraTag01Type = TMS_Json.readString(json, "extraTag01Type"),
                extraTag01Value = TMS_Json.readString(json, "extraTag01Value"),
                extraTag02Name = TMS_Json.readString(json, "extraTag02Name"),
                extraTag02Type = TMS_Json.readString(json, "extraTag02Type"),
                extraTag02Value = TMS_Json.readString(json, "extraTag02Value"),
                extraTag03Name = TMS_Json.readString(json, "extraTag03Name"),
                extraTag03Type = TMS_Json.readString(json, "extraTag03Type"),
                extraTag03Value = TMS_Json.readString(json, "extraTag03Value"),
                extraTag04Name = TMS_Json.readString(json, "extraTag04Name"),
                extraTag04Type = TMS_Json.readString(json, "extraTag04Type"),
                extraTag04Value = TMS_Json.readString(json, "extraTag04Value"),
                extraTag05Name = TMS_Json.readString(json, "extraTag05Name"),
                extraTag05Type = TMS_Json.readString(json, "extraTag05Type"),
                extraTag05Value = TMS_Json.readString(json, "extraTag05Value")
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_EmvCtlsConfig> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_EmvCtlsConfig>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }
    }
}
