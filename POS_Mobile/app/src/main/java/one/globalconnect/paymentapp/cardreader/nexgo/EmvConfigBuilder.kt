package one.globalconnect.paymentapp.cardreader.nexgo

import android.util.Log
import com.nexgo.oaf.apiv3.emv.AidEntity
import com.nexgo.oaf.apiv3.emv.AidEntryModeEnum
import one.globalconnect.tms.paymentapp.TMS_EmvContactConfig
import one.globalconnect.tms.paymentapp.TMS_EmvCtlsConfig
import one.globalconnect.tms.paymentapp.TMS_Terminal

internal object EmvConfigBuilder {
    private const val TAG = "EmvConfigBuilder"

    fun buildAidList(
        aidTab: List<TMS_EmvContactConfig>,
        pcdApps: List<TMS_EmvCtlsConfig>,
        terminalOnlinePinCap: Boolean = true,
    ): List<AidEntity> {
        val result = ArrayList<AidEntity>(aidTab.size + pcdApps.size)
        for (tms in aidTab) result += contactAidEntity(tms, terminalOnlinePinCap)
        for (pcd in pcdApps) result += contactlessAidEntity(pcd, terminalOnlinePinCap)
        Log.d(
            TAG,
            "buildAidList contact=${aidTab.size} contactless=${pcdApps.size} " +
                "terminalOnlinePinCap=$terminalOnlinePinCap total=${result.size}",
        )
        for (entity in result) logAidEntity(entity)
        return result
    }

    private fun contactAidEntity(
        tms: TMS_EmvContactConfig,
        terminalOnlinePinCap: Boolean,
    ): AidEntity = AidEntity().apply {
        aid              = tms.AID.lowercase()
        asi              = if (tms.PartSel.toInt() ==1) 0 else 1  // "01"=partial→asi=0, else exact→asi=1
        appVerNum        = tms.AppVerNo
        maxTargetPercent = tms.max_percent.toInt()
        targetPercent    = tms.Percentagesel.toInt()
        threshold        = tms.Thresholdbiassel
        tacDefault       = tms.TAC_Defaul
        tacDenial        = tms.TAC_Denial
        tacOnline        = tms.TAC_Online
        setDdol(trimHexField(tms.Default_DDOL, tms.Default_DDOL_Len))
        floorLimit       = tms.Floor_Limit
        onlinePinCap     = effectiveOnlinePinCap(terminalOnlinePinCap, tms.onlinePinCap)
        aidEntryModeEnum = AidEntryModeEnum.AID_ENTRY_CONTACT
    }

    private fun contactlessAidEntity(
        pcd: TMS_EmvCtlsConfig,
        terminalOnlinePinCap: Boolean,
    ): AidEntity = AidEntity().apply {
        aid                   = pcd.AID.lowercase()
        asi                   = 0  // no PartSel field in PCDApps — default to partial match
        tacDefault            = pcd.TACDefault
        tacDenial             = pcd.TACDenial
        tacOnline             = pcd.TACOnline
        floorLimit            = pcd.FloorLimit
        contactlessFloorLimit = pcd.FloorLimit
        contactlessTransLimit = pcd.TransactionLimit
        contactlessCvmLimit   = pcd.CVMReqLimit
        onlinePinCap          = effectiveOnlinePinCap(terminalOnlinePinCap, pcd.onlinePinCap)
        aidEntryModeEnum      = AidEntryModeEnum.AID_ENTRY_CONTACTLESS
    }

    internal fun effectiveOnlinePinCap(terminalOnlinePinCap: Boolean, aidOnlinePinCap: Int): Int {
        return if (terminalOnlinePinCap && aidOnlinePinCap == 1) 1 else 0
    }

    fun buildTerminalCapabilityProfiles(
        aidTab: List<TMS_EmvContactConfig>,
        pcdApps: List<TMS_EmvCtlsConfig>,
        terminal: TMS_Terminal,
    ): List<EmvTerminalCapabilityProfile> {
        val profiles = ArrayList<EmvTerminalCapabilityProfile>(aidTab.size + pcdApps.size)
        aidTab.forEach { aid -> profiles += contactCapabilityProfile(aid, terminal) }
        pcdApps.forEach { aid -> profiles += contactlessCapabilityProfile(aid, terminal) }
        profiles.forEach { profile ->
            Log.d(
                TAG,
                "[9F33 ${profile.interfaceType}] aid=${profile.aid} " +
                    "base=${profile.base9F33?.toHex()} controlled=%02X enabled=%02X".format(
                        profile.controlledCvmMask,
                        profile.enabledCvmMask,
                    ),
            )
        }
        return profiles
    }

