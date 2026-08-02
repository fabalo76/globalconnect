package one.globalconnect.pinpad.storage

import android.content.Context
import android.util.Log
import android.graphics.BitmapFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

class PinpadJpegStore private constructor(rootDir: File) {
    constructor(context: Context) : this(File(context.filesDir, STORE_DIR_NAME))

    internal constructor(baseDir: File, testMarker: Unit = Unit) : this(baseDir)

    private val storeDir = rootDir.apply { mkdirs() }
    private val imageDir = File(storeDir, IMAGE_DIR_NAME).apply { mkdirs() }
    private val metadataFile = File(storeDir, METADATA_FILE_NAME)
    private val properties = readProperties()
    private var download: PendingDownload? = null
    private var upload: PendingUpload? = null

    fun initialize(): Boolean {
        return runCatching {
            imageDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
            properties.clear()
            saveProperties()
            download = null
            upload = null
            true
        }.onFailure { Log.w(TAG, "Unable to initialize JPEG table", it) }.getOrDefault(false)
    }

    fun importManagedImage(fileName: String, source: File): Boolean {
        val normalized = normalizedName(fileName) ?: return false
        if (!normalized.endsWith(".jpg", true) && !normalized.endsWith(".jpeg", true)) return false
        if (!source.isFile || source.length() == 0L || BitmapFactory.decodeFile(source.absolutePath) == null) {
            return false
        }
        return runCatching {
            val target = imageFile(normalized)
            val staging = File(imageDir, ".$normalized.part")
            source.copyTo(staging, overwrite = true)
            if (target.exists() && !target.delete()) return@runCatching false
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            target.isFile && target.length() == source.length()
        }.onFailure { Log.w(TAG, "Unable to import managed JPEG $normalized", it) }
            .getOrDefault(false)
    }

    fun table(): List<JpegEntry> {
        return imageDir.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .map { file ->
                val name = file.name
                JpegEntry(name = name, selected = selectedNames().contains(name))
            }
            .sortedBy { it.name }
    }

    fun select(control: Char, names: List<String>): List<Char> {
        if (control !in setOf('0', '1') || names.isEmpty()) return listOf('2')
        val selected = selectedNames().toMutableSet()
        val statuses = names.map { name ->
            val normalized = normalizedName(name) ?: return@map '1'
            if (!imageFile(normalized).isFile) return@map '1'
            if (control == '1') selected += normalized else selected -= normalized
            '0'
        }
        setSelectedNames(selected)
        return statuses
    }

    fun delete(names: List<String>): List<Char> {
        if (names.isEmpty()) return listOf('2')
        val selected = selectedNames().toMutableSet()
        val statuses = names.map { name ->
            val normalized = normalizedName(name) ?: return@map '1'
            val deleted = imageFile(normalized).delete()
            if (deleted) selected -= normalized
            if (idleName() == normalized) {
                properties.remove(KEY_IDLE_NAME)
                properties.setProperty(KEY_IDLE_ENABLED, "0")
            }
            if (deleted) '0' else '1'
        }
        setSelectedNames(selected)
        saveProperties()
        return statuses
    }

    fun downloadPacket(payload: String): Char {
        if (payload.length < 7) return '7'
        val pktType = payload[0]
        if (pktType !in setOf('0', '1')) return '1'
        val seqNo = payload.substring(1, 4).toIntOrNull() ?: return '2'
        val force = payload[4]
        if (force !in setOf('0', '1')) return '3'
        val rest = payload.drop(5)
        val firstFs = rest.indexOf(FS)
        val secondFs = if (firstFs >= 0) rest.indexOf(FS, firstFs + 1) else -1
        if (firstFs < 0 || secondFs < 0) return '7'
        val packetName = normalizedName(rest.substring(firstFs + 1, secondFs))
        val fileName = packetName ?: download?.fileName ?: return 'B'
        if (fileName.length > MAX_NAME_LENGTH) return 'C'
        val sizeOffset = secondFs + 1
        val data = parseDownloadPacketData(rest, sizeOffset) ?: return '5'
        if (seqNo == 0) {
            if (force == '0' && imageFile(fileName).isFile) return '4'
            download = PendingDownload(fileName, StringBuilder(data), seqNo + 1)
        } else {
            val pending = download ?: return '2'
            if (pending.fileName != fileName || pending.nextSeqNo != seqNo) return '2'
            pending.base64.append(data)
            pending.nextSeqNo += 1
        }
        return if (pktType == '1') {
            val pending = download ?: return '6'
            if (pending.base64.isEmpty()) return 'A'
            val decoded = decodeBase64(pending.base64.toString()) ?: return '6'
            val file = imageFile(pending.fileName)
            file.writeBytes(decoded)
            download = null
            if (file.length() > 0) 'F' else 'A'
        } else {
            '0'
        }
    }

