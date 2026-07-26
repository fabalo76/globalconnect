package one.globalconnect.xtmsagent.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.nexgo.oaf.apiv3.SystemServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver
import one.globalconnect.xtmsagent.nexgo.NexgoSystemServiceInitializer

const val FACTORY_TMS_PACKAGE = "com.nexgo.xtms"

enum class FactoryTmsState {
    ENABLED,
    DISABLED,
    NOT_INSTALLED,
    DEVICE_OWNER_REQUIRED,
    ERROR,
}

data class FactoryTmsStatus(
    val state: FactoryTmsState,
    val code: String,
)

data class FactoryTmsPolicyResult(
    val success: Boolean,
    val status: FactoryTmsStatus,
    val changed: Boolean,
    val code: String,
)

internal interface FactoryTmsPolicyBackend {
    fun isInstalled(): Boolean
    fun isDeviceOwner(): Boolean
    fun isHidden(): Boolean
    fun isEnabled(): Boolean
    fun setHidden(hidden: Boolean): Boolean
}

object FactoryTmsManager {
    private const val TAG = "FactoryTmsPolicy"
    private const val PREFERENCES_NAME = "factory_tms_policy"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_ENABLED = "enabled"

    fun status(context: Context): FactoryTmsStatus =
        readStatus(AndroidFactoryTmsPolicyBackend(context.applicationContext))

