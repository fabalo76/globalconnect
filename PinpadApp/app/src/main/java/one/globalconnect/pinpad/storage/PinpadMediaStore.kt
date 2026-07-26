package one.globalconnect.pinpad.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Base64

class PinpadMediaStore private constructor(rootDir: File) {
    constructor(context: Context) : this(File(context.filesDir, STORE_DIR_NAME))

    internal constructor(baseDir: File, testMarker: Unit = Unit) : this(baseDir)

    private val storeDir = rootDir.apply { mkdirs() }
    private val mediaDir = File(storeDir, MEDIA_DIR_NAME).apply { mkdirs() }
    private val transferDir = File(storeDir, TRANSFER_DIR_NAME).apply { mkdirs() }
    private var download: PendingDownload? = null
    private var upload: PendingUpload? = null

    init {
        transferDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
    }

    fun initialize(): Boolean {
        return runCatching {
            clearPendingDownload()
            clearPendingUpload()
            mediaDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
            transferDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
            true
        }.onFailure { Log.w(TAG, "Unable to initialize media table", it) }.getOrDefault(false)
    }

    fun table(): List<MediaEntry> {
        return mediaDir.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .mapNotNull { file ->
                MediaType.fromFileName(file.name)?.let { type ->
                    MediaEntry(file.name, type, file.length())
                }
            }
            .sortedBy { it.name }
    }

    fun downloadPacket(payload: String): Char {
        if (payload.length < MIN_DOWNLOAD_PAYLOAD_LENGTH) return '7'
        val packetType = payload[0]
        if (packetType !in PACKET_TYPES) return '1'
        val sequenceEnd = 1 + SEQUENCE_FIELD_LENGTH
        val sequence = payload.substring(1, sequenceEnd).toIntOrNull() ?: return '2'
        val force = payload[sequenceEnd]
        if (force !in FORCE_VALUES) return '3'

        val rest = payload.drop(sequenceEnd + 1)
        val firstSeparator = rest.indexOf(FS)
        val secondSeparator = if (firstSeparator >= 0) rest.indexOf(FS, firstSeparator + 1) else -1
        if (firstSeparator < 0 || secondSeparator < 0) return '7'

        val packetName = normalizedName(rest.substring(firstSeparator + 1, secondSeparator))
        val fileName = packetName ?: download?.fileName ?: return 'B'
        val mediaType = MediaType.fromFileName(fileName) ?: return 'C'
        val sizeOffset = secondSeparator + 1
        val data = parseDownloadPacketData(rest, sizeOffset) ?: return '5'
        if (data.any { it.code > 0x7F }) return '5'

        val pending = if (sequence == 0) {
            clearPendingDownload()
            val target = mediaFile(fileName)
            if (force == '0' && target.isFile) return '4'
            if (!target.isFile && table().size >= MAX_MEDIA_FILES) return '8'
            PendingDownload(
                fileName = fileName,
                mediaType = mediaType,
                decodedFile = File.createTempFile("media-download-", ".part", transferDir),
                nextSequence = 0,
                decodedBytes = 0,
                base64Carry = "",
            ).also { download = it }
        } else {
            download ?: return '2'
        }

        if (pending.fileName != fileName || pending.nextSequence != sequence) return '2'
        val appendStatus = appendDecodedData(
            pending = pending,
            data = data,
            finalPacket = packetType == '1',
        )
        if (appendStatus != null) {
            clearPendingDownload()
            return appendStatus
        }

        pending.nextSequence += 1
        if (packetType == '0') return '0'
        if (pending.decodedBytes == 0L) {
            clearPendingDownload()
            return 'A'
        }

        val status = finishDownload(pending)
        clearPendingDownload()
        return status
    }

    private fun appendDecodedData(
        pending: PendingDownload,
        data: String,
        finalPacket: Boolean,
    ): Char? {
        return try {
            val combined = pending.base64Carry + data
            val decodeLength = if (finalPacket) {
                combined.length
            } else {
                combined.length - (combined.length % BASE64_GROUP_LENGTH)
            }
            if (decodeLength > 0) {
                val decoded = Base64.getDecoder().decode(combined.substring(0, decodeLength))
                val updatedSize = pending.decodedBytes + decoded.size
                if (updatedSize > maxDecodedBytes(pending.mediaType)) return '8'
                pending.decodedFile.appendBytes(decoded)
                pending.decodedBytes = updatedSize
            }
            pending.base64Carry = combined.substring(decodeLength)
            if (finalPacket && pending.base64Carry.isNotEmpty()) '6' else null
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Invalid Base64 media packet", error)
            '6'
        } catch (error: IOException) {
            Log.w(TAG, "Unable to append decoded media packet", error)
            '6'
        }
    }

