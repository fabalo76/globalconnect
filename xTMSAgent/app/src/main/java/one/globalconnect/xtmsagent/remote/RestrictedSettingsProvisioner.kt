package one.globalconnect.xtmsagent.remote

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Process
import one.globalconnect.xtmsagent.diagnostics.NexgoDiagnosticsManager
import one.globalconnect.xtmsagent.nexgo.NexgoProfileResolver

/** Android 13 owner-only trial. Hidden API access may be rejected by OEM firmware. */
object RestrictedSettingsProvisioner {
    private const val OP = "android:access_restricted_settings"

    fun state(context: Context): String {
        if (Build.VERSION.SDK_INT < 33) return "not_applicable"
        return runCatching { modeName(readMode(context)) }
            .getOrElse { "unavailable:${it.javaClass.simpleName}" }
    }

    private fun readMode(context: Context): Int =
        context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName)

    private fun modeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allowed"
        AppOpsManager.MODE_IGNORED -> "ignored"
        AppOpsManager.MODE_ERRORED -> "errored"
        AppOpsManager.MODE_DEFAULT -> "default"
        else -> "mode_$mode"
    }

    fun ensureAllowed(context: Context) {
        val model = NexgoProfileResolver.resolve(null, Build.MODEL, null).modelKey
        val owner = context.getSystemService(DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)
        NexgoDiagnosticsManager.record(context,
            "restrictedSettings.before state=${state(context)} owner=$owner uid=${Process.myUid()}")
        if (!eligible(model, Build.VERSION.SDK_INT, owner)) {
            NexgoDiagnosticsManager.record(context, "restrictedSettings.result=outside_trial_scope")
            return
        }
        try {
            val manager = context.getSystemService(AppOpsManager::class.java)
            // Resolve only the framework's existing method; do not relax hidden API enforcement.
            val stringSetter = runCatching {
                AppOpsManager::class.java.getMethod("setMode", String::class.java,
                    Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
            }.onFailure {
                NexgoDiagnosticsManager.recordException(context, "restrictedSettings.stringMethodLookup", it)
            }.getOrNull()
            // Older compatibility-exposed overload: resolve the op number from the
            // running framework rather than assuming an OEM operation index.
            val numericSetter = if (stringSetter == null) runCatching {
                val operation = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
                    .invoke(null, OP) as Int
                AppOpsManager::class.java.getMethod("setMode", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType) to operation
            }.onFailure {
                NexgoDiagnosticsManager.recordException(context, "restrictedSettings.numericMethodLookup", it)
            }.getOrNull() else null
            NexgoDiagnosticsManager.record(context,
                "restrictedSettings.methodAvailable=${stringSetter != null || numericSetter != null} " +
                    "route=${if (stringSetter != null) "string" else if (numericSetter != null) "numeric" else "unavailable"} " +
                    "operation=$OP package=${context.packageName}")
            val result = approveIfBlocked(
                read = { readMode(context) },
                write = {
                    if (stringSetter != null) {
                        stringSetter.invoke(manager, OP, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
                    } else {
                        val (setter, operation) = checkNotNull(numericSetter) {
                            "AppOpsManager.setMode unavailable on this firmware"
                        }
                        setter.invoke(manager, operation, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
                    }
                },
            )
            NexgoDiagnosticsManager.record(context, "restrictedSettings.result=$result")
        } catch (error: Exception) {
            NexgoDiagnosticsManager.recordException(context, "restrictedSettings.approve", error)
            NexgoDiagnosticsManager.record(context, "restrictedSettings.result=failed manualApprovalRequired=true")
        } finally {
            NexgoDiagnosticsManager.record(context, "restrictedSettings.after state=${state(context)}")
        }
    }

    internal fun eligible(model: String, sdk: Int, owner: Boolean): Boolean =
        model == "N6ProLite" && sdk == 33 && owner

    internal fun approveIfBlocked(read: () -> Int, write: () -> Unit): String {
        val before = read()
        if (before == AppOpsManager.MODE_ALLOWED) return "already_allowed"
        if (before != AppOpsManager.MODE_IGNORED) return "unsupported_state_$before"
        write()
        return if (read() == AppOpsManager.MODE_ALLOWED) "approved_by_device_owner" else "approval_not_applied"
    }
}
