package one.globalconnect.xtmsagent.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

private const val TAG = "PaymentAppAutoLaunch"
private const val ACTION_PAY_APP = "android.intent.action.PAY_APP"
private const val PREFS_NAME = "payment_app_auto_launch"
private const val KEY_PENDING_AFTER_BOOT = "pending_after_boot"
private const val KEY_PENDING_BOOT_COUNT = "pending_boot_count"
private const val KEY_LAST_LAUNCHED_BOOT_COUNT = "last_launched_boot_count"

/**
 * Defers payment-app startup until xTMSAgent's HOME activity is in the foreground.
 *
 * Android blocks an application from opening an Activity directly from a
 * BOOT_COMPLETED receiver. xTMSAgent is the device launcher, so it records the boot
 * event and consumes it once its HOME activity reaches RESUMED state.
 */
object PaymentAppAutoLauncher {
    fun markPendingAfterBoot(context: Context) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bootCount = currentBootCount(context)
        if (!shouldQueuePaymentLaunch(
                bootCount = bootCount,
                pending = preferences.getBoolean(KEY_PENDING_AFTER_BOOT, false),
                pendingBootCount = preferences.getInt(KEY_PENDING_BOOT_COUNT, -1),
                lastLaunchedBootCount = preferences.getInt(KEY_LAST_LAUNCHED_BOOT_COUNT, -1),
            )
        ) {
            Log.i(TAG, "Payment application launch already handled for boot $bootCount")
            return
        }

        preferences
            .edit()
            .putBoolean(KEY_PENDING_AFTER_BOOT, true)
            .putInt(KEY_PENDING_BOOT_COUNT, bootCount)
            .apply()
        Log.i(TAG, "Payment application launch queued for boot $bootCount")
    }

    fun launchIfPending(activity: Activity): Boolean {
        val preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_PENDING_AFTER_BOOT, false)) return false

        val resolveInfos = queryPaymentActivities(activity)
        val candidatePackages = resolveInfos
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
        val paymentPackage = selectPaymentPackage(activity.packageName, candidatePackages)
        if (paymentPackage == null) {
            Log.e(TAG, "Unable to select payment application from $candidatePackages")
            return false
        }

        val activityInfo = resolveInfos
            .firstOrNull { it.activityInfo?.packageName == paymentPackage }
            ?.activityInfo
        if (activityInfo == null) {
            Log.e(TAG, "No PAY_APP activity found for $paymentPackage")
            return false
        }

        val launchIntent = Intent(ACTION_PAY_APP).apply {
            setClassName(paymentPackage, activityInfo.name)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        return try {
            activity.startActivity(launchIntent)
            val pendingBootCount = preferences.getInt(
                KEY_PENDING_BOOT_COUNT,
                currentBootCount(activity),
            )
            preferences.edit()
                .putBoolean(KEY_PENDING_AFTER_BOOT, false)
                .putInt(KEY_LAST_LAUNCHED_BOOT_COUNT, pendingBootCount)
                .apply()
            Log.i(TAG, "Payment application started after boot: $paymentPackage")
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start payment application after boot", error)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun queryPaymentActivities(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                Intent(ACTION_PAY_APP).addCategory(Intent.CATEGORY_DEFAULT),
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            context.packageManager.queryIntentActivities(
                Intent(ACTION_PAY_APP).addCategory(Intent.CATEGORY_DEFAULT),
                0,
            )
        }

    private fun currentBootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}

internal fun shouldQueuePaymentLaunch(
    bootCount: Int,
    pending: Boolean,
    pendingBootCount: Int,
    lastLaunchedBootCount: Int,
): Boolean = bootCount < 0 ||
    (lastLaunchedBootCount != bootCount && (!pending || pendingBootCount != bootCount))

internal fun selectPaymentPackage(agentPackage: String, candidates: List<String>): String? {
    val matchingFlavorPackage = agentPackage.replace(
        "one.globalconnect.xtmsagent",
        "one.globalconnect.paymentapp",
    )
    return when {
        matchingFlavorPackage in candidates -> matchingFlavorPackage
        candidates.size == 1 -> candidates.single()
        else -> null
    }
}
