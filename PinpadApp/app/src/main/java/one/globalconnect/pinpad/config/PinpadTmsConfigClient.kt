package one.globalconnect.pinpad.config

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import one.globalconnect.pinpad.logging.PinpadTraceLog

object PinpadTmsConfigClient {
    fun request(context: Context): Boolean {
        val intent = Intent(PinpadTmsConfigReceiver.ACTION_REQUEST_PARAMS).apply {
            putExtra(
                PinpadTmsConfigReceiver.EXTRA_APPLICATION_ID,
                PinpadTmsConfigReceiver.APPLICATION_ID,
            )
        }
        val targetPackage = findXtmsPackage(context, intent) ?: run {
            PinpadTraceLog.device("PINPAD_APP configuration request skipped: xTMSAgent not found")
            return false
        }
        intent.`package` = targetPackage
        context.sendBroadcast(intent)
        PinpadTraceLog.device("PINPAD_APP configuration requested from package=$targetPackage")
        return true
    }

    @Suppress("DEPRECATION")
    private fun findXtmsPackage(context: Context, probe: Intent): String? {
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryBroadcastReceivers(
                probe,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            context.packageManager.queryBroadcastReceivers(probe, 0)
        }
        return matches.asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { it == XTMS_PACKAGE_PREFIX || it.startsWith("$XTMS_PACKAGE_PREFIX.") }
    }

    private const val XTMS_PACKAGE_PREFIX = "one.globalconnect.xtmsagent"
}
