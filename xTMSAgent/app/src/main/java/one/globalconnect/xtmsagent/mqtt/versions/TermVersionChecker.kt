package one.globalconnect.xtmsagent.mqtt.versions

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

private const val TAG = "TermVersionChecker"

data class AppUpdateInfo(
    val fileId: Int,
    val packageId: String,
    val appName: String?,
    val serverVersion: String,
    val serverVersionCode: Int,
    val installedVersion: String?,
    val installedVersionCode: Long?
)

data class VersionCheckResult(
    val appsToUpdate: List<AppUpdateInfo>
) {
    val needsAppUpdate: Boolean get() = appsToUpdate.isNotEmpty()
}

object TermVersionChecker {

    fun check(context: Context, payload: ByteArray): VersionCheckResult {
        val json      = JSONObject(String(payload, Charsets.UTF_8))
        val progApps  = json.optJSONArray("progApps")

        if (progApps == null || progApps.length() == 0) {
            Log.d(TAG, "verinfo: no progApps assigned")
            return VersionCheckResult(emptyList())
        }

        val toUpdate = mutableListOf<AppUpdateInfo>()

        for (i in 0 until progApps.length()) {
            val app       = progApps.getJSONObject(i)
            val fileId        = app.optInt("id", 0)
            val packageId     = app.optString("pkg").trim()
            val appName       = app.optString("name").trim().ifBlank { null }
            val serverVerRaw  = app.optString("ver").trim()
            val serverVerCode = app.optInt("verCode", 0)

            if (fileId <= 0 || packageId.isBlank() || serverVerRaw.isBlank()) {
                Log.w(TAG, "verinfo: progApps[$i] missing id, pkg, or ver — skipping")
                continue
            }

            // Server may still send VersionNo as "name(code)" when verCode field is absent
            // (old server build) or when the DB value embeds the code in the string.
            // Parse the trailing (N) suffix on the client as a fallback.
            val verCodeSuffix = Regex("""\((\d+)\)$""")
            val suffixMatch   = verCodeSuffix.find(serverVerRaw)
            val effectiveVerCode = when {
                serverVerCode > 0  -> serverVerCode
                suffixMatch != null -> suffixMatch.groupValues[1].toIntOrNull() ?: 0
                else               -> 0
            }
            val effectiveVerName = when {
                serverVerCode > 0  -> serverVerRaw   // server already stripped the suffix
                suffixMatch != null -> serverVerRaw.substring(0, suffixMatch.range.first).trimEnd()
                else               -> serverVerRaw
            }

            val installedVer     = getInstalledVersion(context, packageId)
            val installedVerCode = getInstalledVersionCode(context, packageId)

            // Compare by versionCode when available — avoids name-format mismatches.
            val upToDate = if (effectiveVerCode > 0 && installedVerCode != null) {
                installedVerCode == effectiveVerCode.toLong()
            } else {
                installedVer != null && installedVer == effectiveVerName
            }

            if (!upToDate) {
                Log.i(TAG, "App update needed: pkg=$packageId " +
                    "installedVer=${installedVer ?: "(not installed)"} installedCode=$installedVerCode " +
                    "serverVer=$effectiveVerName serverCode=$effectiveVerCode")
                toUpdate.add(AppUpdateInfo(fileId, packageId, appName, effectiveVerName, effectiveVerCode, installedVer, installedVerCode))
            } else {
                Log.d(TAG, "App up to date: pkg=$packageId ver=$effectiveVerName verCode=$effectiveVerCode installed=$installedVerCode")
            }
        }

        return VersionCheckResult(toUpdate)
    }

    private fun getInstalledVersion(context: Context, packageName: String): String? =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun getInstalledVersionCode(context: Context, packageName: String): Long? =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).longVersionCode
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
