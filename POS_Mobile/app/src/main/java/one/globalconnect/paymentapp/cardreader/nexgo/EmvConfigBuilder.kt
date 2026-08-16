package one.globalconnect.paymentapp.cardreader.nexgo

import android.util.Log
import com.nexgo.oaf.apiv3.emv.AidEntity
import com.nexgo.oaf.apiv3.emv.AidEntryModeEnum
import one.globalconnect.tms.paymentapp.TMS_EmvContactConfig
import one.globalconnect.tms.paymentapp.TMS_EmvCtlsConfig

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
