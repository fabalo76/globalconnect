package one.globalconnect.pinpad.audio

import java.io.File
import java.io.RandomAccessFile

/** Reads AAC-LC ADTS headers without decoding audio; drops a partial final frame on recovery. */
object AacAdts {
    data class Info(val bytes: Long, val durationMs: Long)
    fun scan(file: File, repair: Boolean = false): Info = RandomAccessFile(file, if (repair) "rw" else "r").use { input ->
        var position = 0L
        var samples = 0L
        val header = ByteArray(7)
        while (position + 7 <= input.length()) {
            input.seek(position)
            input.readFully(header)
            fun b(index: Int) = header[index].toInt() and 255
            if (b(0) != 255 || b(1) and 0xF6 != 0xF0 ||
                b(2) shr 6 != 1 || (b(2) shr 2) and 15 != 8 ||
                ((b(2) and 1) shl 2 or (b(3) shr 6)) != 1) break
            val length = ((b(3) and 3) shl 11) or (b(4) shl 3) or (b(5) shr 5)
            val headerSize = if (b(1) and 1 == 1) 7 else 9
            val frameSamples = 1024L * ((b(6) and 3) + 1)
            if (length <= headerSize || position + length > input.length() ||
                position + length > AudioRecordingPolicy.MAX_AAC_BYTES ||
                samples + frameSamples > AudioRecordingPolicy.SAMPLE_RATE * AudioRecordingPolicy.MAX_DURATION_MS / 1000) break
            samples += frameSamples
            position += length
        }
        if (repair) { input.setLength(position); input.fd.sync() }
        Info(position, samples * 1000 / AudioRecordingPolicy.SAMPLE_RATE)
    }
}
