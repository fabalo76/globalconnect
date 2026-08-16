package one.globalconnect.xtmsagent.store

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.OnAppOperatListener
import com.nexgo.oaf.apiv3.SdkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import one.globalconnect.xtmsagent.mqtt.downloads.DeviceOwnerPackageInstaller
import java.io.File

internal object StoreAppInstaller {
    suspend fun install(context: Context, application: StoreApplication, downloadedFile: File) {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(downloadedFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(downloadedFile.absolutePath, 0)
        } ?: throw IllegalStateException("The downloaded file is not a valid Android application")
        check(packageInfo.packageName == application.packageName) { "The downloaded application package is invalid" }
        check(packageInfo.longVersionCode == application.versionCode) { "The downloaded application version is invalid" }

        val installFile = File(context.externalCacheDir ?: context.cacheDir, downloadedFile.name)
        downloadedFile.copyTo(installFile, overwrite = true)
        installFile.setReadable(true, false)
        try {
            val ownerResult = DeviceOwnerPackageInstaller.install(
                context = context,
                taskId = null,
                apkFile = installFile,
                packageName = application.packageName,
                versionCode = application.versionCode,
                stagedFile = downloadedFile,
            )
            if (ownerResult.success || isInstalled(context, application)) return
            check(ownerResult.shouldFallback) { ownerResult.message }

            val completion = CompletableDeferred<Boolean>()
            val result = APIProxy.getDeviceEngine(context).platform.installApp(
                installFile.absolutePath,
                object : OnAppOperatListener {
                    override fun onOperatResult(res: Int) {
                        completion.complete(res == SdkResult.Success)
                    }
                },
            )
            check(result == SdkResult.Success) { "The device installer rejected the application" }
            val completed = withTimeoutOrNull(300_000L) { completion.await() } ?: false
            check(completed && isInstalled(context, application)) { "The application could not be installed" }
        } finally {
            installFile.delete()
            downloadedFile.delete()
        }
    }

    fun installedVersion(context: Context, packageName: String): Long? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).longVersionCode
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun isInstalled(context: Context, application: StoreApplication): Boolean =
        (installedVersion(context, application.packageName) ?: -1L) >= application.versionCode
}
