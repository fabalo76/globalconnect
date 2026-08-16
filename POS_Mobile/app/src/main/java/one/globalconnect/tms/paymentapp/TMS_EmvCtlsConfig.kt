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
            return TMS_EmvCtlsConfig(
                config_id = TMS_Json.readString(json, "config_id"),
                description = TMS_Json.readString(json, "description"),
                aid = TMS_Json.readString(json, "aid"),
                kernelId = TMS_Json.readString(json, "kernelId"),
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
                terminalCapabilities = TMS_Json.readString(json, "terminalCapabilities"),
                terminalType = TMS_Json.readString(json, "terminalType"),
                ttq = TMS_Json.readString(json, "ttq"),
                cardDataInputCapa = TMS_Json.readString(json, "cardDataInputCapa"),
                cvmCapaCvmRequired = TMS_Json.readString(json, "cvmCapaCvmRequired"),
                cvmCapaNoCvmRequired = TMS_Json.readString(json, "cvmCapaNoCvmRequired"),
                defaultUdol = TMS_Json.readString(json, "defaultUdol"),
                kernelConfig = TMS_Json.readString(json, "kernelConfig"),
                magstripeAvn = TMS_Json.readString(json, "magstripeAvn"),
                magCvmCapaCvmRequired = TMS_Json.readString(json, "magCvmCapaCvmRequired"),
                magCvmCapaNoCvmRequired = TMS_Json.readString(json, "magCvmCapaNoCvmRequired"),
                tornLifeTime = TMS_Json.readString(json, "tornLifeTime"),
                tornMaxRecords = TMS_Json.readString(json, "tornMaxRecords"),
                secCapability = TMS_Json.readString(json, "secCapability"),
                termRiskData = TMS_Json.readString(json, "termRiskData"),
                onlinePinCap = TMS_Json.readBinaryFlagDefault(json, "onlinePinCap", 1),
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
