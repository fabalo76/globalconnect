package one.globalconnect.xtmsagent.download

import android.util.Base64
import android.util.Log
import one.globalconnect.xtmsagent.TMSFunc
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "TmsFileDownloader"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS    = 120_000

/**
 * Downloads a single TMS file from the HTTPS endpoint (TerminalDownload.aspx).
 * Called by [one.globalconnect.xtmsagent.mqtt.TmsHouseKeepingManager] to download logos, system
 * file sets, and program APKs described in the hkresp pending-task list.
 *
 * Auth: Base64url(HMAC-SHA256(key=UTF8(download_secret), data=UTF8(termId)))
 *
 * POST body:
 *   { "tid":"TERM001", "key":"<hmac>",
 *     "fst":"P",
 *     "fsn":"Android TMS 2.0",   // FileSetName (szVersion from HK response)
 *     "fsv":"20240115143000",    // FileSet VersionDateTime YYYYMMDDHHMMSS
 *     "fn":"com.uic.app.apk",   // FileName
 *     "fv":"2.0.1" }            // File VersionNo
 */
object TmsFileDownloader {

    /**
     * Downloads a file and writes it to [savePath].
     *
     * All config (URL, secret, termId) is read from [TMSFunc.tmsCfg] which is
     * populated from Launcher_Config.JSON via [TMSFunc.ChkParamChange].
     *
     * @param fst      FileSetType: "P"=TermApp / "S"=SysFileSet / "H"=HeaderLogo / "T"=TrailerLogo
     * @param fsn      FileSetName as received in the HouseKeeping response
     * @param fsv      FileSet VersionDateTime in YYYYMMDDHHMMSS format
     * @param fn       FileName as received in the HouseKeeping response
     * @param fv       File VersionNo as received in the HouseKeeping response
     * @param savePath Absolute local path to save the downloaded file
     * @return 0 on success, non-zero on failure
     */
    @JvmStatic
    fun downloadFile(
        fst: String, fsn: String, fsv: String,
        fn:  String, fv:  String, savePath: String
    ): Int {
        val cfg    = TMSFunc.tmsCfg
        val termId = cfg.sn
        val secret = cfg.download_secret
        val url    = "${cfg.webScheme}://${cfg.server_addr}:${cfg.web_port}/Download/TerminalDownload.aspx"

        if (termId.isBlank() || secret.isBlank() || cfg.server_addr.isBlank()) {
            Log.e(TAG, "Missing config — tid='$termId' secret_set=${secret.isNotBlank()} server='${cfg.server_addr}'")
            return -1
        }

        return try {
            val apiKey = computeHmacKey(termId, secret)
            val body   = buildJson(termId, apiKey, fst, fsn, fsv, fn, fv)

            Log.i(TAG, "HTTPS download: fst=$fst fn=$fn fv=$fv fsn='$fsn' → $savePath")

            // Ensure parent directory exists
            File(savePath).parentFile?.mkdirs()

            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.doInput  = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout    = READ_TIMEOUT_MS

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    val errMsg = conn.errorStream?.bufferedReader()?.readText() ?: "(no body)"
                    Log.e(TAG, "Server HTTP $status for '$fn': $errMsg")
                    return status
                }

                FileOutputStream(savePath).use { out ->
                    conn.inputStream.copyTo(out)
                }

                val bytes = File(savePath).length()
                Log.i(TAG, "Downloaded '$fn' — $bytes bytes → $savePath")
                0

            } finally {
                conn.disconnect()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for '$fn': ${e.message}", e)
            -2
        }
    }

    /**
     * Downloads a parameter file from <c>ParamDownload.aspx</c> and writes it to [savePath].
     *
     * The server resolves the file from the terminal's currently published profile version
     * ({PublishPath}/{ProfileID}/{CurProfileVersion}/{TermID}.{ft}.gz).
     *
     * @param ft       File type: "dat" (binary parameter file) or "json" (JSON parameter file)
     * @param savePath Absolute local path to save the downloaded .gz file
     * @return 0 on success, non-zero on failure
     */
    @JvmStatic
    fun downloadParamFile(ft: String, savePath: String): Int {
        val cfg    = TMSFunc.tmsCfg
        val termId = cfg.sn
        val secret = cfg.download_secret
        val url    = "${cfg.webScheme}://${cfg.server_addr}:${cfg.web_port}/Download/ParamDownload.aspx"

        if (termId.isBlank() || secret.isBlank() || cfg.server_addr.isBlank()) {
            Log.e(TAG, "Missing config for param download — tid='$termId' server='${cfg.server_addr}'")
            return -1
        }

        return try {
            val apiKey = computeHmacKey(termId, secret)
            val body   = """{"tid":"${termId.jsonEscape()}","key":"${apiKey.jsonEscape()}","ft":"${ft.jsonEscape()}"}"""

            Log.i(TAG, "Param download: ft=$ft → $savePath")
            File(savePath).parentFile?.mkdirs()

            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput      = true
                conn.doInput       = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout    = READ_TIMEOUT_MS

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    val errMsg = conn.errorStream?.bufferedReader()?.readText() ?: "(no body)"
                    Log.e(TAG, "Param download HTTP $status for ft=$ft: $errMsg")
                    return status
                }

                FileOutputStream(savePath).use { out -> conn.inputStream.copyTo(out) }

                val bytes = File(savePath).length()
                Log.i(TAG, "Param file downloaded — $bytes bytes → $savePath")
                0

            } finally {
                conn.disconnect()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Param download failed for ft=$ft: ${e.message}", e)
            -2
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun computeHmacKey(termId: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(termId.toByteArray(Charsets.UTF_8))
        // Base64url — no padding, URL-safe alphabet — matches server VerifyApiKey()
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Builds the JSON POST body without an external library.
     * Gson is available (already a dependency) but not needed for this small fixed structure.
     */
    private fun buildJson(
        tid: String, key: String,
        fst: String, fsn: String, fsv: String,
        fn:  String, fv:  String
    ): String = buildString {
        append("{")
        appendField("tid", tid);  append(",")
        appendField("key", key);  append(",")
        appendField("fst", fst)
        if (fsn.isNotBlank()) { append(","); appendField("fsn", fsn) }
        if (fsv.isNotBlank()) { append(","); appendField("fsv", fsv) }
        append(","); appendField("fn", fn)
        if (fv.isNotBlank())  { append(","); appendField("fv",  fv)  }
        append("}")
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        append('"'); append(key.jsonEscape()); append("\":\"")
        append(value.jsonEscape()); append('"')
    }

    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
}
