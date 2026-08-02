package one.globalconnect.xtmsagent.nexgo

import android.content.Context
import android.os.Build
import com.nexgo.oaf.apiv3.SystemServiceHelper
import com.nexgo.oaf.apiv3.platform.OnPlatformInitListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver

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
    private const val OWNER_VERIFICATION_ATTEMPTS = 20
    private const val OWNER_VERIFICATION_DELAY_MILLIS = 100L
    private val provisioningMutex = Mutex()

    suspend fun ensureDeviceOwner(context: Context): NexgoDeviceOwnerProvisionResult =
        provisioningMutex.withLock {
            ensureDeviceOwnerLocked(context)
        }

    private suspend fun ensureDeviceOwnerLocked(
        context: Context,
    ): NexgoDeviceOwnerProvisionResult {
        val appContext = context.applicationContext
        if (TmsDeviceAdminReceiver.isDeviceOwner(appContext)) {
            return NexgoDeviceOwnerProvisionResult(true, "already_device_owner")
        }

        val helper = SystemServiceHelper.getInstance()
        val profile = NexgoProfileResolver.resolve(
            modelProperty = null,
            buildModel = Build.MODEL,
            commandBaseProperty = runCatching {
                SystemServiceHelper.getCMDBASE().toString()
            }.getOrNull(),
        )
        val deviceOwnerCommand = deviceOwnerCommand(profile.modelKey)
        if (!profile.commandProfileVerified ||
            deviceOwnerCommand == null ||
            profile.capabilities[NexgoCapability.DEVICE_OWNER] != NexgoCapabilityState.SUPPORTED
        ) {
            return NexgoDeviceOwnerProvisionResult(false, "device_owner_model_unsupported")
        }

        val initResult = try {
            NexgoSystemServiceInitializer.await(appContext)
        } catch (_: Exception) {
            return NexgoDeviceOwnerProvisionResult(false, "system_service_init_failed")
        }
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
        val sdkResult = withContext(Dispatchers.IO) {
            helper.executeGeneralMethod(
                deviceOwnerCommand,
                payload,
                ByteArray(0),
                ByteArray(0),
            )
        }
        if (sdkResult != SystemServiceHelper.RETURN_SUCC) {
            return NexgoDeviceOwnerProvisionResult(false, "device_owner_rejected", sdkResult)
        }

        repeat(OWNER_VERIFICATION_ATTEMPTS) {
            if (TmsDeviceAdminReceiver.isDeviceOwner(appContext)) {
                return NexgoDeviceOwnerProvisionResult(true, "device_owner_provisioned", sdkResult)
            }
            delay(OWNER_VERIFICATION_DELAY_MILLIS)
        }
        return NexgoDeviceOwnerProvisionResult(false, "device_owner_not_applied", sdkResult)
    }

    internal fun deviceOwnerCommand(modelKey: String): Int? = when (modelKey) {
        "CT20P" -> CT20P_DEVICE_OWNER_COMMAND
        "N6S", "N82" -> COMMAND_BASE_80_DEVICE_OWNER_COMMAND
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
