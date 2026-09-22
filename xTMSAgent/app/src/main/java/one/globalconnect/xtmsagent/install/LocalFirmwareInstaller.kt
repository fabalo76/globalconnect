package one.globalconnect.xtmsagent.install

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.RecoverySystem
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.SdkResult
import java.io.File

internal data class PreparedFirmware(val file: File, val version: String)

internal object LocalFirmwareInstaller {
    fun prepare(context: Context, uri: Uri): PreparedFirmware {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "local-firmware")
        check(directory.mkdirs() || directory.isDirectory) { "firmware_storage" }
        val file = File.createTempFile("ota-", ".zip", directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 4L * 1024 * 1024 * 1024 && directory.usableSpace > 128L * 1024 * 1024) {
                            "firmware_storage"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("firmware_invalid")
            val version = FirmwarePackageValidator.validate(file, Build.DEVICE, Build.FINGERPRINT, Build.VERSION.INCREMENTAL)
            // Verify the whole OTA against the device's trusted OTA certificates.
            try { RecoverySystem.verifyPackage(file, null, null) }
            catch (e: Exception) { throw IllegalArgumentException("firmware_signature", e) }
            check(file.setReadable(true, false)) { "firmware_storage" }
            return PreparedFirmware(file, version)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun requestUpdate(context: Context, firmware: PreparedFirmware) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        check(level >= 0 && scale > 0 && level * 100L / scale >= 30) { "firmware_battery" }
        val result = APIProxy.getDeviceEngine(context).platform.updateFirmware(firmware.file.absolutePath)
        check(result == SdkResult.Success) { "firmware_request_failed" }
        // Keep the file: the system updater may read it asynchronously, across reboot.
        // SDK success acknowledges dispatch, not installation or firmware verification completion.
    }
}
