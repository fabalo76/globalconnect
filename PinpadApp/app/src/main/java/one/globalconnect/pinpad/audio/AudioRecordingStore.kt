package one.globalconnect.pinpad.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID

object AudioRecordingPolicy {
    const val MAX_RECORDINGS = 10
    const val MAX_DURATION_MS = 30 * 60 * 1000L
    const val SAMPLE_RATE = 16_000
    const val AAC_BIT_RATE = 24_000
    const val MAX_AAC_BYTES = 10 * 1024 * 1024L
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val MAX_DATA_BYTES = MAX_DURATION_MS / 1000 * BYTES_PER_SECOND
    // SerialPortDriver.send rejects frames > 2048 bytes, including Base64 and framing.
    const val CHUNK_BYTES = 1024
    fun canContinue(elapsedMs: Long, dataBytes: Long): Boolean =
        elapsedMs < MAX_DURATION_MS && dataBytes < MAX_DATA_BYTES
    fun supports(model: String?): Boolean {
        // The tested N6 Pro firmware reports N6ProLite in both Build.MODEL and ro.xgd.type.
        val normalized = model.orEmpty().filter(Char::isLetterOrDigit)
        return normalized.equals("N6PRO", ignoreCase = true) || normalized.equals("N6PROLITE", ignoreCase = true)
    }
}

/** Recordings are independent of the host-download media table. All names are generated here. */
class AudioRecordingStore(private val directory: File) {
    private val durations = mutableMapOf<String, Pair<Long, Long>>()
    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create audio recording storage" }
        files().forEach { file ->
            if (file.extension == "aac") {
                if (AacAdts.scan(file, repair = true).bytes == 0L) check(file.delete())
            } else if (file.length() < 44) {
                check(file.delete())
            } else {
                // Recover a playable, bounded WAV after process death during capture.
                RandomAccessFile(file, "rw").use { wav ->
                    val bytes = (wav.length() - 44).coerceAtMost(AudioRecordingPolicy.MAX_DATA_BYTES) / 2 * 2
                    wav.setLength(44 + bytes)
                    wav.write(wavHeader(bytes))
                }
            }
        }
    }

    @Synchronized fun create(): File {
        val existing = files()
        existing.take((existing.size - AudioRecordingPolicy.MAX_RECORDINGS + 1).coerceAtLeast(0)).forEach {
            check(it.delete()) { "Cannot remove oldest recording" }
        }
        check(directory.usableSpace >= AudioRecordingPolicy.MAX_AAC_BYTES + 10 * 1024 * 1024) {
            "Insufficient free space for a 30-minute recording"
        }
        val timestamp = maxOf(System.currentTimeMillis(),
            (existing.lastOrNull()?.name?.substring(6, 19)?.toLongOrNull() ?: 0L) + 1)
        return File(directory, "audio-$timestamp-${UUID.randomUUID()}.aac").also {
            check(it.createNewFile())
        }
    }

    @Synchronized fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && validName(it.name) }.sortedBy { it.name }

    @Synchronized fun durationMs(file: File): Long {
        if (file.extension == "wav") return (file.length() - 44).coerceAtLeast(0) * 1000 / AudioRecordingPolicy.BYTES_PER_SECOND
        val size = file.length()
        durations[file.name]?.takeIf { it.first == size }?.let { return it.second }
        return AacAdts.scan(file).durationMs.also { durations[file.name] = size to it }
    }

    fun validName(name: String): Boolean = NAME.matches(name)
    @Synchronized fun find(name: String): File? =
        if (validName(name)) File(directory, name).takeIf(File::isFile) else null

    @Synchronized fun delete(name: String): Char = when {
        !validName(name) -> '1'
        find(name) == null -> '4'
        find(name)!!.delete() -> '0'
        else -> '6'
    }

    @Synchronized fun deleteAll(): Char {
        var success = true
        files().forEach { if (!it.delete()) success = false }
        return if (success) '0' else '6'
    }

    @Synchronized fun packet(name: String, offset: Long): String {
        if (!validName(name) || offset < 0) return "1"
        val file = find(name) ?: return "4"
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val total = input.length()
                if (offset >= total) return "1"
                input.seek(offset)
                val bytes = ByteArray(minOf(AudioRecordingPolicy.CHUNK_BYTES.toLong(), total - offset).toInt())
                input.readFully(bytes)
                "0\u001c$offset\u001c$total\u001c${Base64.getEncoder().encodeToString(bytes)}"
            }
        }.getOrDefault("6")
    }

    companion object {
        private val NAME = Regex("audio-[0-9]{13}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:wav|aac)")
        fun wavHeader(bytes: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((bytes + 36).toInt()); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(AudioRecordingPolicy.SAMPLE_RATE)
            putInt(AudioRecordingPolicy.BYTES_PER_SECOND); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes.toInt())
        }.array()
    }
}
