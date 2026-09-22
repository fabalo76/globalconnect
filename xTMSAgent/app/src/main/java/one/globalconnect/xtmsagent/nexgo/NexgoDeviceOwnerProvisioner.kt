package one.globalconnect.xtmsagent.nexgo

import android.content.Context
import android.os.Build
import com.nexgo.oaf.apiv3.SystemServiceHelper
import com.nexgo.oaf.apiv3.platform.OnPlatformInitListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver
import one.globalconnect.xtmsagent.diagnostics.NexgoDiagnosticsManager

data class NexgoDeviceOwnerProvisionResult(
    val success: Boolean,
    val code: String,
    val sdkResultCode: Int? = null,
)

object NexgoSystemServiceInitializer {
    private const val SERVICE_INIT_TIMEOUT_MILLIS = 15_000L
    private val initMutex = Mutex()
    @Volatile
    private var initResult: CompletableDeferred<Int>? = null

    suspend fun await(context: Context): Int {
        val pending = initMutex.withLock {
            initResult ?: CompletableDeferred<Int>().also { deferred ->
                initResult = deferred
                try {
                    SystemServiceHelper.getInstance().init(
                        context.applicationContext,
                        object : OnPlatformInitListener {
                            override fun onPlatformInitResult(resultCode: Int) {
                                runCatching {
                                    NexgoDiagnosticsManager.record(context, "systemService initResult=$resultCode")
                                }
                                deferred.complete(resultCode)
                            }
                        },
                    )
                } catch (exception: Exception) {
                    deferred.completeExceptionally(exception)
                }
            }
        }
        return withTimeout(SERVICE_INIT_TIMEOUT_MILLIS) { pending.await() }
    }
}

object NexgoDeviceOwnerProvisioner {
    private const val CT20P_DEVICE_OWNER_COMMAND = 702_108_171
    private const val COMMAND_BASE_80_DEVICE_OWNER_COMMAND = 802_108_171
    private const val COMMAND_BASE_90_DEVICE_OWNER_COMMAND = 902_108_171
    private const val OWNER_VERIFICATION_ATTEMPTS = 20
    private const val OWNER_VERIFICATION_DELAY_MILLIS = 100L
    private val provisioningMutex = Mutex()

