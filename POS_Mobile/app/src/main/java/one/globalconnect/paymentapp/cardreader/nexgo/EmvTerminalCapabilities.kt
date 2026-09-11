package one.globalconnect.paymentapp.cardreader.nexgo

import one.globalconnect.tms.paymentapp.TMS_ContactlessTechnicalValues
import java.util.Locale

internal enum class EmvCapabilityInterface {
    CONTACT,
    CONTACTLESS,
}

internal data class EmvTerminalCapabilityProfile(
    val aid: String,
    val interfaceType: EmvCapabilityInterface,
    val base9F33: ByteArray?,
    val controlledCvmMask: Int,
    val enabledCvmMask: Int,
    val ttq: ByteArray? = null,
    val contactlessTechnicalValues: TMS_ContactlessTechnicalValues? = null,
)

internal object EmvTerminalCapabilities {
    const val PLAINTEXT_OFFLINE_PIN = 0x80
    const val ONLINE_PIN = 0x40
    const val SIGNATURE = 0x20
    const val ENCIPHERED_OFFLINE_PIN = 0x10
    const val NO_CVM = 0x08
    const val STANDARD_CVM_MASK = 0xF8

    fun findProfile(
        profiles: List<EmvTerminalCapabilityProfile>,
        selectedAid: String,
        interfaceType: EmvCapabilityInterface,
    ): EmvTerminalCapabilityProfile? {
        val normalizedAid = selectedAid.normalizedHex()
        return profiles
            .asSequence()
            .filter { it.interfaceType == interfaceType }
            .filter { normalizedAid.startsWith(it.aid.normalizedHex()) }
            .maxByOrNull { it.aid.length }
    }

    fun apply9F33(
        current9F33: ByteArray?,
        profile: EmvTerminalCapabilityProfile,
    ): ByteArray? {
        val source = profile.base9F33?.takeIf { it.size == 3 } ?: current9F33?.takeIf { it.size >= 3 }
        if (source == null) return null

        val result = source.copyOf(3)
        val currentCvm = result[1].toInt() and 0xFF
        result[1] = (
            (currentCvm and profile.controlledCvmMask.inv()) or
                (profile.enabledCvmMask and profile.controlledCvmMask)
            ).toByte()
        return result
    }

    fun applyOfflinePinChangeCvmPolicy(configured9F33: ByteArray?): ByteArray? {
        val source = configured9F33?.takeIf { it.size >= 3 } ?: return null
        val result = source.copyOf(3)
        val offlinePinMask = PLAINTEXT_OFFLINE_PIN or ENCIPHERED_OFFLINE_PIN
        val currentCvm = result[1].toInt() and 0xFF
        val offlinePinCapabilities = currentCvm and offlinePinMask
        if (offlinePinCapabilities == 0) return null
        result[1] = ((currentCvm and STANDARD_CVM_MASK.inv()) or offlinePinCapabilities).toByte()
        return result
    }

    /** Restricts CVM capabilities to No CVM so an already-blocked card never requests its current PIN. */
    fun applyOfflinePinUnblockCvmPolicy(configured9F33: ByteArray?): ByteArray? {
        val source = configured9F33?.takeIf { it.size >= 3 } ?: return null
        val result = source.copyOf(3)
        val currentCvm = result[1].toInt() and 0xFF
        if (currentCvm and NO_CVM == 0) return null
        result[1] = ((currentCvm and STANDARD_CVM_MASK.inv()) or NO_CVM).toByte()
        return result
    }

    fun parse9F33(value: String): ByteArray? {
        val normalized = value.normalizedHex()
        if (normalized.length != 6) return null
        return runCatching {
            ByteArray(3) { index ->
                normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    private fun String.normalizedHex(): String = trim().replace(" ", "").uppercase(Locale.US)
}
