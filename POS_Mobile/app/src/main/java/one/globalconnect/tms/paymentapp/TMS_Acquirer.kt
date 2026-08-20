package one.globalconnect.tms.paymentapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.JvmName

data class TMS_Acquirer(
    var acquirer_id: String = "",
    var acquirerName: String = "",
    var headerLine1: String = "",
    var headerLine2: String = "",
    var merchantId: String = "",
    var terminalId: String = "",
    var hostProtocol: String = "",
    var hostConnectionInfoRef: String = "",
    var enableSale: Boolean = false,
    var enableCash: Boolean = false,
    var enablePayment: Boolean = false,
    var enableRefund: Boolean = false,
    var enableLoyalty: Boolean = false,
    var enableInstallments: Boolean = false,
    var enableCheckInOut: Boolean = false,
    var issuer: List<TMS_Issuer> = emptyList(),
    var nii: Long = 0L,
    var countryCode: Long = 0L,
    var currencyCode: Long = 0L,
    var currencySymbol: String = "",
    var initialBatchNumber: Long = 0L,
    var tipProcessingMode: Long = 0L,
    var sendAcquirerEntryCapability: Boolean = false,
    var acquirerEntryCapability: String = "",
    var sendAqEntryCap: Boolean = false,
    var acqEntryCap: String = "",
    var settlementHostConnectionInfoRef: String = "",
    var restrictedBins: String = "",
    var blockFallbackToBins: String = "",
    var emvFeature: Boolean = false,
    var allowFallback: Boolean = false,
    var enableBalance: Boolean = false,
    var pinType: Int = 0,
    var masterKeyId: String = "",
    var sessionKeyA: String = "",
    var sessionKeyB: String = "",
    var pinMasterKeyIndex: String = "",
    var pinStaticSessionKey: String = "",
) {
    val AcqID: String get() = acquirer_id
    @get:JvmName("getLegacyAcquirerName")
    val AcquirerName: String get() = acquirerName
    val MerchID: String get() = merchantId
    val AcqTermID: String get() = terminalId
    val HostProtocol: Long get() = hostProtocol.toLongOrNull() ?: if (hostProtocol.equals("isswitch", ignoreCase = true)) 12L else 0L
    val IPTabTran: String get() = hostConnectionInfoRef
    val NII: Long get() = nii
    @get:JvmName("getLegacyCountryCode")
    val CountryCode: Long get() = countryCode
    @get:JvmName("getLegacyCurrencyCode")
    val CurrencyCode: Long get() = currencyCode
    val Currency: String get() = currencySymbol
    val InitBatchNo: Long get() = initialBatchNumber
    val TIPProcs: Long get() = tipProcessingMode
    @get:JvmName("getLegacySendAqEntryCap")
    val SendAqEntryCap: Boolean get() = sendAcquirerEntryCapability || sendAqEntryCap
    val Acq_Entry_Cap: String get() = acquirerEntryCapability.ifBlank { acqEntryCap }
    val AcqLine1: String get() = headerLine1
    val AcqLine2: String get() = headerLine2
    val AcqLine3: String get() = ""
    val IPTabSet: String get() = settlementHostConnectionInfoRef.ifBlank { hostConnectionInfoRef }
    @get:JvmName("getLegacyRestrictedBins")
    val RestrictedBins: String get() = restrictedBins
    val BlockFallBackToBINS: String get() = blockFallbackToBins
    val EMV_Feature: Boolean get() = emvFeature
    val AllowFallBack: Boolean get() = allowFallback
    val EnableSales: Boolean get() = enableSale
    @get:JvmName("getLegacyEnableCash")
    val EnableCash: Boolean get() = enableCash
    @get:JvmName("getLegacyEnablePayment")
    val EnablePayment: Boolean get() = enablePayment
    val EnableCheckin: Boolean get() = enableCheckInOut
    @get:JvmName("getLegacyEnableBalance")
    val EnableBalance: Boolean get() = enableBalance
    val PINType: Int get() = pinType
    val MkID: String get() = masterKeyId
    val pinKeyScheme: TMS_PinKeyScheme get() = when (pinType) {
        PIN_TYPE_MKSK -> TMS_PinKeyScheme.MKSK
        PIN_TYPE_DUKPT, PIN_TYPE_DUKPT_DYNAMIC -> TMS_PinKeyScheme.DUKPT
        else -> TMS_PinKeyScheme.NONE
    }
    val supportsOnlinePin: Boolean get() = pinKeyScheme != TMS_PinKeyScheme.NONE
    val supportsDukptOnlinePin: Boolean get() = pinType == PIN_TYPE_DUKPT || pinType == PIN_TYPE_DUKPT_DYNAMIC

    /** New pinMasterKeyIndex is a direct Nexgo slot; legacy MkID 00-09 maps to slots 1-10. */
    val nexgoPinKeyIndex: Int?
        get() {
            val directIndex = pinMasterKeyIndex.trim()
                .takeIf { it.isNotEmpty() }
                ?.toIntOrNull(16)
                ?.takeIf { it in 1..10 }
            if (directIndex != null) return directIndex

            return masterKeyId.trim()
                .toIntOrNull(16)
                ?.plus(1)
                ?.takeIf { it in 1..10 }
        }

    /** Encrypted MK/SK PIN session key, or null when the PIN key is already loaded in the slot. */
    val encryptedPinSessionKey: String?
        get() {
            val configuredValue = pinStaticSessionKey.ifBlank { sessionKeyA + sessionKeyB }
            val value = configuredValue.filterNot(Char::isWhitespace).uppercase()
            return value.takeUnless {
                it.isBlank() ||
                    it.all { character -> character == '0' } ||
                    it.all { character -> character == 'F' }
            }
        }

    companion object {
        fun fromJson(json: JSONObject): TMS_Acquirer {
            return TMS_Acquirer(
                acquirer_id = TMS_Json.readString(json, "acquirer_id"),
                acquirerName = TMS_Json.readString(json, "acquirerName"),
                headerLine1 = TMS_Json.readString(json, "headerLine1"),
                headerLine2 = TMS_Json.readString(json, "headerLine2"),
                merchantId = TMS_Json.readString(json, "merchantId"),
                terminalId = TMS_Json.readString(json, "terminalId"),
                hostProtocol = TMS_Json.readString(json, "hostProtocol"),
                hostConnectionInfoRef = TMS_Json.readString(json, "hostConnectionInfoRef"),
                enableSale = TMS_Json.readBoolean(json, "enableSale"),
                enableCash = TMS_Json.readBoolean(json, "enableCash"),
                enablePayment = TMS_Json.readBoolean(json, "enablePayment"),
                enableRefund = TMS_Json.readBoolean(json, "enableRefund"),
                enableLoyalty = TMS_Json.readBoolean(json, "enableLoyalty"),
                enableInstallments = TMS_Json.readBoolean(json, "enableInstallments"),
                enableCheckInOut = TMS_Json.readBoolean(json, "enableCheckInOut"),
                issuer = TMS_Issuer.listFromJson(json.optJSONArray("issuer")),
                nii = TMS_Json.readLong(json, "nii"),
                countryCode = TMS_Json.readLongAny(json, "countryCode", "CountryCode", "CountrCode"),
                currencyCode = TMS_Json.readLongAny(json, "currencyCode", "CurrencyCode"),
                currencySymbol = TMS_Json.readStringAny(json, "currencySymbol", "CurrencySymbol"),
                initialBatchNumber = TMS_Json.readLong(json, "initialBatchNumber"),
                tipProcessingMode = TMS_Json.readLong(json, "tipProcessingMode"),
                sendAcquirerEntryCapability = TMS_Json.readBoolean(json, "sendAcquirerEntryCapability"),
                acquirerEntryCapability = TMS_Json.readString(json, "acquirerEntryCapability"),
                sendAqEntryCap = TMS_Json.readBooleanDefault(json, "sendAqEntryCap", TMS_Json.readBoolean(json, "sendAcquirerEntryCapability")),
                acqEntryCap = TMS_Json.readString(json, "acqEntryCap").ifBlank { TMS_Json.readString(json, "acquirerEntryCapability") },
                settlementHostConnectionInfoRef = TMS_Json.readString(json, "settlementHostConnectionInfoRef"),
                restrictedBins = TMS_Json.readString(json, "restrictedBins"),
                blockFallbackToBins = TMS_Json.readString(json, "blockFallbackToBins"),
                emvFeature = TMS_Json.readBooleanDefault(json, "emvFeature", true),
                allowFallback = TMS_Json.readBooleanDefault(json, "allowFallback", true),
                enableBalance = TMS_Json.readBoolean(json, "enableBalance"),
                pinType = TMS_Json.readLongAny(json, "pinType", "PINType").toInt(),
                masterKeyId = TMS_Json.readStringAny(json, "masterKeyId", "MkID"),
                sessionKeyA = TMS_Json.readStringAny(json, "sessionKeyA", "sessionKey_A"),
                sessionKeyB = TMS_Json.readStringAny(json, "sessionKeyB", "sessionKey_B"),
                pinMasterKeyIndex = TMS_Json.readString(json, "pinMasterKeyIndex"),
                pinStaticSessionKey = TMS_Json.readString(json, "pinStaticSessionKey"),
            )
        }

        fun listFromJson(array: JSONArray?): List<TMS_Acquirer> {
            if (array == null) return emptyList()
            val items = mutableListOf<TMS_Acquirer>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items.add(fromJson(item))
            }
            return items
        }

        private const val PIN_TYPE_MKSK = 1
        private const val PIN_TYPE_DUKPT = 3
        private const val PIN_TYPE_DUKPT_DYNAMIC = 4
    }
}

enum class TMS_PinKeyScheme {
    NONE,
    MKSK,
    DUKPT,
}
