package one.globalconnect.xtmsagent.recovery

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

internal interface OwnerRemovalBackend {
    fun isOwner(): Boolean
    fun persistOptOut()
    fun cleanup()
    fun clearOwner()
}

object RecoveryOwnerRemoval {
    private const val PREFS = "recovery_owner_control"
    private const val OPT_OUT = "disable_auto_provisioning"

    fun autoProvisioningDisabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OPT_OUT, false)

    @Suppress("DEPRECATION")
    fun remove(context: Context): String {
        val app = context.applicationContext
        val policy = app.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(app.packageName, "one.globalconnect.xtmsagent.TmsDeviceAdminReceiver")
        fun log(text: String) = RecoveryApplication.record(app, "removeDeviceOwner $text")
        fun attempt(label: String, action: () -> Unit) {
            runCatching(action).onSuccess { log("$label completed") }
                .onFailure { log("$label failed: ${it.stackTraceToString()}") }
        }
        return try {
            log("before owner=${policy.isDeviceOwnerApp(app.packageName)} admin=${policy.isAdminActive(admin)}")
            val removed = perform(object : OwnerRemovalBackend {
                override fun isOwner() = policy.isDeviceOwnerApp(app.packageName)
                override fun persistOptOut() {
                    check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(OPT_OUT, true).commit()) { "Could not save the automatic-enrollment opt-out" }
                    log("automaticEnrollment=disabled")
                }
                override fun cleanup() {
                    attempt("clearPersistentHome") { policy.clearPackagePersistentPreferredActivities(admin, app.packageName) }
                    attempt("clearOwnRestrictions") {
                        val restrictions = policy.getUserRestrictions(admin)
                        restrictions.keySet().filter { restrictions.getBoolean(it) }.forEach {
                            policy.clearUserRestriction(admin, it)
                        }
                    }
                }
                override fun clearOwner() {
                    log("invoke clearDeviceOwnerApp")
                    policy.clearDeviceOwnerApp(app.packageName)
                }
            })
            if (removed) {
                attempt("clearHomeDefault") { app.packageManager.clearPackagePreferredActivities(app.packageName) }
                if (policy.isAdminActive(admin)) attempt("removeRemainingAdmin") { policy.removeActiveAdmin(admin) }
            }
            val ownerAfter = policy.isDeviceOwnerApp(app.packageName)
            val adminAfter = policy.isAdminActive(admin)
            log("after owner=$ownerAfter admin=$adminAfter")
            if (ownerAfter) "Device ownership is still active. Removal was not applied; send log_today.txt."
            else "Device ownership removed. Automatic enrollment is disabled.\nDevice admin active: $adminAfter\nUse Choose Home app to select the factory launcher."
        } catch (error: Exception) {
            log("failed: ${error.stackTraceToString()}")
            "Could not confirm removal: ${error.message ?: error.javaClass.simpleName}. Send log_today.txt."
        }
    }

    internal fun perform(backend: OwnerRemovalBackend): Boolean {
        // Persist before the owner call, which may cause Android to restart the app.
        backend.persistOptOut()
        if (!backend.isOwner()) return true
        backend.cleanup()
        backend.clearOwner()
        return !backend.isOwner()
    }
}
