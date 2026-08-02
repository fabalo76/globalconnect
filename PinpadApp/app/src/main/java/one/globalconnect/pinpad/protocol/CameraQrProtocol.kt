package one.globalconnect.pinpad.protocol

import java.nio.charset.StandardCharsets
import java.util.Base64

enum class CameraFacing(val code: Char) {
    Front('F'),
    Back('B'),
}

data class PhotoCaptureRequest(
    val timeoutSeconds: Int,
    val facing: CameraFacing,
    val jpegQuality: Int,
)

sealed interface PhotoCaptureResult {
    data class Captured(val jpegBytes: ByteArray) : PhotoCaptureResult
    data object Cancelled : PhotoCaptureResult
    data object Timeout : PhotoCaptureResult
    data object Error : PhotoCaptureResult
}

data class QrDisplayRequest(
    val timeoutSeconds: Int,
    val value: String,
)

sealed interface QrDisplayResult {
    data object Completed : QrDisplayResult
    data object Cancelled : QrDisplayResult
    data object Timeout : QrDisplayResult
    data object Error : QrDisplayResult
}

data class QrScanRequest(
    val timeoutSeconds: Int,
    val facing: CameraFacing,
)

sealed interface QrScanResult {
    data class Scanned(val value: String) : QrScanResult
    data object Cancelled : QrScanResult
    data object Timeout : QrScanResult
    data object Error : QrScanResult
}

object CameraQrProtocol {
    const val PHOTO_REQUEST_COMMAND = "PH1"
    const val PHOTO_RESPONSE_COMMAND = "PH2"
    const val QR_DISPLAY_REQUEST_COMMAND = "QR1"
    const val QR_DISPLAY_RESPONSE_COMMAND = "QR2"
    const val QR_SCAN_REQUEST_COMMAND = "QR3"
    const val QR_SCAN_RESPONSE_COMMAND = "QR4"
    const val BASE64_PACKET_CHARS = 1_024
    const val MIN_TIMEOUT_SECONDS = 5
    const val MAX_TIMEOUT_SECONDS = 300
    const val MIN_JPEG_QUALITY = 10
    const val MAX_JPEG_QUALITY = 100
    const val MAX_QR_UTF8_BYTES = 2_048

    fun parsePhotoRequest(payload: String): PhotoCaptureRequest? {
        val fields = payload.split(FS_CHAR)
        if (fields.size != 3) return null
        val timeout = parseTimeout(fields[0]) ?: return null
        val facing = parseFacing(fields[1]) ?: return null
        val quality = fields[2].toIntOrNull()
            ?.takeIf { it in MIN_JPEG_QUALITY..MAX_JPEG_QUALITY }
            ?: return null
        return PhotoCaptureRequest(timeout, facing, quality)
    }

    fun photoResponsePayloads(result: PhotoCaptureResult): List<String> {
        if (result !is PhotoCaptureResult.Captured) {
            return listOf("${photoStatus(result)}${packetNumber(0)}${packetNumber(0)}")
        }
        val encoded = Base64.getEncoder().encodeToString(result.jpegBytes)
        if (encoded.isEmpty()) return listOf("4${packetNumber(0)}${packetNumber(0)}")
        val chunks = encoded.chunked(BASE64_PACKET_CHARS)
        require(chunks.size <= MAX_PACKET_NUMBER) {
            "Photo requires more than $MAX_PACKET_NUMBER packets"
        }
        return chunks.mapIndexed { index, data ->
            "1${packetNumber(index + 1)}${packetNumber(chunks.size)}$data"
        }
    }

    fun parseQrDisplayRequest(payload: String): QrDisplayRequest? {
        val fields = payload.split(FS_CHAR, limit = 2)
        if (fields.size != 2) return null
        val timeout = parseTimeout(fields[0]) ?: return null
        val value = decodeQrValue(fields[1]) ?: return null
        return QrDisplayRequest(timeout, value)
    }

    fun qrDisplayResponsePayload(result: QrDisplayResult): String = when (result) {
        QrDisplayResult.Completed -> "1"
        QrDisplayResult.Cancelled -> "2"
        QrDisplayResult.Timeout -> "3"
        QrDisplayResult.Error -> "4"
    }

    fun parseQrScanRequest(payload: String): QrScanRequest? {
        val fields = payload.split(FS_CHAR)
        if (fields.size != 2) return null
        return QrScanRequest(
            timeoutSeconds = parseTimeout(fields[0]) ?: return null,
            facing = parseFacing(fields[1]) ?: return null,
        )
    }

    fun qrScanResponsePayload(result: QrScanResult): String = when (result) {
        is QrScanResult.Scanned -> {
            val bytes = result.value.toByteArray(StandardCharsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > MAX_QR_UTF8_BYTES) {
                "4"
            } else {
                "1$FS_CHAR${Base64.getEncoder().encodeToString(bytes)}"
            }
        }
        QrScanResult.Cancelled -> "2"
        QrScanResult.Timeout -> "3"
        QrScanResult.Error -> "4"
    }

    private fun decodeQrValue(encoded: String): String? {
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_QR_UTF8_BYTES) return null
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun parseTimeout(value: String): Int? = value.toIntOrNull()
        ?.takeIf { it in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS }

    private fun parseFacing(value: String): CameraFacing? = when (value.trim().uppercase()) {
        "F", "FRONT", "0" -> CameraFacing.Front
        "B", "BACK", "1" -> CameraFacing.Back
        else -> null
    }

    private fun photoStatus(result: PhotoCaptureResult): Char = when (result) {
        PhotoCaptureResult.Cancelled -> '2'
        PhotoCaptureResult.Timeout -> '3'
        PhotoCaptureResult.Error -> '4'
        is PhotoCaptureResult.Captured -> '1'
    }

    private fun packetNumber(value: Int): String = value.toString().padStart(PACKET_NUMBER_WIDTH, '0')

    private const val PACKET_NUMBER_WIDTH = 4
    private const val MAX_PACKET_NUMBER = 9_999
    private const val FS_CHAR = '\u001C'
}
