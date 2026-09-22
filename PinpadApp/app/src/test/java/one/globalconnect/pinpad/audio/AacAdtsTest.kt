package one.globalconnect.pinpad.audio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AacAdtsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun frame() = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x60, 0x40, 0x01, 0xff.toByte(), 0xfc.toByte()) + ByteArray(8)

    @Test fun recoversCompleteFramesAndMeasuresEncodedDuration() {
        val folder = temporary.newFolder()
        val file = AudioRecordingStore(folder).create()
        assertTrue(file.name.endsWith(".aac"))
        file.writeBytes(frame() + frame() + frame().take(10).toByteArray())
        assertEquals(128L, AacAdts.scan(file).durationMs)
        val recovered = AudioRecordingStore(folder)
        assertEquals(30L, file.length())
        assertEquals(128L, recovered.durationMs(file))
        assertFalse(recovered.validName("../" + file.name))
    }

    @Test fun removesEmptyInterruptedCaptureButPreservesLegacyWav() {
        val folder = temporary.newFolder()
        val empty = AudioRecordingStore(folder).create()
        val wav = File(folder, empty.name.replace(".aac", ".wav"))
        wav.writeBytes(AudioRecordingStore.wavHeader(32000) + ByteArray(32000))
        val store = AudioRecordingStore(folder)
        assertFalse(empty.exists())
        assertEquals(1000L, store.durationMs(wav))
    }

    @Test fun boundsFramesToThirtyMinutesAndRejectsWrongFormat() {
        val file = temporary.newFile()
        file.outputStream().use { out -> repeat(28126) { out.write(frame()) } }
        assertEquals(1_800_000L, AacAdts.scan(file, repair = true).durationMs)
        assertEquals(28125L * 15, file.length())
        file.writeBytes(frame().also { it[2] = 0x50 })
        assertEquals(0L, AacAdts.scan(file).bytes)
    }
}
