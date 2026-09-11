package one.globalconnect.xtmsagent.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.OnAppOperatListener
import com.nexgo.oaf.apiv3.SdkResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import one.globalconnect.xtmsagent.mqtt.downloads.DeviceOwnerPackageInstaller

internal data class LocalApkInstallResult(
    val success: Boolean,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val message: String,
)

/** Installs a user-selected APK through the same xTMSAgent-owned path as TMS installs. */
internal object LocalApkInstaller {
    private const val INSTALL_TIMEOUT_MS = 300_000L

    suspend fun install(context: Context, source: Uri): LocalApkInstallResult {
        val appContext = context.applicationContext
        val workDir = File(appContext.cacheDir, "local-install").apply {
            check(mkdirs() || isDirectory) { "Unable to create local install workspace" }
        }
        val stagedFile = File.createTempFile("selected-", ".apk", workDir)
        val installFile = File(
            appContext.externalCacheDir ?: workDir,
            "local-install-${UUID.randomUUID()}.apk",
        )

        var packageName = ""
        var versionName = ""
        var versionCode = -1L
        return try {
            appContext.contentResolver.openInputStream(source)?.use { input ->
                stagedFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("The selected APK could not be opened")
            check(stagedFile.length() > 0L) { "The selected APK is empty" }

            val packageInfo = packageArchiveInfo(appContext, stagedFile)
                ?: error("The selected file is not a valid Android application")
            packageName = packageInfo.packageName.orEmpty()
            versionName = packageInfo.versionName.orEmpty()
            versionCode = packageInfo.versionCodeCompat()
            check(packageName.isNotBlank() && versionCode >= 0L) {
                "The selected APK has invalid package metadata"
            }

            stagedFile.copyTo(installFile, overwrite = true)
            installFile.setReadable(true, false)
            val ownerResult = DeviceOwnerPackageInstaller.install(
                context = appContext,
                taskId = null,
                apkFile = installFile,
                packageName = packageName,
                versionCode = versionCode,
                stagedFile = stagedFile,
            )
            if (ownerResult.success && installedVersion(appContext, packageName) == versionCode) {
                LocalApkInstallResult(true, packageName, versionName, versionCode, ownerResult.message)
            } else if (ownerResult.shouldFallback) {
                installWithNexgo(appContext, installFile, packageName, versionName, versionCode)
            } else {
                LocalApkInstallResult(false, packageName, versionName, versionCode, ownerResult.message)
            }
        } catch (error: Exception) {
            LocalApkInstallResult(
                success = false,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                message = error.message ?: error.javaClass.simpleName,
            )
        } finally {
            stagedFile.delete()
            installFile.delete()
        }
    }

    private suspend fun installWithNexgo(
        context: Context,
        apkFile: File,
        packageName: String,
        versionName: String,
        versionCode: Long,
    ): LocalApkInstallResult {
        val completion = CompletableDeferred<Boolean>()
        val result = APIProxy.getDeviceEngine(context).platform.installApp(
            apkFile.absolutePath,
            object : OnAppOperatListener {
                override fun onOperatResult(res: Int) {
                    completion.complete(res == SdkResult.Success)
                }
            },
        )
        if (result != SdkResult.Success) {
            return LocalApkInstallResult(
                false,
                packageName,
                versionName,
                versionCode,
                "The device installer rejected the application ($result)",
            )
        }
        val completed = withTimeoutOrNull(INSTALL_TIMEOUT_MS) { completion.await() } == true
        val installed = installedVersion(context, packageName) == versionCode
        return LocalApkInstallResult(
            success = completed && installed,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            message = if (completed && installed) {
                "Installed by the Nexgo package installer"
            } else {
                "The application could not be installed"
            },
        )
    }

    private fun packageArchiveInfo(context: Context, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }

    private fun installedVersion(context: Context, packageName: String): Long? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            ).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        }
    }.getOrNull()

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
}
