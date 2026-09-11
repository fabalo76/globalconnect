package one.globalconnect.xtmsagent.remote

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.nexgo.oaf.apiv3.SystemServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import one.globalconnect.xtmsagent.nexgo.NexgoSystemServiceInitializer

private const val TAG = "RCAccessibilitySetup"
private const val VERIFICATION_ATTEMPTS = 20
private const val VERIFICATION_DELAY_MS = 100L

data class RemoteControlAccessibilitySetupResult(
    val success: Boolean,
    val code: String,
)

/**
 * Enables the remote-control accessibility service through the NEXGO privileged
 * system service when the terminal firmware permits it.
 *
 * Android Device Owner APIs can allow-list accessibility packages, but cannot
 * enable an accessibility service. The NEXGO command is therefore an optional
 * terminal-specific provisioning path; callers must retain a manual Settings
 * fallback when it is unavailable or rejected.
 */
object RemoteControlAccessibilityProvisioner {

    suspend fun ensureEnabled(context: Context): RemoteControlAccessibilitySetupResult {
        val appContext = context.applicationContext
        if (RemoteControlAccessibilityService.isEnabled(appContext)) {
            return RemoteControlAccessibilitySetupResult(true, "already_enabled")
        }

        val initResult = try {
            NexgoSystemServiceInitializer.await(appContext)
        } catch (error: Exception) {
            Log.w(TAG, "NEXGO system service initialization failed", error)
            return RemoteControlAccessibilitySetupResult(false, "system_service_unavailable")
        }
        if (initResult != SystemServiceHelper.RETURN_SUCC) {
            return RemoteControlAccessibilitySetupResult(false, "system_service_rejected")
        }

        val target = ComponentName(
            appContext,
            RemoteControlAccessibilityService::class.java,
        ).flattenToString()
        val current = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val enabledServices = appendService(current, target)
        val commands = accessibilityEnableCommands(enabledServices)

        val commandsAccepted = withContext(Dispatchers.IO) {
            try {
                val systemManager = SystemServiceHelper.getInstance().getSystemManager()
                    ?: return@withContext false
                var allAccepted = true
                commands.forEach { command ->
                    allAccepted =
                        systemManager.executeRootCMD(command, "", "", "") && allAccepted
                }
                allAccepted
            } catch (error: Exception) {
                Log.w(TAG, "NEXGO rejected accessibility provisioning", error)
                false
            }
        }

        repeat(VERIFICATION_ATTEMPTS) {
            if (RemoteControlAccessibilityService.isEnabled(appContext)) {
                Log.i(TAG, "Remote Control accessibility enabled through NEXGO provisioning")
                return RemoteControlAccessibilitySetupResult(true, "enabled_by_nexgo")
            }
            delay(VERIFICATION_DELAY_MS)
        }

        return RemoteControlAccessibilitySetupResult(
            success = false,
            code = if (commandsAccepted) "enable_not_applied" else "enable_command_rejected",
        )
    }

    internal fun appendService(current: String?, target: String): String =
        buildList {
            current
                ?.split(':')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.let(::addAll)
            add(target)
        }
            .distinctBy { it.lowercase() }
            .joinToString(":")

    internal fun accessibilityEnableCommands(enabledServices: String): List<String> = listOf(
        "settings put secure enabled_accessibility_services ${shellSingleQuote(enabledServices)}",
        "settings put secure accessibility_enabled 1",
    )

    internal fun shellSingleQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