    private fun parseDownloadPacketData(rest: String, sizeOffset: Int): String? {
        DOWNLOAD_SIZE_FIELD_WIDTHS.forEach { width ->
            if (sizeOffset + width > rest.length) return@forEach
            val size = rest.substring(sizeOffset, sizeOffset + width).toIntOrNull() ?: return@forEach
            val data = rest.substring(sizeOffset + width)
            if (size in 0..MAX_DOWNLOAD_PACKET_CHARS && data.length == size) return data
        }
        return null
    }

    fun startUpload(fileName: String): MediaUploadPacket {
        clearPendingUpload()
        val normalized = normalizedName(fileName) ?: return MediaUploadPacket.error('2')
        val file = mediaFile(normalized)
        if (!file.isFile || MediaType.fromFileName(normalized) == null) return MediaUploadPacket.error('3')

        return runCatching {
            val encodedFile = File.createTempFile("media-upload-", ".b64", transferDir)
            file.inputStream().use { input ->
                encodedFile.outputStream().use { rawOutput ->
                    Base64.getEncoder().wrap(rawOutput).use { encodedOutput ->
                        input.copyTo(encodedOutput)
                    }
                }
            }
            upload = PendingUpload(encodedFile, sequence = 0, offset = 0)
            nextUploadPacket()
        }.onFailure {
            Log.w(TAG, "Unable to prepare media upload", it)
            clearPendingUpload()
        }.getOrElse { MediaUploadPacket.error('6') }
    }

    fun nextUploadPacket(): MediaUploadPacket {
        val pending = upload ?: return MediaUploadPacket.error('5')
        return runCatching {
            val remaining = pending.encodedFile.length() - pending.offset
            if (remaining <= 0) {
                val packet = MediaUploadPacket(type = '1', sequence = pending.sequence, data = "")
                clearPendingUpload()
                return@runCatching packet
            }

            val size = minOf(MAX_UPLOAD_PACKET_CHARS.toLong(), remaining).toInt()
            val bytes = ByteArray(size)
            RandomAccessFile(pending.encodedFile, "r").use { input ->
                input.seek(pending.offset)
                input.readFully(bytes)
            }
            val last = pending.offset + size >= pending.encodedFile.length()
            val packet = MediaUploadPacket(
                type = if (last) '1' else '0',
                sequence = pending.sequence,
                data = bytes.toString(StandardCharsets.US_ASCII),
            )
            if (last) {
                clearPendingUpload()
            } else {
                pending.sequence += 1
                pending.offset += size
            }
            packet
        }.onFailure {
            Log.w(TAG, "Unable to read media upload packet", it)
            clearPendingUpload()
        }.getOrElse { MediaUploadPacket.error('6') }
    }

    fun playableFile(name: String): MediaPlayResult {
        val normalized = normalizedName(name) ?: return MediaPlayResult('1', null, null)
        val type = MediaType.fromFileName(normalized) ?: return MediaPlayResult('1', null, null)
        val file = mediaFile(normalized)
        return if (file.isFile) {
            MediaPlayResult('0', file.absolutePath, type)
        } else {
            MediaPlayResult('2', null, null)
        }
    }

    fun delete(names: List<String>): List<Char> {
        return names.map { name ->
            val normalized = normalizedName(name)
            when {
                normalized == null || MediaType.fromFileName(normalized) == null -> '1'
                !mediaFile(normalized).isFile -> '2'
                mediaFile(normalized).delete() -> '0'
                else -> '3'
            }
        }
    }

    private fun finishDownload(pending: PendingDownload): Char {
        return try {
            if (!isValidMedia(pending.decodedFile, pending.mediaType)) return '9'

            val target = mediaFile(pending.fileName)
            if (target.exists() && !target.delete()) return '6'
            if (!pending.decodedFile.renameTo(target)) {
                pending.decodedFile.copyTo(target, overwrite = true)
                pending.decodedFile.delete()
            }
            if (target.isFile && target.length() > 0) 'F' else 'A'
        } catch (error: IOException) {
            Log.w(TAG, "Unable to complete media download", error)
            '6'
        }
    }

