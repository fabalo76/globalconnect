package one.globalconnect.xtmsagent.mqtt.versions

import android.content.Context
import android.util.Log
import org.json.JSONObject

private const val TAG        = "AppInstallPendingStore"
private const val PREFS_NAME = "app_install_pending"

/**
 * Persists APK install tasks that were deferred because the target app was busy
 * (e.g. unsettled transactions).  Stored in SharedPreferences as JSON so the task
 * survives process restarts.
 *
 * One entry per [packageId] — a new store() call overwrites any previous entry.
 */
object AppInstallPendingStore {

    data class PendingInstallInfo(
        val fileId: Int,
        val packageId: String,
        val appName: String?,
        val serverVersion: String,
        val serverVersionCode: Int,
        val apkPath: String
    )

    fun store(context: Context, info: PendingInstallInfo) {
        val json = JSONObject().apply {
            put("fileId",            info.fileId)
            put("packageId",         info.packageId)
            put("appName",           info.appName ?: "")
            put("serverVersion",     info.serverVersion)
            put("serverVersionCode", info.serverVersionCode)
            put("apkPath",           info.apkPath)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(info.packageId, json.toString()).apply()
        Log.i(TAG, "Stored pending install: pkg=${info.packageId} ver=${info.serverVersion}")
    }

    fun get(context: Context, packageId: String): PendingInstallInfo? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(packageId, null) ?: return null
        return try {
            val j = JSONObject(raw)
            PendingInstallInfo(
                fileId            = j.getInt("fileId"),
                packageId         = j.getString("packageId"),
                appName           = j.optString("appName").ifBlank { null },
                serverVersion     = j.getString("serverVersion"),
                serverVersionCode = j.getInt("serverVersionCode"),
                apkPath           = j.getString("apkPath")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize pending install for $packageId: ${e.message}")
            null
        }
    }

    fun remove(context: Context, packageId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(packageId).apply()
        Log.i(TAG, "Cleared pending install for $packageId")
    }

    fun hasAny(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all.isNotEmpty()
}