    private fun parseDownloadPacketData(rest: String, sizeOffset: Int): String? {
        DOWNLOAD_SIZE_FIELD_WIDTHS.forEach { width ->
            if (sizeOffset + width > rest.length) return@forEach
            val size = rest.substring(sizeOffset, sizeOffset + width).toIntOrNull() ?: return@forEach
            val data = rest.substring(sizeOffset + width)
            if (size in 0..MAX_JPEG_DOWNLOAD_CHARS && data.length == size) return data
        }
        return null
    }

    fun startUpload(fileName: String): JpegUploadPacket {
        val normalized = normalizedName(fileName) ?: return JpegUploadPacket.error('2')
        val file = imageFile(normalized)
        if (!file.isFile) return JpegUploadPacket.error('3')
        val encoded = Base64.getEncoder().encodeToString(file.readBytes())
        upload = PendingUpload(encoded, 0)
        return nextUploadPacket()
    }

    fun nextUploadPacket(): JpegUploadPacket {
        val current = upload ?: return JpegUploadPacket.error('5')
        val start = current.seqNo * MAX_JPEG_UPLOAD_CHARS
        if (start >= current.base64.length) {
            upload = null
            return JpegUploadPacket(type = '1', seqNo = current.seqNo, data = "")
        }
        val data = current.base64.substring(start, minOf(start + MAX_JPEG_UPLOAD_CHARS, current.base64.length))
        val last = start + data.length >= current.base64.length
        val packet = JpegUploadPacket(type = if (last) '1' else '0', seqNo = current.seqNo, data = data)
        if (last) {
            upload = null
        } else {
            current.seqNo += 1
        }
        return packet
    }

    fun setIdleLogo(name: String): Char {
        val normalized = normalizedName(name) ?: return '1'
        if (!imageFile(normalized).isFile) return '2'
        properties.setProperty(KEY_IDLE_NAME, normalized)
        saveProperties()
        return '0'
    }

    fun setIdleLogoEnabled(op: Char): Char {
        if (op !in setOf('0', '1')) return '1'
        if (op == '1' && idleName().isNullOrBlank()) return '2'
        properties.setProperty(KEY_IDLE_ENABLED, op.toString())
        saveProperties()
        return '0'
    }

    fun showFile(name: String): JpegShowResult {
        val normalized = normalizedName(name) ?: return JpegShowResult('1', null)
        val file = imageFile(normalized)
        return if (file.isFile) JpegShowResult('0', file.absolutePath) else JpegShowResult('2', null)
    }

    fun selectedFilePaths(): List<String> {
        val selected = selectedNames()
        return table()
            .filter { it.name in selected }
            .map { imageFile(it.name).absolutePath }
    }

    fun enabledIdleLogoPath(): String? {
        if (properties.getProperty(KEY_IDLE_ENABLED, "0") != "1") return null
        val name = idleName() ?: return null
        return imageFile(name).takeIf { it.isFile }?.absolutePath
    }

