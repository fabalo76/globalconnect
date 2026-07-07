package one.globalconnect.xtmsagent.easy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata for a single file within an Easy Download task.
 * Returned by the EasyFileMeta.aspx server endpoint.
 */
data class EasyFileInfo(
    val fileId: Int,
    val fileName: String,
    val size: Long,
    /** 1=SysFileSet(params), 2=App(APK), 3=HeaderLogo, 4=TrailerLogo */
    val slot: Int
) {
    val isApk: Boolean get() = fileName.endsWith(".apk", ignoreCase = true)

    companion object {
        /** Parses the JSON response from EasyFileMeta.aspx. */
        fun parseList(json: JSONObject): List<EasyFileInfo> {
            val arr: JSONArray = json.optJSONArray("files") ?: return emptyList()
            val result = mutableListOf<EasyFileInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result += EasyFileInfo(
                    fileId   = obj.getInt("fileId"),
                    fileName = obj.getString("fileName"),
                    size     = obj.optLong("size", 0L),
                    slot     = obj.getInt("slot")
                )
            }
            return result
        }
    }
}
