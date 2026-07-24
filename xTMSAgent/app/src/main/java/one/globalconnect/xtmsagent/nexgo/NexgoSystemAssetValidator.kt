package one.globalconnect.xtmsagent.nexgo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

enum class NexgoSystemAsset(
    val fixedFileName: String,
    val capability: NexgoCapability,
) {
    BOOT_ANIMATION("bootanimation.zip", NexgoCapability.BOOT_ANIMATION),
    SHUTDOWN_ANIMATION("shutdownanimation.zip", NexgoCapability.SHUTDOWN_ANIMATION),
    STANDALONE_BOOT_SOUND("boot.wav", NexgoCapability.STANDALONE_BOOT_SOUND),
    DEFAULT_WALLPAPER("default_wallpaper.jpg", NexgoCapability.DEFAULT_WALLPAPER),
    POWER_LOGO("xgd_logo.bin", NexgoCapability.POWER_LOGO),
    ;

    fun fixedFileName(profile: NexgoDeviceProfile): String =
        if (this == STANDALONE_BOOT_SOUND && profile.modelKey == "CT20P") {
            "bootsound.mp3"
        } else {
            fixedFileName
        }
}

data class NexgoAssetValidationResult(
    val isValid: Boolean,
    val code: String,
    val sha256: String? = null,
)

object NexgoSystemAssetValidator {
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 128L * 1024 * 1024
    private const val MAX_POWER_LOGO_BYTES = 16L * 1024 * 1024
    private const val XGD_HEADER_BYTES = 1_000
    private const val MTK_HEADER_BYTES = 512
    private const val MTK_MAGIC = 0x58881688
    private const val MTK_IMAGE_COUNT = 2
    private val XGD_LOGO_MAGIC = "XGD_LOGO_UPDATE_HEAD".toByteArray(Charsets.US_ASCII)
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun validateAnimation(
        profile: NexgoDeviceProfile,
        asset: NexgoSystemAsset,
        file: File,
        expectedSha256: String,
    ): NexgoAssetValidationResult {
        if (asset != NexgoSystemAsset.BOOT_ANIMATION &&
            asset != NexgoSystemAsset.SHUTDOWN_ANIMATION
        ) {
            return invalid("asset_type_not_animation")
        }
        if (profile.capabilities[asset.capability] != NexgoCapabilityState.SUPPORTED) {
            return invalid("asset_not_supported_for_model")
        }
        if (file.name != asset.fixedFileName(profile)) return invalid("invalid_fixed_filename")
        if (!file.isFile || file.length() <= 0L) return invalid("asset_missing_or_empty")
        if (file.length() > MAX_ARCHIVE_BYTES) return invalid("asset_too_large")
        if (!SHA256_REGEX.matches(expectedSha256)) return invalid("invalid_expected_sha256")

        val actualSha256 = sha256(file)
            ?: return invalid("sha256_unavailable")
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            return invalid("sha256_mismatch", actualSha256)
        }

