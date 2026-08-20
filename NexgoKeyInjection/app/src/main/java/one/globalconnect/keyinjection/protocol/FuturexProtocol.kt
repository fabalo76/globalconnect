package one.globalconnect.keyinjection.protocol

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets
import one.globalconnect.keyinjection.comm.SerialPacketManager
import one.globalconnect.keyinjection.util.Logger

/**
 * Parser and response builder for the Futurex Direct Key Injection protocol.
 *
 * Packet framing, LRC validation, and ACK/NAK exchange are handled by
 * [SerialPacketManager]. This class validates the ASCII message body before
 * any key material is passed to the Nexgo PED API.
 */
class FuturexProtocol(
    packetManagerFactory: () -> SerialPacketManager = { SerialPacketManager() }
) : KeyInjectionProtocol(packetManagerFactory) {

    constructor(packetManager: SerialPacketManager) : this({ packetManager })

    private val codeToCommand = mapOf(
        "00" to KeyInjectionCommand.DUKPT_INJECT,
        "01" to KeyInjectionCommand.MK_INJECT,
        "02" to KeyInjectionCommand.KTK_ENCRYPTED_KEY_INJECT,
        "03" to KeyInjectionCommand.SERIAL_NUM_REQUEST,
        "04" to KeyInjectionCommand.SERIAL_NUM_WRITE,
        "05" to KeyInjectionCommand.ERASE_KEYS
    )

    private data class Message(
        val command: KeyInjectionCommand,
        val payload: String
    )

    private fun parseMessage(bytes: ByteArray): Message? {
        val text = bytes.toString(Charsets.US_ASCII)
        if (text.length < COMMAND_LENGTH + VERSION_LENGTH) return null
        val command = codeToCommand[text.take(COMMAND_LENGTH)] ?: return null
        return Message(command, text.drop(COMMAND_LENGTH))
    }

    /** Calculate the eight-hex-character 3DES KCV for [hexKey]. */
    fun calculateKCV(hexKey: String): String {
        val normalizedHex = hexKey.uppercase()
        if (!normalizedHex.isHexOf(16, 32, 48)) return ""

        return try {
            val keyBytes = normalizedHex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
            val normalizedKey = when (keyBytes.size) {
                8 -> keyBytes + keyBytes + keyBytes
                16 -> keyBytes + keyBytes.copyOfRange(0, 8)
                24 -> keyBytes
                else -> return ""
            }
            val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(normalizedKey, "DESede"))
            cipher.doFinal(ByteArray(8))
                .copyOfRange(0, 4)
                .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        } catch (error: GeneralSecurityException) {
            Logger.e(TAG, "Unable to calculate key check value", error)
            ""
        }
    }

    /** Return the command represented by [bytes], or `null` if unsupported. */
    fun getCommand(bytes: ByteArray): KeyInjectionCommand? {
        if (bytes.size < COMMAND_LENGTH) return null
        return codeToCommand[bytes.toString(Charsets.US_ASCII).take(COMMAND_LENGTH)]
    }

    fun parseSerialNumRequest(bytes: ByteArray): SerialNumRequest? {
        val message = parseMessage(bytes) ?: return null
        if (message.command != KeyInjectionCommand.SERIAL_NUM_REQUEST) return null
        if (!message.payload.isHexOf(VERSION_LENGTH)) return null
        return SerialNumRequest(message.payload.uppercase())
    }

    fun parseEraseKeysRequest(bytes: ByteArray): EraseKeysRequest? {
        val message = parseMessage(bytes) ?: return null
        if (message.command != KeyInjectionCommand.ERASE_KEYS) return null
        if (!message.payload.isHexOf(VERSION_LENGTH)) return null
        return EraseKeysRequest(message.payload.uppercase())
    }

    fun parseSerialNumWrite(bytes: ByteArray): SerialNumWrite? {
        val message = parseMessage(bytes) ?: return null
        if (message.command != KeyInjectionCommand.SERIAL_NUM_WRITE) return null
        if (message.payload.length != VERSION_LENGTH + SERIAL_NUMBER_LENGTH) return null

        val commandVersion = message.payload.take(VERSION_LENGTH)
        val serialNumber = message.payload.drop(VERSION_LENGTH)
        if (!commandVersion.isHexOf(VERSION_LENGTH) || !serialNumber.isPrintableAscii()) return null
        return SerialNumWrite(commandVersion.uppercase(), serialNumber)
    }

    fun parseMKInject(bytes: ByteArray): MKInject? {
        val message = parseMessage(bytes) ?: return null
        if (message.command != KeyInjectionCommand.MK_INJECT) return null
        if (message.payload.length < KEY_SLOT_LENGTH) return null

        val keySlot = message.payload.take(KEY_SLOT_LENGTH)
        val keyPayload = message.payload.drop(KEY_SLOT_LENGTH).uppercase()
        if (!keySlot.isHexOf(KEY_SLOT_LENGTH) || !keyPayload.isHexOf(16, 32, 48)) return null

        val kcv = calculateKCV(keyPayload)
        if (kcv.isEmpty()) return null
        return MKInject(keySlot.uppercase(), keyPayload, kcv)
    }

    fun parseDUKPTInject(bytes: ByteArray): DUKPTInject? {
        val message = parseMessage(bytes) ?: return null
        if (message.command != KeyInjectionCommand.DUKPT_INJECT) return null
        if (message.payload.length < KEY_SLOT_LENGTH + KSN_LENGTH) return null

        val keySlot = message.payload.take(KEY_SLOT_LENGTH)
        val ksn = message.payload.substring(KEY_SLOT_LENGTH, KEY_SLOT_LENGTH + KSN_LENGTH)
        val ipek = message.payload.drop(KEY_SLOT_LENGTH + KSN_LENGTH).uppercase()
        if (!keySlot.isHexOf(KEY_SLOT_LENGTH) || !ksn.isHexOf(KSN_LENGTH)) return null
        if (!ipek.isHexOf(16, 32, 48)) return null

        val kcv = calculateKCV(ipek)
        if (kcv.isEmpty()) return null
        return DUKPTInject(keySlot.uppercase(), ksn.uppercase(), ipek, kcv)
    }

    /** Parse Futurex command 02 (key under KTK). */
    fun parseKTKEncryptedKeyInject(bytes: ByteArray): KTKEncryptedKeyInject? {
        val text = bytes.toString(Charsets.US_ASCII)
        if (!text.startsWith("02") || text.length < COMMAND_02_FIXED_LENGTH + LENGTH_FIELD_SIZE) {
            return null
        }

        val commandVersion = text.substring(2, 4)
        val keySlot = text.substring(4, 6)
        val ktkSlot = text.substring(6, 8)
        val keyType = text.substring(8, 10).uppercase()
        val keyEncryption = text.substring(10, 12)
        val keyChecksum = text.substring(12, 16).uppercase()
        val ktkChecksum = text.substring(16, 20).uppercase()
        val ksn = text.substring(20, 40).uppercase()
        val keyLength = text.substring(40, 43).uppercase()

        if (!commandVersion.isHexOf(2) || !keySlot.isHexOf(2) || !ktkSlot.isHexOf(2)) return null
        if (keyType !in SUPPORTED_KEY_TYPES || keyEncryption !in SUPPORTED_ENCRYPTION_MODES) return null
        if (!keyChecksum.isHexOf(4) || !ktkChecksum.isHexOf(4) || !ksn.isHexOf(KSN_LENGTH)) return null
        if (!keyLength.isHexOf(LENGTH_FIELD_SIZE)) return null

        val keyPayloadLength = keyLength.toIntOrNull(16) ?: return null
        if (keyPayloadLength <= 0 || keyPayloadLength > MAX_PAYLOAD_LENGTH) return null
        val keyPayloadEnd = COMMAND_02_FIXED_LENGTH + keyPayloadLength
        if (text.length < keyPayloadEnd + LENGTH_FIELD_SIZE) return null

        val keyPayload = text.substring(COMMAND_02_FIXED_LENGTH, keyPayloadEnd)
        if (!keyPayload.isPrintableAscii()) return null

        val ktkLength = text.substring(keyPayloadEnd, keyPayloadEnd + LENGTH_FIELD_SIZE).uppercase()
        if (!ktkLength.isHexOf(LENGTH_FIELD_SIZE)) return null
        val ktkPayloadLength = ktkLength.toIntOrNull(16) ?: return null
        if (ktkPayloadLength < 0 || ktkPayloadLength > MAX_PAYLOAD_LENGTH) return null

        val expectedLength = keyPayloadEnd + LENGTH_FIELD_SIZE + ktkPayloadLength
        if (text.length != expectedLength) return null
        val ktkPayload = text.substring(keyPayloadEnd + LENGTH_FIELD_SIZE)

        when (keyEncryption) {
            "02" -> if (!ktkPayload.isHexOf(16, 32, 48)) return null
            else -> if (ktkPayloadLength != 0 || ktkPayload.isNotEmpty()) return null
        }

        return KTKEncryptedKeyInject(
            commandVersion.uppercase(),
            keySlot.uppercase(),
            ktkSlot.uppercase(),
            keyType,
            keyEncryption,
            keyChecksum,
            ktkChecksum,
            ksn,
            keyLength,
            keyPayload,
            ktkLength,
            ktkPayload.uppercase()
        )
    }

    fun buildMKInjectResponse(responseCode: String, kcv: String): ByteArray =
        responseBody("01", responseCode, kcv)

    fun buildDUKPTInjectResponse(
        ksnInjectionResponseCode: String,
        ipekInjectionResponseCode: String,
        kcv: String
    ): ByteArray {
        val body = "00" + validResponseCode(ksnInjectionResponseCode) +
            validResponseCode(ipekInjectionResponseCode) + validKcv(kcv)
        return body.toByteArray(Charsets.US_ASCII)
    }

    fun buildSerialNumReadResponse(responseCode: String, serialNumber: String): ByteArray {
        require(serialNumber.isPrintableAscii()) { "Serial number must contain printable ASCII" }
        val normalizedSerialNumber = serialNumber.take(SERIAL_NUMBER_LENGTH)
            .padEnd(SERIAL_NUMBER_LENGTH, ' ')
        return ("03" + validResponseCode(responseCode) + normalizedSerialNumber)
            .toByteArray(Charsets.US_ASCII)
    }

    fun buildEraseAllKeys(responseCode: String): ByteArray =
        ("05" + validResponseCode(responseCode)).toByteArray(Charsets.US_ASCII)

    fun buildKTKEncryptedKeyInjectResponse(
        keyInjectionResponseCode: String,
        kcv: String
    ): ByteArray = responseBody("02", keyInjectionResponseCode, kcv)

    private fun responseBody(command: String, responseCode: String, kcv: String): ByteArray =
        (command + validResponseCode(responseCode) + validKcv(kcv))
            .toByteArray(Charsets.US_ASCII)

    private fun validResponseCode(responseCode: String): String {
        val normalized = responseCode.uppercase()
        require(normalized.isHexOf(2)) { "Response code must be two ASCII hex characters" }
        return normalized
    }

    private fun validKcv(kcv: String): String {
        val normalized = kcv.take(4).uppercase()
        require(normalized.isHexOf(4)) { "KCV must contain at least four ASCII hex characters" }
        return normalized
    }

    private fun String.isHexOf(vararg lengths: Int): Boolean =
        length in lengths && all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    private fun String.isPrintableAscii(): Boolean = all { it.code in 0x20..0x7E }

    private companion object {
        const val TAG = "FuturexProtocol"
        const val COMMAND_LENGTH = 2
        const val VERSION_LENGTH = 2
        const val KEY_SLOT_LENGTH = 2
        const val KSN_LENGTH = 20
        const val SERIAL_NUMBER_LENGTH = 16
        const val LENGTH_FIELD_SIZE = 3
        const val COMMAND_02_FIXED_LENGTH = 43
        const val MAX_PAYLOAD_LENGTH = 512

        val SUPPORTED_KEY_TYPES = setOf(
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "0A", "0B"
        )
        val SUPPORTED_ENCRYPTION_MODES = setOf("00", "01", "02")
    }
}
