package one.globalconnect.pinpad.protocol

import java.util.Base64

enum class SignatureOrientation(val code: Char) {
    Horizontal('H'),
    Vertical('V'),
}

enum class SignatureImageFormat(val code: Char) {
    Png('P'),
    Jpeg('J'),
}

data class SignatureCaptureRequest(
    val timeoutSeconds: Int,
    val orientation: SignatureOrientation,
    val imageFormat: SignatureImageFormat,
)

sealed interface SignatureCaptureResult {
    data class Captured(val imageBytes: ByteArray) : SignatureCaptureResult
    data object Cancelled : SignatureCaptureResult
    data object Timeout : SignatureCaptureResult
    data object Error : SignatureCaptureResult
}

object SignatureCaptureProtocol {
    const val REQUEST_COMMAND = "S1"
    const val RESPONSE_COMMAND = "S2"
    const val BASE64_PACKET_CHARS = 1_024
    const val MIN_TIMEOUT_SECONDS = 5
    const val MAX_TIMEOUT_SECONDS = 300

    fun parseRequest(payload: String): SignatureCaptureRequest? {
        val fields = payload.split(FS_CHAR)
        if (fields.size != 3) return null
        val timeoutSeconds = fields[0].toIntOrNull()
            ?.takeIf { it in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS }
            ?: return null
        val orientation = when (fields[1].trim().uppercase()) {
            "H", "HORIZONTAL", "0" -> SignatureOrientation.Horizontal
            "V", "VERTICAL", "1" -> SignatureOrientation.Vertical
            else -> return null
        }
        val imageFormat = when (fields[2].trim().uppercase()) {
            "P", "PNG", "0" -> SignatureImageFormat.Png
            "J", "JPG", "JPEG", "1" -> SignatureImageFormat.Jpeg
            else -> return null
        }
        return SignatureCaptureRequest(timeoutSeconds, orientation, imageFormat)
    }

    fun responsePayloads(result: SignatureCaptureResult): List<String> {
        if (result !is SignatureCaptureResult.Captured) {
            val resultCode = when (result) {
                SignatureCaptureResult.Cancelled -> '2'
                SignatureCaptureResult.Timeout -> '3'
                SignatureCaptureResult.Error -> '4'
                is SignatureCaptureResult.Captured -> error("Captured result handled above")
            }
            return listOf("$resultCode${packetNumber(0)}${packetNumber(0)}")
        }

        val encoded = Base64.getEncoder().encodeToString(result.imageBytes)
        if (encoded.isEmpty()) return listOf("4${packetNumber(0)}${packetNumber(0)}")
        val chunks = encoded.chunked(BASE64_PACKET_CHARS)
        require(chunks.size <= MAX_PACKET_NUMBER) {
            "Signature image requires more than $MAX_PACKET_NUMBER packets"
        }
        return chunks.mapIndexed { index, data ->
            "1${packetNumber(index + 1)}${packetNumber(chunks.size)}$data"
        }
    }

    private fun packetNumber(value: Int): String = value.toString().padStart(PACKET_NUMBER_WIDTH, '0')

    private const val PACKET_NUMBER_WIDTH = 4
    private const val MAX_PACKET_NUMBER = 9_999
    private const val FS_CHAR = '\u001C'
}