        val expectedDisplay = expectedAnimationDisplay(profile, asset)
            ?: return invalid("animation_dimensions_unknown", actualSha256)
        return validateStoredAnimationArchive(file, expectedDisplay, actualSha256)
    }

    fun validatePowerLogo(
        profile: NexgoDeviceProfile,
        file: File,
        expectedSha256: String,
    ): NexgoAssetValidationResult {
        val display = profile.display
        if (profile.capabilities[NexgoCapability.POWER_LOGO] != NexgoCapabilityState.SUPPORTED ||
            !profile.commandProfileVerified ||
            display == null
        ) {
            return invalid("power_logo_not_supported_for_model")
        }
        if (file.name != NexgoSystemAsset.POWER_LOGO.fixedFileName(profile)) {
            return invalid("invalid_fixed_filename")
        }
        if (!file.isFile || file.length() <= 0L) return invalid("asset_missing_or_empty")
        if (file.length() > MAX_POWER_LOGO_BYTES) return invalid("asset_too_large")
        if (!SHA256_REGEX.matches(expectedSha256)) return invalid("invalid_expected_sha256")

        val actualSha256 = sha256(file) ?: return invalid("sha256_unavailable")
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            return invalid("sha256_mismatch", actualSha256)
        }
        return validateNexgoMtkPowerLogoContainer(profile.modelKey, display, file, actualSha256)
    }

    private fun validateNexgoMtkPowerLogoContainer(
        modelKey: String,
        display: NexgoDisplaySpec,
        file: File,
        sha256: String,
    ): NexgoAssetValidationResult {
        return try {
            val bytes = file.readBytes()
        if (bytes.size < XGD_HEADER_BYTES + MTK_HEADER_BYTES + 16) {
            return invalid("power_logo_container_too_small", sha256)
        }
        if (!bytes.copyOfRange(0, XGD_LOGO_MAGIC.size).contentEquals(XGD_LOGO_MAGIC)) {
            return invalid("power_logo_wrapper_magic_invalid", sha256)
        }
        if (readLittleEndianInt(bytes, 36) != 1) {
            return invalid("power_logo_record_count_invalid", sha256)
        }
        val recordName = bytes.copyOfRange(40, 72)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.US_ASCII)
        val recordOffset = readLittleEndianInt(bytes, 72)
        val recordSize = readLittleEndianInt(bytes, 76)
        val expectedRecordName = if (modelKey == "N82") {
            "${display.width}_uboot_logo.bin"
        } else {
            "${display.width}_logo.bin"
        }
        if (recordName != expectedRecordName || recordOffset != XGD_HEADER_BYTES) {
            return invalid("power_logo_record_invalid", sha256)
        }
        if (recordSize <= MTK_HEADER_BYTES || recordOffset + recordSize != bytes.size) {
            return invalid("power_logo_record_size_invalid", sha256)
        }
        if (readLittleEndianInt(bytes, recordOffset) != MTK_MAGIC ||
            bytes.copyOfRange(recordOffset + 8, recordOffset + 12)
                .toString(Charsets.US_ASCII) != "logo"
        ) {
            return invalid("power_logo_payload_magic_invalid", sha256)
        }

        val blockSize = readLittleEndianInt(bytes, recordOffset + 4)
        val imageCount = readLittleEndianInt(bytes, recordOffset + MTK_HEADER_BYTES)
        val mappedBlockSize = readLittleEndianInt(bytes, recordOffset + MTK_HEADER_BYTES + 4)
        if (imageCount != MTK_IMAGE_COUNT ||
            blockSize != mappedBlockSize ||
            blockSize != recordSize - MTK_HEADER_BYTES
        ) {
            return invalid("power_logo_image_map_invalid", sha256)
        }

        val mapBytes = 8 + imageCount * 4
        val offsets = IntArray(imageCount) { index ->
            readLittleEndianInt(bytes, recordOffset + MTK_HEADER_BYTES + 8 + index * 4)
        }
            if (offsets.first() != mapBytes ||
                offsets.any { it !in mapBytes until blockSize } ||
                offsets.toList().zipWithNext().any { (current, next) -> current >= next }
            ) {
                return invalid("power_logo_image_offsets_invalid", sha256)
            }

        var firstImage: ByteArray? = null
        offsets.forEachIndexed { index, startOffset ->
            val endOffset = offsets.getOrNull(index + 1) ?: blockSize
            val compressedStart = recordOffset + MTK_HEADER_BYTES + startOffset
            val compressedEnd = recordOffset + MTK_HEADER_BYTES + endOffset
            val expectedRgbaBytes = display.width * display.height * 4
            val image = inflateBounded(bytes, compressedStart, compressedEnd, expectedRgbaBytes)
                ?: return invalid("power_logo_image_invalid", sha256)
            if (image.indices.step(4).any { image[it + 3] != 0xFF.toByte() }) {
                return invalid("power_logo_alpha_invalid", sha256)
            }
            if (firstImage == null) {
                firstImage = image
            } else if (firstImage?.contentEquals(image) != true) {
                return invalid("power_logo_images_differ", sha256)
            }
        }
            NexgoAssetValidationResult(true, "validated", sha256)
        } catch (_: Exception) {
            invalid("power_logo_container_invalid", sha256)
        }
    }

    private fun validateStoredAnimationArchive(
        file: File,
        expectedDisplay: NexgoDisplaySpec,
        sha256: String,
    ): NexgoAssetValidationResult = try {
        ZipFile(file).use { archive ->
            var totalUncompressed = 0L
            var frameCount = 0
            var description: String? = null
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!isSafeEntryName(entry.name)) return invalid("unsafe_archive_path", sha256)
                if (entry.isDirectory) continue
                if (entry.method != ZipEntry.STORED) return invalid("zip_entry_not_stored", sha256)
                if (entry.size < 0L) return invalid("zip_entry_size_unknown", sha256)
                totalUncompressed += entry.size
                if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                    return invalid("archive_uncompressed_size_too_large", sha256)
                }
                if (entry.name == "desc.txt") {
                    description = archive.getInputStream(entry).bufferedReader().use { it.readText() }
                } else if (entry.name.substringAfterLast('/').contains('.')) {
                    frameCount++
                }
            }

            if (frameCount == 0) return invalid("animation_has_no_frames", sha256)
            val header = description
                ?.lineSequence()
                ?.map(String::trim)
                ?.firstOrNull(String::isNotEmpty)
                ?: return invalid("animation_desc_missing", sha256)
            val parts = header.split(Regex("\\s+"))
            if (parts.size < 3) return invalid("animation_desc_invalid", sha256)
            val width = parts[0].toIntOrNull()
            val height = parts[1].toIntOrNull()
            val framesPerSecond = parts[2].toIntOrNull()
            if (width != expectedDisplay.width || height != expectedDisplay.height) {
                return invalid("animation_dimensions_mismatch", sha256)
            }
            if (framesPerSecond == null || framesPerSecond !in 1..120) {
                return invalid("animation_frame_rate_invalid", sha256)
            }
            NexgoAssetValidationResult(true, "validated", sha256)
        }
    } catch (_: Exception) {
        invalid("animation_archive_invalid", sha256)
    }

    private fun expectedAnimationDisplay(
        profile: NexgoDeviceProfile,
        asset: NexgoSystemAsset,
    ): NexgoDisplaySpec? = when (asset) {
        NexgoSystemAsset.BOOT_ANIMATION -> profile.display
        NexgoSystemAsset.SHUTDOWN_ANIMATION -> when (profile.modelKey) {
            "CT20P", "N6S", "N82", "N96" -> NexgoDisplaySpec(720, 1280)
            else -> null
        }
        else -> null
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || name.startsWith('\\')) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return false
        return name.replace('\\', '/').split('/').none { it == ".." }
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private fun inflateBounded(
        bytes: ByteArray,
        start: Int,
        end: Int,
        expectedBytes: Int,
    ): ByteArray? {
        if (start < 0 || end <= start || end > bytes.size) return null
        val output = ByteArrayOutputStream(expectedBytes)
        InflaterInputStream(ByteArrayInputStream(bytes, start, end - start)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > expectedBytes) return null
                output.write(buffer, 0, read)
            }
            if (total != expectedBytes) return null
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02X".format(it) }
    } catch (_: Exception) {
        null
    }

    private fun invalid(code: String, sha256: String? = null) =
        NexgoAssetValidationResult(false, code, sha256)
}
