package one.globalconnect.xtmsagent.easy

import org.json.JSONObject

/**
 * Report published to the server when the terminal updates easy task status.
 *
 * Published to topic: tms/terminal/{termId}/easyack
 * JSON format: {"easyId":5,"st":2,"pst":1}   (pst is optional)
 *
 * Status values (st):
 *   1 = Created
 *   2 = Download Initiated
 *   3 = Ready To Install
 *   4 = Installed / Effective
 *   5 = Error / Unable to Install
 *
 * ParaStatus values (pst, only set when enablePara=true):
 *   0 = No parameter download
 *   1 = Ready to Download
 *   2 = Download Started
 *   3 = Ready To Apply
 *   4 = Applied
 *   5 = Error
 */
data class EasyAckReport(
    val easyId: Int,
    val status: Int,
    val paraStatus: Int? = null
) {
    /** Serialises to UTF-8 JSON bytes for MQTT publish. */
    fun toBytes(): ByteArray {
        val json = JSONObject()
        json.put("easyId", easyId)
        json.put("st", status)
        paraStatus?.let { json.put("pst", it) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }
}
