package one.globalconnect.xtmsagent.install

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/** Accept Android OTA packages, not an arbitrary ZIP or an APK renamed to ZIP. */
internal object FirmwarePackageValidator {
    fun validate(file: File, device: String, fingerprint: String, incremental: String): String {
        ZipFile(file).use { zip ->
            require(zip.getEntry("AndroidManifest.xml") == null) { "firmware_invalid" }
            val metadata = zip.getEntry("META-INF/com/android/metadata")
                ?: throw IllegalArgumentException("firmware_metadata_missing")
            val bytes = zip.getInputStream(metadata).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65_536) { "firmware_invalid" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val props = Properties().apply { bytes.inputStream().use { load(it) } }
            val targets = props.getProperty("pre-device", "").split('|').map(String::trim)
            require(device.isNotBlank() && device in targets) { "firmware_wrong_device" }
            props.getProperty("pre-build")?.let {
                require(fingerprint in it.split('|')) { "firmware_wrong_base" }
            }
            props.getProperty("pre-build-incremental")?.let {
                require(incremental == it) { "firmware_wrong_base" }
            }
            require(zip.getEntry("payload.bin") != null ||
                zip.getEntry("META-INF/com/google/android/update-binary") != null) { "firmware_invalid" }
            return props.getProperty("post-build-incremental", "").take(120)
        }
    }
}
