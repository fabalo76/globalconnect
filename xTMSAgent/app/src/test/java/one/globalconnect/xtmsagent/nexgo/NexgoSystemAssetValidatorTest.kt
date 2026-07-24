package one.globalconnect.xtmsagent.nexgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NexgoSystemAssetValidatorTest {
    @Test
    fun `accepts stored N96 boot animation with matching dimensions and digest`() {
        val directory = Files.createTempDirectory("nexgo-animation-test").toFile()
        try {
            val archive = File(directory, "bootanimation.zip")
            writeAnimation(archive, "720 1600 20\np 1 0 part0\n", ZipEntry.STORED)
            val profile = NexgoProfileResolver.resolve("N96", "N96", "90000000")

            val result = NexgoSystemAssetValidator.validateAnimation(
                profile,
                NexgoSystemAsset.BOOT_ANIMATION,
                archive,
                sha256(archive),
            )

            assertTrue(result.isValid)
            assertEquals("validated", result.code)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects compressed entries before PSS can receive them`() {
        val directory = Files.createTempDirectory("nexgo-animation-test").toFile()
        try {
            val archive = File(directory, "bootanimation.zip")
            writeAnimation(archive, "720 1280 30\np 1 0 part0\n", ZipEntry.DEFLATED)
            val profile = NexgoProfileResolver.resolve("N6S", "N6", "80000000")

            val result = NexgoSystemAssetValidator.validateAnimation(
                profile,
                NexgoSystemAsset.BOOT_ANIMATION,
                archive,
                sha256(archive),
            )

            assertFalse(result.isValid)
            assertEquals("zip_entry_not_stored", result.code)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `preserves vendor shutdown canvas on N96`() {
        val directory = Files.createTempDirectory("nexgo-animation-test").toFile()
        try {
            val archive = File(directory, "shutdownanimation.zip")
            writeAnimation(archive, "720 1600 25\np 1 0 part0\n", ZipEntry.STORED)
            val profile = NexgoProfileResolver.resolve("N96", "N96", "90000000")

            val result = NexgoSystemAssetValidator.validateAnimation(
                profile,
                NexgoSystemAsset.SHUTDOWN_ANIMATION,
                archive,
                sha256(archive),
            )

            assertFalse(result.isValid)
            assertEquals("animation_dimensions_mismatch", result.code)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `accepts CT20P vendor shutdown canvas`() {
        val directory = Files.createTempDirectory("nexgo-animation-test").toFile()
        try {
            val archive = File(directory, "shutdownanimation.zip")
            writeAnimation(archive, "720 1280 30\np 1 0 part0\n", ZipEntry.STORED)
            val profile = NexgoProfileResolver.resolve("CT20P", "CT20P", "70000000")

            val result = NexgoSystemAssetValidator.validateAnimation(
                profile,
                NexgoSystemAsset.SHUTDOWN_ANIMATION,
                archive,
                sha256(archive),
            )

            assertTrue(result.isValid)
            assertEquals("validated", result.code)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `requires fixed Nexgo system filename`() {
        val directory = Files.createTempDirectory("nexgo-animation-test").toFile()
        try {
            val archive = File(directory, "renamed.zip")
            writeAnimation(archive, "480 854 20\np 1 0 part0\n", ZipEntry.STORED)
            val profile = NexgoProfileResolver.resolve("N82", "N82", null)

            val result = NexgoSystemAssetValidator.validateAnimation(
                profile,
                NexgoSystemAsset.BOOT_ANIMATION,
                archive,
                sha256(archive),
            )

            assertFalse(result.isValid)
            assertEquals("invalid_fixed_filename", result.code)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `accepts Nexgo MTK power logo for a verified CT20P profile`() {
        val directory = Files.createTempDirectory("nexgo-logo-test").toFile()
        try {
            val logo = File(directory, "xgd_logo.bin")
            writePowerLogo(logo, 480, 800)
            val profile = NexgoProfileResolver.resolve("CT20P", "CT20P", "70000000")

            val result = NexgoSystemAssetValidator.validatePowerLogo(
                profile,
                logo,
                sha256(logo),
            )

            assertTrue(result.isValid)
            assertEquals("validated", result.code)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `accepts Nexgo MTK power logo for a verified N82 profile`() {
        val directory = Files.createTempDirectory("nexgo-logo-test").toFile()
        try {
            val logo = File(directory, "xgd_logo.bin")
            writePowerLogo(logo, 480, 854, "480_uboot_logo.bin")
            val profile = NexgoProfileResolver.resolve("N82", "N82", "80000000")

            val result = NexgoSystemAssetValidator.validatePowerLogo(
                profile,
                logo,
                sha256(logo),
            )

            assertTrue(result.isValid)
            assertEquals("validated", result.code)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeAnimation(file: File, description: String, method: Int) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            writeEntry(zip, "desc.txt", description.toByteArray(), method)
            writeEntry(zip, "part0/00001.png", byteArrayOf(0x01, 0x02, 0x03), method)
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, method: Int) {
        val entry = ZipEntry(name).apply {
            this.method = method
            if (method == ZipEntry.STORED) {
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writePowerLogo(
        file: File,
        width: Int,
        height: Int,
        recordName: String = "${width}_logo.bin",
    ) {
        val rgba = ByteArray(width * height * 4) { 0xFF.toByte() }
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output, Deflater(9)).use { it.write(rgba) }
        }.toByteArray()
        val mapSize = 16
        val blockSize = mapSize + compressed.size * 2
        val payload = ByteArray(512 + blockSize) { 0xFF.toByte() }
        val payloadBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        payloadBuffer.putInt(0, 0x58881688)
        payloadBuffer.putInt(4, blockSize)
        "logo".toByteArray(Charsets.US_ASCII).copyInto(payload, 8)
        payloadBuffer.putInt(12, 13)
        payloadBuffer.putInt(48, 0x58891689)
        payloadBuffer.putInt(52, 512)
        payloadBuffer.putInt(56, 1)
        payload.fill(0, 60, 68)
        payloadBuffer.putInt(68, 16)
        payload.fill(0, 72, 80)
        payloadBuffer.putInt(512, 2)
        payloadBuffer.putInt(516, blockSize)
        payloadBuffer.putInt(520, mapSize)
        payloadBuffer.putInt(524, mapSize + compressed.size)
        compressed.copyInto(payload, 512 + mapSize)
        compressed.copyInto(payload, 512 + mapSize + compressed.size)

        val container = ByteArray(1000 + payload.size)
        "XGD_LOGO_UPDATE_HEAD".toByteArray(Charsets.US_ASCII).copyInto(container)
        val containerBuffer = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN)
        containerBuffer.putInt(36, 1)
        recordName.toByteArray(Charsets.US_ASCII).copyInto(container, 40)
        containerBuffer.putInt(72, 1000)
        containerBuffer.putInt(76, payload.size)
        payload.copyInto(container, 1000)
        file.writeBytes(container)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02X".format(it) }
}
