package one.globalconnect.xtmsagent.nexgo

import android.content.Context
import com.nexgo.oaf.apiv3.SystemServiceHelper
import com.nexgo.oaf.apiv3.platform.OnPlatformInitListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.globalconnect.xtmsagent.diagnostics.NexgoDiagnosticsManager
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NexgoAssetInstallResult(
    val success: Boolean,
    val code: String,
    val sdkResultCode: Int? = null,
)

object NexgoSystemAssetInstaller {
    private const val SERVICE_INIT_TIMEOUT_MILLIS = 15_000L
    private const val STAGING_DIRECTORY = "nexgo-system-assets"
    private val installMutex = Mutex()

    suspend fun installAnimation(
        context: Context,
        asset: NexgoSystemAsset,
        sourceFile: File,
        expectedSha256: String,
    ): NexgoAssetInstallResult = installAsset(context, asset, sourceFile, expectedSha256)

    suspend fun installPowerLogo(
        context: Context,
        sourceFile: File,
        expectedSha256: String,
    ): NexgoAssetInstallResult = installAsset(
        context = context,
        asset = NexgoSystemAsset.POWER_LOGO,
        sourceFile = sourceFile,
        expectedSha256 = expectedSha256,
    )

    private suspend fun installAsset(
        context: Context,
        asset: NexgoSystemAsset,
        sourceFile: File,
        expectedSha256: String,
    ): NexgoAssetInstallResult = installMutex.withLock {
        val appContext = context.applicationContext
        val profile = NexgoRuntimeInspector.inspect(appContext).profile
        val validation = withContext(Dispatchers.IO) {
            validateAsset(profile, asset, sourceFile, expectedSha256)
        }
        if (!validation.isValid) {
            return@withLock result(
                context = appContext,
                profile = profile,
                asset = asset,
                success = false,
                code = validation.code,
            )
        }

        val externalCache = appContext.externalCacheDir
            ?: return@withLock result(
                context = appContext,
                profile = profile,
                asset = asset,
                success = false,
                code = "external_cache_unavailable",
            )
        val stagingDirectory = File(externalCache, STAGING_DIRECTORY)
        val stagedFile = File(stagingDirectory, asset.fixedFileName(profile))

        try {
            withContext(Dispatchers.IO) {
                stagingDirectory.mkdirs()
                FileOutputStream(stagedFile).use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                    output.fd.sync()
                }
                stagedFile.setReadable(true, false)
            }

            val stagedValidation = withContext(Dispatchers.IO) {
                validateAsset(profile, asset, stagedFile, expectedSha256)
            }
            if (!stagedValidation.isValid) {
                return@withLock result(
                    context = appContext,
                    profile = profile,
                    asset = asset,
                    success = false,
                    code = "staged_${stagedValidation.code}",
                )
            }

            val initResult = try {
                awaitSystemService(appContext)
            } catch (_: TimeoutCancellationException) {
                return@withLock result(
                    context = appContext,
                    profile = profile,
                    asset = asset,
                    success = false,
                    code = "system_service_init_timeout",
                )
            } catch (_: Exception) {
                return@withLock result(
                    context = appContext,
                    profile = profile,
                    asset = asset,
                    success = false,
                    code = "system_service_init_failed",
                )
            }
            if (initResult != 0) {
                return@withLock result(
                    context = appContext,
                    profile = profile,
                    asset = asset,
                    success = false,
                    code = "system_service_rejected",
                    sdkResultCode = initResult,
                )
            }

            val systemManager = SystemServiceHelper.getInstance().getSystemManager()
                ?: return@withLock result(
                    context = appContext,
                    profile = profile,
                    asset = asset,
                    success = false,
                    code = "system_manager_unavailable",
                )
            val sdkResult = withContext(Dispatchers.IO) {
                systemManager.updateTms(stagedFile.absolutePath)
            }
            result(
                context = appContext,
                profile = profile,
                asset = asset,
                success = sdkResult == 0,
                code = if (sdkResult == 0) "installed" else "update_tms_rejected",
                sdkResultCode = sdkResult,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            result(
                context = appContext,
                profile = profile,
                asset = asset,
                success = false,
                code = "install_failed",
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                stagedFile.delete()
                stagingDirectory.delete()
            }
        }
    }

    private fun validateAsset(
        profile: NexgoDeviceProfile,
        asset: NexgoSystemAsset,
        file: File,
        expectedSha256: String,
    ): NexgoAssetValidationResult = when (asset) {
        NexgoSystemAsset.BOOT_ANIMATION,
        NexgoSystemAsset.SHUTDOWN_ANIMATION,
        -> NexgoSystemAssetValidator.validateAnimation(profile, asset, file, expectedSha256)

        NexgoSystemAsset.POWER_LOGO ->
            NexgoSystemAssetValidator.validatePowerLogo(profile, file, expectedSha256)

        else -> NexgoAssetValidationResult(false, "asset_type_not_installable")
    }

    private suspend fun awaitSystemService(context: Context): Int =
        withTimeout(SERVICE_INIT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    SystemServiceHelper.getInstance().init(
                        context,
                        object : OnPlatformInitListener {
                            override fun onPlatformInitResult(resultCode: Int) {
                                if (continuation.isActive) continuation.resume(resultCode)
                            }
                        },
                    )
                } catch (exception: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
        }

    private fun result(
        context: Context,
        profile: NexgoDeviceProfile,
        asset: NexgoSystemAsset,
        success: Boolean,
        code: String,
        sdkResultCode: Int? = null,
    ) = NexgoAssetInstallResult(success, code, sdkResultCode).also {
        runCatching {
            NexgoDiagnosticsManager.record(
                context,
                buildString {
                    append("systemAsset model=")
                    append(profile.modelKey)
                    append(" asset=")
                    append(asset.name)
                    append(" success=")
                    append(success)
                    append(" code=")
                    append(code)
                    sdkResultCode?.let {
                        append(" sdkResult=")
                        append(it)
                    }
                },
            )
        }
    }
}