    private fun contactCapabilityProfile(
        aid: TMS_EmvContactConfig,
        terminal: TMS_Terminal,
    ): EmvTerminalCapabilityProfile {
        var enabled = 0
        if (terminal.offlineClearPinCap && aid.offlineClearPinCap) {
            enabled = enabled or EmvTerminalCapabilities.PLAINTEXT_OFFLINE_PIN
        }
        if (terminal.onlinePinCap && aid.onlinePinCap == 1) {
            enabled = enabled or EmvTerminalCapabilities.ONLINE_PIN
        }
        if (terminal.signatureCap && aid.signatureCap) {
            enabled = enabled or EmvTerminalCapabilities.SIGNATURE
        }
        if (terminal.offlineEncrPinCap && aid.offlineEncrPinCap) {
            enabled = enabled or EmvTerminalCapabilities.ENCIPHERED_OFFLINE_PIN
        }
        if (terminal.noCVMCap && aid.noCVMCap) {
            enabled = enabled or EmvTerminalCapabilities.NO_CVM
        }
        return EmvTerminalCapabilityProfile(
            aid = aid.AID,
            interfaceType = EmvCapabilityInterface.CONTACT,
            base9F33 = null,
            controlledCvmMask = EmvTerminalCapabilities.STANDARD_CVM_MASK,
            enabledCvmMask = enabled,
        )
    }

    private fun contactlessCapabilityProfile(
        aid: TMS_EmvCtlsConfig,
        terminal: TMS_Terminal,
    ): EmvTerminalCapabilityProfile {
        val base = EmvTerminalCapabilities.parse9F33(aid.terminalCapabilities)
        // Contactless does not support offline PIN. Controlling all standard CVM bits here
        // guarantees that raw values such as E0F8C8 cannot advertise either offline PIN method.
        val controlled = EmvTerminalCapabilities.STANDARD_CVM_MASK
        var enabled = 0

        if (terminal.onlinePinCap && aid.onlinePinCap == 1) {
            enabled = enabled or EmvTerminalCapabilities.ONLINE_PIN
        }
        if (terminal.signatureCap && aid.signatureCap) {
            enabled = enabled or EmvTerminalCapabilities.SIGNATURE
        }
        if (terminal.noCVMCap && aid.noCVMCap) {
            enabled = enabled or EmvTerminalCapabilities.NO_CVM
        }

        return EmvTerminalCapabilityProfile(
            aid = aid.AID,
            interfaceType = EmvCapabilityInterface.CONTACTLESS,
            base9F33 = base,
            controlledCvmMask = controlled,
            enabledCvmMask = enabled,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun logAidEntity(e: AidEntity) {
        when (e.aidEntryModeEnum) {
            AidEntryModeEnum.AID_ENTRY_CONTACT -> Log.d(
                TAG,
                "[CONTACT]     aid=${e.aid} appVerNum=${e.appVerNum} asi=${e.asi}" +
                    " tacDefault=${e.tacDefault} tacDenial=${e.tacDenial} tacOnline=${e.tacOnline}" +
                    " ddol=${e.getDdol()} floorLimit=${e.floorLimit} threshold=${e.threshold}" +
                    " maxTargetPct=${e.maxTargetPercent} targetPct=${e.targetPercent}" +
                    " onlinePinCap=${e.onlinePinCap}",
            )
            AidEntryModeEnum.AID_ENTRY_CONTACTLESS -> Log.d(
                TAG,
                "[CONTACTLESS]  aid=${e.aid} asi=${e.asi}" +
                    " tacDefault=${e.tacDefault} tacDenial=${e.tacDenial} tacOnline=${e.tacOnline}" +
                    " floorLimit=${e.floorLimit} ctlsFloorLimit=${e.contactlessFloorLimit}" +
                    " ctlsTransLimit=${e.contactlessTransLimit} ctlsCvmLimit=${e.contactlessCvmLimit}" +
                    " onlinePinCap=${e.onlinePinCap}",
            )
            else -> Log.d(TAG, "[OTHER mode=${e.aidEntryModeEnum}] aid=${e.aid}")
        }
    }

    /**
     * Extracts the active bytes from a zero-padded TMS hex field.
     * [lenHex] is a hex-encoded byte count ("0B" = 11 bytes = 22 hex chars).
     */
    private fun trimHexField(value: String, lenHex: String): String {
        val byteCount = lenHex.trim().toIntOrNull(16) ?: return value
        return value.take(byteCount * 2)
    }
}