    private fun normalizedName(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return null
        if (trimmed.any { it == '/' || it == '\\' || it.code < 0x20 }) return null
        return trimmed
    }

    private fun isValidMedia(file: File, mediaType: MediaType): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val header = ByteArray(12)
        val bytesRead = file.inputStream().use { it.read(header) }
        return when (mediaType) {
            MediaType.Mp3 -> bytesRead >= 3 && (
                header.copyOfRange(0, 3).contentEquals(MP3_ID3_HEADER)
                    || bytesRead >= 2
                    && header[0].toInt() and 0xFF == 0xFF
                    && header[1].toInt() and 0xE0 == 0xE0
                )
            MediaType.Mp4 -> bytesRead >= 8 && header.copyOfRange(4, 8).contentEquals(MP4_FTYP_HEADER)
        }
    }

    private fun mediaFile(name: String): File = File(mediaDir, name)

    private fun clearPendingDownload() {
        download?.decodedFile?.delete()
        download = null
    }

    private fun clearPendingUpload() {
        upload?.encodedFile?.delete()
        upload = null
    }

    data class MediaEntry(
        val name: String,
        val type: MediaType,
        val sizeBytes: Long,
    )

    data class MediaPlayResult(
        val status: Char,
        val path: String?,
        val type: MediaType?,
    )

    data class MediaUploadPacket(
        val type: Char,
        val sequence: Int,
        val data: String,
    ) {
        fun payload(): String =
            "$type${"%0${SEQUENCE_FIELD_LENGTH}d".format(sequence)}${"%03d".format(data.length)}$data"

        companion object {
            fun error(type: Char): MediaUploadPacket = MediaUploadPacket(type, 0, "")
        }
    }

    enum class MediaType(val protocolCode: Char) {
        Mp3('3'),
        Mp4('4');

        companion object {
            fun fromFileName(fileName: String): MediaType? = when {
                fileName.endsWith(".mp3", ignoreCase = true) -> Mp3
                fileName.endsWith(".mp4", ignoreCase = true) -> Mp4
                else -> null
            }
        }
    }

    private data class PendingDownload(
        val fileName: String,
        val mediaType: MediaType,
        val decodedFile: File,
        var nextSequence: Int,
        var decodedBytes: Long,
        var base64Carry: String,
    )

    private data class PendingUpload(
        val encodedFile: File,
        var sequence: Int,
        var offset: Long,
    )

    companion object {
        private const val TAG = "PinpadMediaStore"
        private const val STORE_DIR_NAME = "pinpad_media"
        private const val MEDIA_DIR_NAME = "files"
        private const val TRANSFER_DIR_NAME = "transfers"
        private const val MAX_NAME_LENGTH = 64
        private const val MAX_MEDIA_FILES = 50
        private const val SEQUENCE_FIELD_LENGTH = 6
        private const val SIZE_FIELD_LENGTH = 3
        private const val MAX_DOWNLOAD_PACKET_CHARS = 8_192
        private const val MAX_UPLOAD_PACKET_CHARS = 524
        private const val MAX_MP3_BYTES = 16L * 1024 * 1024
        private const val MAX_MP4_BYTES = 64L * 1024 * 1024
        private const val MIN_DOWNLOAD_PAYLOAD_LENGTH =
            1 + SEQUENCE_FIELD_LENGTH + 1 + 2 + SIZE_FIELD_LENGTH
        private const val BASE64_GROUP_LENGTH = 4
        private const val FS = '\u001C'
        private val PACKET_TYPES = setOf('0', '1')
        private val FORCE_VALUES = setOf('0', '1')
        private val DOWNLOAD_SIZE_FIELD_WIDTHS = intArrayOf(4, SIZE_FIELD_LENGTH)
        private val MP3_ID3_HEADER = "ID3".toByteArray(StandardCharsets.US_ASCII)
        private val MP4_FTYP_HEADER = "ftyp".toByteArray(StandardCharsets.US_ASCII)

        private fun maxDecodedBytes(type: MediaType): Long = when (type) {
            MediaType.Mp3 -> MAX_MP3_BYTES
            MediaType.Mp4 -> MAX_MP4_BYTES
        }
    }
}
