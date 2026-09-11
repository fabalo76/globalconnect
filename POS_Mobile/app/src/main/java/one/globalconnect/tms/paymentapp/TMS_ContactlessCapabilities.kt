package one.globalconnect.tms.paymentapp

import org.json.JSONObject
import java.util.Locale

data class TMS_ContactlessCapabilityFlags(
    val manualKeyEntry: Boolean = true,
    val magneticStripe: Boolean = true,
    val contactChip: Boolean = true,
    val clearOfflinePin: Boolean = true,
    val onlinePin: Boolean = true,
    val signature: Boolean = true,
    val encipheredOfflinePin: Boolean = true,
    val noCvm: Boolean = true,
    val sda: Boolean = true,
    val dda: Boolean = true,
    val cardCapture: Boolean = false,
    val cda: Boolean = true,
) {
    val cardDataInputByte: Int
        get() = flag(manualKeyEntry, MANUAL_KEY_ENTRY) or
            flag(magneticStripe, MAGNETIC_STRIPE) or
            flag(contactChip, CONTACT_CHIP)

    val cvmByte: Int
        get() = flag(clearOfflinePin, CLEAR_OFFLINE_PIN) or
            flag(onlinePin, ONLINE_PIN) or
            flag(signature, SIGNATURE) or
            flag(encipheredOfflinePin, ENCIPHERED_OFFLINE_PIN) or
            flag(noCvm, NO_CVM)

    val securityByte: Int
        get() = flag(sda, SDA) or
            flag(dda, DDA) or
            flag(cardCapture, CARD_CAPTURE) or
            flag(cda, CDA)

    val terminalCapabilities: String
        get() = hex(cardDataInputByte, cvmByte, securityByte)

    val cardDataInputCapability: String
        get() = hex(cardDataInputByte)

    val cvmCapabilityRequired: String
        get() = hex(flag(onlinePin, ONLINE_PIN) or flag(signature, SIGNATURE))

    val cvmCapabilityNoCvmRequired: String
        get() = hex(if (noCvm) NO_CVM else 0)

    // Mastercard DF811F advertises the contactless ODA method; for this terminal
    // only CDA maps to that kernel byte even though 9F33 also reports SDA and DDA.
    val mastercardSecurityCapability: String
        get() = hex(if (cda) CDA else 0)

    fun terminalTransactionQualifiers(aid: String): String {
        val normalizedAid = aid.normalizedHex()
        val base = when {
            normalizedAid.startsWith(VISA_RID) -> VISA_TTQ
            normalizedAid.startsWith(DISCOVER_RID) -> DISCOVER_TTQ
            else -> return ""
        }.hexToBytes() ?: return ""

        if (!onlinePin) base[0] = (base[0].toInt() and ONLINE_PIN_TTQ.inv()).toByte()
        if (!signature) base[0] = (base[0].toInt() and SIGNATURE_TTQ.inv()).toByte()
        return base.toHex()
    }

    fun deriveTechnicalValues(aid: String): TMS_ContactlessTechnicalValues {
        val normalizedAid = aid.normalizedHex()
        val isMastercard = normalizedAid.startsWith(MASTERCARD_RID)
        return TMS_ContactlessTechnicalValues(
            kernelIdentifier = when {
                normalizedAid.startsWith(MASTERCARD_RID) -> "020000"
                normalizedAid.startsWith(VISA_RID) -> "030000"
                normalizedAid.startsWith(AMEX_RID) -> "040000"
                normalizedAid.startsWith(JCB_RID) -> "050000"
                normalizedAid.startsWith(DISCOVER_RID) -> "060000"
                normalizedAid.startsWith(UNIONPAY_RID) -> "070000"
                else -> ""
            },
            terminalCapabilities = terminalCapabilities,
            terminalType = DEFAULT_TERMINAL_TYPE,
            ttq = terminalTransactionQualifiers(aid),
            cardDataInputCapability = cardDataInputCapability,
            cvmCapabilityRequired = cvmCapabilityRequired,
            cvmCapabilityNoCvmRequired = cvmCapabilityNoCvmRequired,
            defaultUdol = if (isMastercard) MASTERCARD_DEFAULT_UDOL else "",
            kernelConfiguration = if (isMastercard) MASTERCARD_KERNEL_CONFIGURATION else "",
            magstripeApplicationVersion = if (isMastercard) MASTERCARD_MAGSTRIPE_VERSION else "",
            magstripeCvmCapabilityRequired = if (isMastercard && signature) "10" else if (isMastercard) "00" else "",
            magstripeCvmCapabilityNoCvmRequired = if (isMastercard) "00" else "",
            tornTransactionLifetime = if (isMastercard) "0000" else "",
            tornTransactionMaxRecords = if (isMastercard) "00" else "",
            securityCapability = if (isMastercard) mastercardSecurityCapability else "",
            terminalRiskManagementData = mastercardTerminalRiskManagementData(normalizedAid),
        )
    }

    private fun mastercardTerminalRiskManagementData(aid: String): String {
        val certifiedContactlessCvmByte = when {
            aid.startsWith("A0000000043060") -> 0x4C
            aid.startsWith("A0000000041010") -> 0x6C
            aid.startsWith("A0000000042203") -> 0x48
            else -> return ""
        }
        val enabledContactlessCvmMask = flag(onlinePin, ONLINE_PIN) or
            flag(signature, SIGNATURE) or
            flag(noCvm, NO_CVM) or
            MASTERCARD_FIXED_CONTACTLESS_CVM_MASK
        val contactlessCvmByte = certifiedContactlessCvmByte and enabledContactlessCvmMask
        return hex(contactlessCvmByte) + MASTERCARD_TERMINAL_RISK_DATA_SUFFIX
    }

    companion object {
        private const val MANUAL_KEY_ENTRY = 0x80
        private const val MAGNETIC_STRIPE = 0x40
        private const val CONTACT_CHIP = 0x20
        private const val CLEAR_OFFLINE_PIN = 0x80
        private const val ONLINE_PIN = 0x40
        private const val SIGNATURE = 0x20
        private const val ENCIPHERED_OFFLINE_PIN = 0x10
        private const val NO_CVM = 0x08
        private const val SDA = 0x80
        private const val DDA = 0x40
        private const val CARD_CAPTURE = 0x20
        private const val CDA = 0x08
        private const val ONLINE_PIN_TTQ = 0x04
        // TTQ byte 1: b3 (04) advertises online PIN and b2 (02) advertises signature.
        private const val SIGNATURE_TTQ = 0x02
        private const val VISA_RID = "A000000003"
        private const val MASTERCARD_RID = "A000000004"
        private const val DISCOVER_RID = "A000000152"
        private const val AMEX_RID = "A000000025"
        private const val JCB_RID = "A000000065"
        private const val UNIONPAY_RID = "A000000333"
        private const val VISA_TTQ = "36004000"
        private const val DISCOVER_TTQ = "36A04000"
        private const val DEFAULT_TERMINAL_TYPE = "22"
        private const val MASTERCARD_DEFAULT_UDOL = "9F6A04"
        private const val MASTERCARD_KERNEL_CONFIGURATION = "B0"
        private const val MASTERCARD_MAGSTRIPE_VERSION = "0001"
        // Byte 1 b3 is Mastercard CDCVM and remains enabled for profiles that certify it.
        private const val MASTERCARD_FIXED_CONTACTLESS_CVM_MASK = 0x04
        private const val MASTERCARD_TERMINAL_RISK_DATA_SUFFIX = "7A800000000000"

        private val SIMPLIFIED_FLAG_NAMES = setOf(
            "manualKeyEntryCap",
            "magneticStripeCap",
            "contactChipCap",
            "offlineClearPinCap",
            "offlineEncrPinCap",
            "sdaCap",
            "ddaCap",
            "cardCaptureCap",
            "cdaCap",
        )

        fun hasSimplifiedFlags(json: JSONObject): Boolean =
            SIMPLIFIED_FLAG_NAMES.any(json::has)

        fun fromJson(
            json: JSONObject,
            legacyTerminalCapabilities: String,
            onlinePin: Boolean,
            signature: Boolean,
            noCvm: Boolean,
        ): TMS_ContactlessCapabilityFlags {
            val legacyBytes = legacyTerminalCapabilities.hexToBytes()
            fun read(name: String, byteIndex: Int, mask: Int, default: Boolean): Boolean =
                if (json.has(name)) {
                    TMS_Json.readBinaryFlagDefault(json, name, if (default) 1 else 0) == 1
                } else {
                    legacyBytes?.getOrNull(byteIndex)?.let { it.toInt() and mask != 0 } ?: default
                }

            return TMS_ContactlessCapabilityFlags(
                manualKeyEntry = read("manualKeyEntryCap", 0, MANUAL_KEY_ENTRY, true),
                magneticStripe = read("magneticStripeCap", 0, MAGNETIC_STRIPE, true),
                contactChip = read("contactChipCap", 0, CONTACT_CHIP, true),
                clearOfflinePin = read("offlineClearPinCap", 1, CLEAR_OFFLINE_PIN, true),
                onlinePin = onlinePin,
                signature = signature,
                encipheredOfflinePin = read(
                    "offlineEncrPinCap",
                    1,
                    ENCIPHERED_OFFLINE_PIN,
                    true,
                ),
                noCvm = noCvm,
                sda = read("sdaCap", 2, SDA, true),
                dda = read("ddaCap", 2, DDA, true),
                cardCapture = read("cardCaptureCap", 2, CARD_CAPTURE, false),
                cda = read("cdaCap", 2, CDA, true),
            )
        }

        private fun flag(enabled: Boolean, mask: Int): Int = if (enabled) mask else 0
        private fun hex(vararg bytes: Int): String = bytes.joinToString("") { "%02X".format(it and 0xFF) }
        private fun String.normalizedHex(): String = trim().replace(" ", "").uppercase(Locale.US)
        private fun String.hexToBytes(): ByteArray? {
            val normalized = normalizedHex()
            if (normalized.isEmpty() || normalized.length % 2 != 0) return null
            return runCatching {
                ByteArray(normalized.length / 2) { index ->
                    normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            }.getOrNull()
        }
        private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }
}

data class TMS_ContactlessTechnicalValues(
    val kernelIdentifier: String,
    val terminalCapabilities: String,
    val terminalType: String,
    val ttq: String,
    val cardDataInputCapability: String,
    val cvmCapabilityRequired: String,
    val cvmCapabilityNoCvmRequired: String,
    val defaultUdol: String,
    val kernelConfiguration: String,
    val magstripeApplicationVersion: String,
    val magstripeCvmCapabilityRequired: String,
    val magstripeCvmCapabilityNoCvmRequired: String,
    val tornTransactionLifetime: String,
    val tornTransactionMaxRecords: String,
    val securityCapability: String,
    val terminalRiskManagementData: String,
)
