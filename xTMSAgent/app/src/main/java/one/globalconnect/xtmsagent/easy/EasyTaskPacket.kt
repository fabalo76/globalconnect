package one.globalconnect.xtmsagent.easy

import org.json.JSONObject

/**
 * Parsed representation of the MQTT easy-task notification packet.
 *
 * Received on topic: tms/terminal/{termId}/easy
 * JSON format: {"easyId":5,"f1":10,"f2":20,"f3":0,"f4":0,"para":true,"pst":0}
 *
 * Fields:
 *   easyId  — Dn_Easy.EasyID
 *   f1      — Dn_SysFileSetID  (0 = not set)
 *   f2      — Dn_AppID         (0 = not set)
 *   f3      — Dn_HeaderLogoID  (0 = not set)
 *   f4      — Dn_TrailerLogoID (0 = not set)
 *   para    — EnableParaDownload
 *   pst     — ParaStatus (0–5)
 */
data class EasyTaskPacket(
    val easyId: Int,
    val f1: Int,
    val f2: Int,
    val f3: Int,
    val f4: Int,
    val enablePara: Boolean,
    val paraStatus: Int
) {
    companion object {
        /**
         * Parses a UTF-8 JSON payload received on the easy topic.
         * Returns null on any parse error.
         */
        fun parse(payload: ByteArray): EasyTaskPacket? {
            return try {
                val json = JSONObject(String(payload, Charsets.UTF_8))
                EasyTaskPacket(
                    easyId     = json.getInt("easyId"),
                    f1         = json.optInt("f1", 0),
                    f2         = json.optInt("f2", 0),
                    f3         = json.optInt("f3", 0),
                    f4         = json.optInt("f4", 0),
                    enablePara = json.optBoolean("para", false),
                    paraStatus = json.optInt("pst", 0)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
