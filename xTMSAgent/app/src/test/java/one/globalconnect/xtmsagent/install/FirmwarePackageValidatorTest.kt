package one.globalconnect.xtmsagent.install

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class FirmwarePackageValidatorTest {
    private fun checkPackage(metadata: String?, payload: Boolean = true, apk: Boolean = false): String {
        val file = File.createTempFile("firmware-test", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, data: String = "") {
                    zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry()
                }
                metadata?.let { entry("META-INF/com/android/metadata", it) }
                if (payload) entry("payload.bin")
                if (apk) entry("AndroidManifest.xml")
            }
            return FirmwarePackageValidator.validate(file, "N96", "current-build", "123")
        } finally { file.delete() }
    }

    @Test fun acceptsMatchingFullAndIncrementalOtaMetadata() {
        assertEquals("456", checkPackage("pre-device=N96\npost-build-incremental=456"))
        assertEquals("", checkPackage("pre-device=N96|N96S\npre-build=current-build\npre-build-incremental=123"))
    }

    @Test fun rejectsWrongDeviceAndIncrementalBase() {
        listOf("pre-device=N82", "pre-device=N96\npre-build=other-build",
            "pre-device=N96\npre-build-incremental=122").forEach {
            assertThrows(IllegalArgumentException::class.java) { checkPackage(it) }
        }
    }

    @Test fun rejectsMissingMetadataPayloadAndDisguisedApk() {
        assertThrows(IllegalArgumentException::class.java) { checkPackage(null) }
        assertThrows(IllegalArgumentException::class.java) { checkPackage("pre-device=N96", payload = false) }
        assertThrows(IllegalArgumentException::class.java) { checkPackage("pre-device=N96", apk = true) }
        assertThrows(IllegalArgumentException::class.java) { checkPackage("pre-device=N96\nextra=" + "x".repeat(70_000)) }
    }
}