    fun bootLogoPacket(payload: String): Char {
        if (payload.length < 6) return '3'
        val pktType = payload[0]
        if (pktType !in setOf('0', '1')) return '1'
        val seqNo = payload.substring(1, 4).toIntOrNull() ?: return '2'
        val rest = payload.drop(4)
        if (!rest.startsWith(FS)) return '3'
        if (rest.length < 1 + SIZE_FIELD_LENGTH) return '4'
        val size = rest.substring(1, 1 + SIZE_FIELD_LENGTH).toIntOrNull() ?: return '4'
        if (size !in 0..MAX_BOOT_DOWNLOAD_CHARS) return '4'
        val data = rest.substring(1 + SIZE_FIELD_LENGTH)
        if (data.length != size) return '4'
        val current = if (seqNo == 0) {
            PendingDownload(BOOT_LOGO_NAME, StringBuilder(), 0).also { download = it }
        } else {
            download ?: return '2'
        }
        if (current.nextSeqNo != seqNo) return '2'
        current.base64.append(data)
        current.nextSeqNo += 1
        return if (pktType == '1') {
            val decoded = decodeBase64(current.base64.toString()) ?: return '5'
            imageFile(BOOT_LOGO_NAME).writeBytes(decoded)
            download = null
            'F'
        } else {
            '0'
        }
    }

    private fun normalizedName(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return null
        if (trimmed.any { it == '/' || it == '\\' || it.code < 0x20 }) return null
        return trimmed
    }

    private fun imageFile(name: String): File = File(imageDir, name)

    private fun selectedNames(): Set<String> {
        return properties.getProperty(KEY_SELECTED, "")
            .split(SELECTED_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun setSelectedNames(value: Set<String>) {
        properties.setProperty(KEY_SELECTED, value.sorted().joinToString(SELECTED_SEPARATOR))
        saveProperties()
    }

    private fun idleName(): String? = properties.getProperty(KEY_IDLE_NAME)?.takeIf { it.isNotBlank() }

    private fun decodeBase64(value: String): ByteArray? {
        return runCatching { Base64.getDecoder().decode(value.toByteArray(StandardCharsets.US_ASCII)) }.getOrNull()
    }

    private fun readProperties(): Properties {
        return runCatching {
            Properties().apply {
                if (metadataFile.isFile) metadataFile.inputStream().use(::load)
            }
        }.getOrElse {
            Log.w(TAG, "Unable to read JPEG metadata", it)
            Properties()
        }
    }

    private fun saveProperties() {
        runCatching {
            metadataFile.outputStream().use { output ->
                properties.store(output, "PINPAD JPEG metadata")
            }
        }.onFailure { Log.w(TAG, "Unable to save JPEG metadata", it) }
    }

    data class JpegEntry(val name: String, val selected: Boolean)

    data class JpegShowResult(val status: Char, val path: String?)

    data class JpegUploadPacket(
        val type: Char,
        val seqNo: Int,
        val data: String,
    ) {
        fun payload(): String = "$type${"%03d".format(seqNo)}${"%03d".format(data.length)}$data"

        companion object {
            fun error(type: Char): JpegUploadPacket = JpegUploadPacket(type, 0, "")
        }
    }

    private data class PendingDownload(
        val fileName: String,
        val base64: StringBuilder,
        var nextSeqNo: Int,
    )

    private data class PendingUpload(
        val base64: String,
        var seqNo: Int,
    )

    companion object {
        private const val TAG = "PinpadJpegStore"
        private const val STORE_DIR_NAME = "pinpad_jpegs"
        private const val IMAGE_DIR_NAME = "images"
        private const val METADATA_FILE_NAME = "jpeg.properties"
        private const val KEY_SELECTED = "selected"
        private const val KEY_IDLE_NAME = "idle_name"
        private const val KEY_IDLE_ENABLED = "idle_enabled"
        private const val SELECTED_SEPARATOR = "|"
        private const val MAX_NAME_LENGTH = 15
        private const val SIZE_FIELD_LENGTH = 3
        private const val MAX_JPEG_DOWNLOAD_CHARS = 8_192
        private const val MAX_JPEG_UPLOAD_CHARS = 524
        private const val MAX_BOOT_DOWNLOAD_CHARS = 525
        private const val BOOT_LOGO_NAME = "_boot_logo"
        private const val FS = '\u001C'
        private val DOWNLOAD_SIZE_FIELD_WIDTHS = intArrayOf(4, SIZE_FIELD_LENGTH)
    }
}