    suspend fun ensureDeviceOwner(context: Context): NexgoDeviceOwnerProvisionResult =
        provisioningMutex.withLock {
            NexgoDiagnosticsManager.recordSetupState(context, "deviceOwner.before")
            try {
                ensureDeviceOwnerLocked(context).also {
                    NexgoDiagnosticsManager.record(context,
                        "deviceOwner.result success=${it.success} code=${it.code} sdkResult=${it.sdkResultCode}")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NexgoDiagnosticsManager.recordException(context, "deviceOwner", error)
                NexgoDeviceOwnerProvisionResult(false, "device_owner_exception")
            } finally {
                NexgoDiagnosticsManager.recordSetupState(context, "deviceOwner.after")
            }
        }

    private suspend fun ensureDeviceOwnerLocked(
        context: Context,
    ): NexgoDeviceOwnerProvisionResult {
        val appContext = context.applicationContext
        if (TmsDeviceAdminReceiver.isDeviceOwner(appContext)) {
            return NexgoDeviceOwnerProvisionResult(true, "already_device_owner")
        }
        if (one.globalconnect.xtmsagent.recovery.RecoveryOwnerRemoval.autoProvisioningDisabled(appContext)) {
            return NexgoDeviceOwnerProvisionResult(false, "automatic_enrollment_disabled_by_operator")
        }

        val helper = SystemServiceHelper.getInstance()
        val sdkBase = runCatching { SystemServiceHelper.getCMDBASE() }.getOrNull()
        val profile = NexgoProfileResolver.resolve(
            modelProperty = null,
            buildModel = Build.MODEL,
            commandBaseProperty = sdkBase?.toString(),
        )
        val firmwareBase = AndroidSystemProperties.get("ro.xgd.pss.basecmd")?.trim()?.toIntOrNull()
        val isCandidate = profile.modelKey == "N6ProLite"
        val deviceOwnerCommand = if (isCandidate) {
            candidateOwnerCommand(profile.modelKey, firmwareBase, sdkBase)
        } else deviceOwnerCommand(profile.modelKey)
        NexgoDiagnosticsManager.record(appContext,
            "deviceOwner.selection model=${profile.modelKey} firmwareBase=$firmwareBase sdkBase=$sdkBase " +
                "candidate=$isCandidate command=$deviceOwnerCommand")
        if ((!isCandidate && !profile.commandProfileVerified) ||
            deviceOwnerCommand == null ||
            (!isCandidate && profile.capabilities[NexgoCapability.DEVICE_OWNER] != NexgoCapabilityState.SUPPORTED)
        ) {
            return NexgoDeviceOwnerProvisionResult(false, "device_owner_model_unsupported")
        }

        val initResult = try {
            NexgoSystemServiceInitializer.await(appContext)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NexgoDiagnosticsManager.recordException(appContext, "deviceOwner.init", error)
            return NexgoDeviceOwnerProvisionResult(false, "system_service_init_failed")
        }
        NexgoDiagnosticsManager.record(appContext, "deviceOwner.init result=$initResult")
        if (initResult != SystemServiceHelper.RETURN_SUCC) {
            return NexgoDeviceOwnerProvisionResult(
                false,
                "system_service_rejected",
                initResult,
            )
        }

        val payload = encodeDeviceOwnerParameters(
            appContext.packageName,
            TmsDeviceAdminReceiver::class.java.name,
        )
        NexgoDiagnosticsManager.record(appContext,
            "deviceOwner.invoke method=executeGeneralMethod command=$deviceOwnerCommand " +
                "package=${appContext.packageName} receiver=${TmsDeviceAdminReceiver::class.java.name} " +
                "inputBytes=${payload.size} outputBytes=0 stdoutStderr=not_exposed_by_vendor_api")
        val started = android.os.SystemClock.elapsedRealtime()
        val sdkResult = withContext(Dispatchers.IO) {
            helper.executeGeneralMethod(
                deviceOwnerCommand,
                payload,
                ByteArray(0),
                ByteArray(0),
            )
        }
        NexgoDiagnosticsManager.record(appContext,
            "deviceOwner.return command=$deviceOwnerCommand sdkResult=$sdkResult " +
                "elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")

        repeat(OWNER_VERIFICATION_ATTEMPTS) {
            if (TmsDeviceAdminReceiver.isDeviceOwner(appContext)) {
                return NexgoDeviceOwnerProvisionResult(true, "device_owner_provisioned", sdkResult)
            }
            delay(OWNER_VERIFICATION_DELAY_MILLIS)
        }
        return NexgoDeviceOwnerProvisionResult(false,
            if (sdkResult == SystemServiceHelper.RETURN_SUCC) "device_owner_not_applied" else "device_owner_rejected",
            sdkResult)
    }

    // A bounded N96-derived trial, not a declaration of full N6ProLite compatibility.
    internal fun candidateOwnerCommand(modelKey: String, firmwareBase: Int?, sdkBase: Int?): Int? =
        if (modelKey == "N6ProLite" && firmwareBase == 90_000_000 && sdkBase == 90_000_000)
            COMMAND_BASE_90_DEVICE_OWNER_COMMAND else null

    internal fun deviceOwnerCommand(modelKey: String): Int? = when (modelKey) {
        "CT20", "CT20P" -> CT20P_DEVICE_OWNER_COMMAND
        "N6S", "N82" -> COMMAND_BASE_80_DEVICE_OWNER_COMMAND
        "N96" -> COMMAND_BASE_90_DEVICE_OWNER_COMMAND
        else -> null
    }

    internal fun encodeDeviceOwnerParameters(
        packageName: String,
        receiverClass: String,
    ): ByteArray {
        val packageBytes = packageName.toByteArray(Charsets.UTF_8)
        val receiverBytes = receiverClass.toByteArray(Charsets.UTF_8)
        require(packageBytes.size in 1..255)
        require(receiverBytes.size in 1..255)
        return byteArrayOf(2, packageBytes.size.toByte()) +
            packageBytes +
            byteArrayOf(receiverBytes.size.toByte()) +
            receiverBytes
    }

}