    suspend fun setEnabled(context: Context, enabled: Boolean): FactoryTmsPolicyResult {
        val policyResult = applyEnabled(
            AndroidFactoryTmsPolicyBackend(context.applicationContext),
            enabled,
        )
        if (policyResult.success) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CONFIGURED, true)
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        }
        val result = if (policyResult.success) {
            applyRuntimeState(context.applicationContext, enabled, policyResult)
        } else {
            policyResult
        }
        logPolicyResult(result, enabled)
        return result
    }

    suspend fun reconcile(context: Context): FactoryTmsPolicyResult? {
        val preferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_CONFIGURED, false)) {
            Log.i(TAG, "No administrator preference saved; factory TMS remains unchanged")
            return null
        }

        val enabled = preferences.getBoolean(KEY_ENABLED, true)
        return setEnabled(context, enabled)
    }

    internal fun readStatus(backend: FactoryTmsPolicyBackend): FactoryTmsStatus {
        if (!backend.isInstalled()) {
            return FactoryTmsStatus(
                FactoryTmsState.NOT_INSTALLED,
                "factory_tms_not_installed",
            )
        }
        if (!backend.isDeviceOwner()) {
            return FactoryTmsStatus(
                FactoryTmsState.DEVICE_OWNER_REQUIRED,
                "device_owner_required",
            )
        }

        return try {
            if (backend.isHidden() || !backend.isEnabled()) {
                FactoryTmsStatus(FactoryTmsState.DISABLED, "factory_tms_disabled")
            } else {
                FactoryTmsStatus(FactoryTmsState.ENABLED, "factory_tms_enabled")
            }
        } catch (exception: RuntimeException) {
            FactoryTmsStatus(
                FactoryTmsState.ERROR,
                exception.javaClass.simpleName,
            )
        }
    }

    internal fun applyEnabled(
        backend: FactoryTmsPolicyBackend,
        enabled: Boolean,
    ): FactoryTmsPolicyResult {
        val initialStatus = readStatus(backend)
        if (
            initialStatus.state == FactoryTmsState.NOT_INSTALLED ||
            initialStatus.state == FactoryTmsState.DEVICE_OWNER_REQUIRED ||
            initialStatus.state == FactoryTmsState.ERROR
        ) {
            return FactoryTmsPolicyResult(
                success = false,
                status = initialStatus,
                changed = false,
                code = initialStatus.code,
            )
        }

        val desiredHidden = !enabled
        val initiallyHidden = try {
            backend.isHidden()
        } catch (exception: RuntimeException) {
            return FactoryTmsPolicyResult(
                success = false,
                status = FactoryTmsStatus(
                    FactoryTmsState.ERROR,
                    exception.javaClass.simpleName,
                ),
                changed = false,
                code = exception.javaClass.simpleName,
            )
        }
        if (initiallyHidden == desiredHidden) {
            return FactoryTmsPolicyResult(
                success = true,
                status = initialStatus,
                changed = false,
                code = "already_applied",
            )
        }

        return try {
            val accepted = backend.setHidden(desiredHidden)
            val verified = accepted && backend.isHidden() == desiredHidden
            if (verified) {
                FactoryTmsPolicyResult(
                    success = true,
                    status = FactoryTmsStatus(
                        if (enabled) FactoryTmsState.ENABLED else FactoryTmsState.DISABLED,
                        "factory_tms_${if (enabled) "enabled" else "disabled"}",
                    ),
                    changed = true,
                    code = "policy_applied",
                )
            } else {
                FactoryTmsPolicyResult(
                    success = false,
                    status = FactoryTmsStatus(
                        FactoryTmsState.ERROR,
                        "policy_verification_failed",
                    ),
                    changed = false,
                    code = "policy_verification_failed",
                )
            }
        } catch (exception: RuntimeException) {
            FactoryTmsPolicyResult(
                success = false,
                status = FactoryTmsStatus(
                    FactoryTmsState.ERROR,
                    exception.javaClass.simpleName,
                ),
                changed = false,
                code = exception.javaClass.simpleName,
            )
        }
    }

    private fun logPolicyResult(result: FactoryTmsPolicyResult, enabled: Boolean) {
        val message =
            "Factory TMS desiredEnabled=$enabled success=${result.success} " +
                "changed=${result.changed} state=${result.status.state} code=${result.code}"
        if (result.success) {
            Log.i(TAG, message)
        } else {
            Log.w(TAG, message)
        }
    }

    private suspend fun applyRuntimeState(
        context: Context,
        enabled: Boolean,
        policyResult: FactoryTmsPolicyResult,
    ): FactoryTmsPolicyResult {
        val initialized = try {
            NexgoSystemServiceInitializer.await(context) == SystemServiceHelper.RETURN_SUCC
        } catch (_: Exception) {
            false
        }
        if (!initialized) {
            return policyResult.copy(
                success = false,
                code = if (enabled) {
                    "enabled_but_runtime_start_unavailable"
                } else {
                    "hidden_but_runtime_stop_unavailable"
                },
            )
        }

        val command = if (enabled) {
            "am startservice -n $FACTORY_TMS_PACKAGE/.XTMSService"
        } else {
            "am force-stop $FACTORY_TMS_PACKAGE"
        }
        val commandAccepted = withContext(Dispatchers.IO) {
            try {
                val systemManager =
                    SystemServiceHelper.getInstance().getSystemManager() ?: return@withContext false
                systemManager.setAppEnabled(
                    FACTORY_TMS_PACKAGE,
                    if (enabled) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    },
                )
                systemManager.executeCmd(command)
            } catch (_: Exception) {
                false
            }
        }
        val finalStatus = status(context)
        val stateVerified =
            finalStatus.state ==
                (if (enabled) FactoryTmsState.ENABLED else FactoryTmsState.DISABLED)
        return if (stateVerified) {
            policyResult.copy(
                status = finalStatus,
                code = if (commandAccepted) {
                    policyResult.code
                } else {
                    "policy_applied_runtime_command_unconfirmed"
                },
            )
        } else {
            policyResult.copy(
                success = false,
                status = finalStatus,
                code = if (enabled) {
                    "enabled_but_runtime_start_failed"
                } else {
                    "hidden_but_runtime_stop_failed"
                },
            )
        }
    }
}

private class AndroidFactoryTmsPolicyBackend(
    private val context: Context,
) : FactoryTmsPolicyBackend {
    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = TmsDeviceAdminReceiver.componentName(context)

    override fun isInstalled(): Boolean =
        try {
            val flags =
                PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
            context.packageManager.getApplicationInfo(FACTORY_TMS_PACKAGE, flags)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    override fun isDeviceOwner(): Boolean =
        devicePolicyManager.isDeviceOwnerApp(context.packageName)

    override fun isHidden(): Boolean =
        devicePolicyManager.isApplicationHidden(admin, FACTORY_TMS_PACKAGE)

    override fun isEnabled(): Boolean {
        val flags =
            PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
        return context.packageManager
            .getApplicationInfo(FACTORY_TMS_PACKAGE, flags)
            .enabled
    }

    override fun setHidden(hidden: Boolean): Boolean =
        devicePolicyManager.setApplicationHidden(admin, FACTORY_TMS_PACKAGE, hidden)
}
