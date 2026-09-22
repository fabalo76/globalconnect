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
import one.globalconnect.xtmsagent.diagnostics.NexgoDiagnosticsManager
import one.globalconnect.xtmsagent.nexgo.NexgoProfileResolver
import kotlinx.coroutines.CancellationException

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
        NexgoDiagnosticsManager.recordSetupState(appContext, "accessibility.before")
        RestrictedSettingsProvisioner.ensureAllowed(appContext)
        if (isConfigured(appContext)) {
            return RemoteControlAccessibilitySetupResult(true, "already_enabled")
        }

        val initResult = try {
            NexgoSystemServiceInitializer.await(appContext)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NexgoDiagnosticsManager.recordException(appContext, "accessibility.init", error)
            Log.w(TAG, "NEXGO system service initialization failed", error)
            return RemoteControlAccessibilitySetupResult(false, "system_service_unavailable")
        }
        NexgoDiagnosticsManager.record(appContext, "accessibility.init result=$initResult")
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
        val isN6ProLite = NexgoProfileResolver.resolve(null, android.os.Build.MODEL, null).modelKey == "N6ProLite"
        val commands = accessibilityEnableCommands(enabledServices, rawArgument = isN6ProLite)

        val commandsAccepted = withContext(Dispatchers.IO) {
            try {
                val systemManager = SystemServiceHelper.getInstance().getSystemManager()
                    ?: return@withContext false
                var allAccepted = true
                commands.forEachIndexed { index, command ->
                    NexgoDiagnosticsManager.record(appContext,
                        "accessibility.invoke index=$index command=$command stdoutStderr=not_exposed_by_vendor_api")
                    var accepted = if (isN6ProLite) false else runCatching {
                        systemManager.executeRootCMD(command, "", "", "")
                    }.onFailure {
                        NexgoDiagnosticsManager.recordException(appContext, "accessibility.root index=$index", it)
                    }.getOrDefault(false)
                    if (!isN6ProLite) NexgoDiagnosticsManager.record(appContext,
                        "accessibility command=$index method=executeRootCMD accepted=$accepted")
                    if (!accepted) {
                        accepted = runCatching { systemManager.executeCmd(command) }
                            .onFailure {
                                NexgoDiagnosticsManager.recordException(appContext, "accessibility.executeCmd index=$index", it)
                            }.getOrDefault(false)
                        NexgoDiagnosticsManager.record(appContext,
                            "accessibility command=$index method=executeCmd accepted=$accepted")
                    }
                    allAccepted = accepted && allAccepted
                }
                allAccepted
            } catch (error: Exception) {
                NexgoDiagnosticsManager.recordException(appContext, "accessibility.commands", error)
                Log.w(TAG, "NEXGO rejected accessibility provisioning", error)
                false
            }
        }

        repeat(VERIFICATION_ATTEMPTS) {
            if (isConfigured(appContext)) {
                NexgoDiagnosticsManager.recordSetupState(appContext, "accessibility.after")
                Log.i(TAG, "Remote Control accessibility enabled through NEXGO provisioning")
                return RemoteControlAccessibilitySetupResult(true, "enabled_by_nexgo")
            }
            delay(VERIFICATION_DELAY_MS)
        }

        NexgoDiagnosticsManager.recordSetupState(appContext, "accessibility.after")
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

    internal fun accessibilityEnableCommands(enabledServices: String, rawArgument: Boolean = false): List<String> {
        // N6ProLite may dispatch via Runtime.exec(String), which preserves literal shell quotes.
        // Restrict raw values to Android component-list characters, safe for either dispatcher.
        val value = if (rawArgument) {
            require(enabledServices.matches(Regex("[A-Za-z0-9_.$/:]+"))) { "Invalid accessibility component list" }
            enabledServices
        } else shellSingleQuote(enabledServices)
        // The exported N6ProLite PSS redirects strings beginning "settings put" to
        // xgddata and discards its status. An absolute path uses PSS Runtime.exec instead.
        val prefix = if (rawArgument) "/system/bin/settings --user 0 put" else "settings put"
        return listOf("$prefix secure enabled_accessibility_services $value",
            "$prefix secure accessibility_enabled 1")
    }

    private fun isConfigured(context: Context): Boolean =
        RemoteControlAccessibilityService.isEnabled(context) &&
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    internal fun shellSingleQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
