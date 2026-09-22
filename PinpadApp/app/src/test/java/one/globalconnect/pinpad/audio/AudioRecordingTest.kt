package one.globalconnect.pinpad.audio

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class AudioRecordingTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Capture : AudioCaptureSession {
        override var running = true
        override var failure: Throwable? = null
        override fun stop(): Boolean { running = false; return true }
    }

    @Test fun onlyExactN6ProVariantsAreSupported() {
        listOf("N6Pro", "N6 Pro", "N6-PRO", "n6pro", "N6ProLite", "N6 Pro Lite", "N6_PRO_LITE").forEach { assertTrue(AudioRecordingPolicy.supports(it)) }
        listOf(null, "N6", "N6S", "N96", "CT20P", "N6ProOther", "N6ProLiteOther", "UNKNOWN").forEach {
            assertFalse(AudioRecordingPolicy.supports(it))
        }
    }

    @Test fun unsupportedCommandsDoNotAccessStoragePermissionOrMicrophone() {
        val controller = AudioRecordingController({ false }, { error("permission queried") },
            { error("storage created") }, { _ -> error("capture started") })
        (20..25).forEach { assertEquals("U", controller.command("M$it", "anything")) }
    }

    @Test fun permissionDeniedAndUnexpectedParametersDoNotCreateFiles() {
        val controller = AudioRecordingController({ true }, { false }, { error("storage created") }, { _ -> Capture() })
        assertEquals("2", controller.command("M20", ""))
        listOf("0", "1", "2").forEach { assertEquals("1", controller.command("M20", it)) }
    }

    @Test fun backgroundSessionIsBusyUntilStoppedAndResetPreservesFiles() {
        val store = AudioRecordingStore(temporary.newFolder())
        val sessions = mutableListOf<Capture>()
        val controller = AudioRecordingController({ true }, { true }, { store }, { _ ->
            Capture().also { sessions += it }
        })
        val started = controller.command("M20", "")
        val name = started.substringAfter('\u001c')
        assertTrue(started.startsWith("0\u001c"))
        assertTrue(controller.command("M20", "").startsWith("3"))
        assertEquals(1, store.files().size)
        assertEquals("3", controller.command("M24", name))
        assertEquals("3", controller.command("M23", "$name\u001c0"))
        assertTrue(controller.command("M22", "").contains("R|0|0|$name"))
        controller.reset()
        assertFalse(sessions.single().running)
        assertEquals(1, store.files().size)
        assertEquals("5", controller.command("M21", ""))
        assertTrue(controller.command("M22", "").contains("S|0|0|$name"))
        assertEquals("0", controller.command("M24", name))
        assertEquals("4", controller.command("M24", name))
    }

    @Test fun tenRecordingRetentionRemovesExactlyOldestAndDeleteAllStopsCapture() {
        val store = AudioRecordingStore(temporary.newFolder())
        val sessions = mutableListOf<Capture>()
        val controller = AudioRecordingController({ true }, { true }, { store }, { _ -> Capture().also { sessions += it } })
        val names = (1..10).map {
            val name = controller.command("M20", "").substringAfter('\u001c')
            assertEquals("0\u001c$name", controller.command("M21", ""))
            name
        }
        val latest = controller.command("M20", "").substringAfter('\u001c')
        assertEquals(names.drop(1) + latest, store.files().map { it.name })
        assertEquals("0", controller.command("M25", ""))
        assertFalse(sessions.last().running)
        assertTrue(store.files().isEmpty())
    }

    @Test fun packetOffsetsAreRepeatableAndRejectTraversal() {
        val store = AudioRecordingStore(temporary.newFolder())
        val file = store.create()
        val data = ByteArray(16000) { (it % 251).toByte() }
        file.writeBytes(AudioRecordingStore.wavHeader(data.size.toLong()) + data)
        val expected = file.readBytes()
        val actual = mutableListOf<Byte>()
        var offset = 0L
        while (offset < expected.size) {
            val response = store.packet(file.name, offset)
            assertEquals(response, store.packet(file.name, offset))
            val fields = response.split('\u001c')
            assertEquals("0", fields[0]); assertEquals(offset.toString(), fields[1])
            val bytes = Base64.getDecoder().decode(fields[3])
            actual.addAll(bytes.toList()); offset += bytes.size
        }
        assertArrayEquals(expected, actual.toByteArray())
        assertEquals("1", store.packet(file.name, -1))
        assertEquals("1", store.packet(file.name, expected.size.toLong()))
        assertEquals('1', store.delete("../outside.wav"))
        assertEquals("1", store.packet("../outside.wav", 0))
    }

    @Test fun failedStartLeavesNoOrphanAndSlowStopCannotDeleteAnOpenRecording() {
        val store = AudioRecordingStore(temporary.newFolder())
        val failing = AudioRecordingController({ true }, { true }, { store }, { _ -> error("Microphone failed") })
        assertEquals("6", failing.command("M20", ""))
        assertTrue(store.files().isEmpty())
        val slow = object : AudioCaptureSession {
            override val running = true
            override val failure: Throwable? = null
            override fun stop() = false
        }
        val controller = AudioRecordingController({ true }, { true }, { store }, { _ -> slow })
        assertTrue(controller.command("M20", "").startsWith("0"))
        assertEquals("3", controller.command("M21", ""))
        assertEquals("3", controller.command("M25", ""))
        controller.reset()
        assertTrue(controller.command("M20", "").startsWith("3"))
        assertEquals(1, store.files().size)
    }

    @Test fun completeAudioResponseFitsNexgoSerialSdkWriteLimit() {
        val store = AudioRecordingStore(temporary.newFolder())
        val file = store.create()
        java.io.RandomAccessFile(file, "rw").use {
            // Sparse full-duration file exercises both first and largest-offset packets.
            it.setLength(44 + AudioRecordingPolicy.MAX_DATA_BYTES)
        }
        val codec = one.globalconnect.pinpad.protocol.PINPADFrameCodec()
        for (offset in listOf(0L, AudioRecordingPolicy.MAX_DATA_BYTES - AudioRecordingPolicy.CHUNK_BYTES)) {
            val payload = store.packet(file.name, offset)
            val response = codec.encode(one.globalconnect.pinpad.protocol.PINPADFrame(
                one.globalconnect.pinpad.protocol.PINPADFrameType.Transaction, "M23", payload.toByteArray(Charsets.US_ASCII)))
            assertTrue("NEXGO send() rejects ${response.size} bytes", response.size <= 2048)
            val fields = payload.split('\u001c')
            assertEquals("0", fields[0])
            assertEquals(1024, Base64.getDecoder().decode(fields[3]).size)
            assertTrue(codec.decode(response) is one.globalconnect.pinpad.protocol.PINPADFrameCodec.DecodeResult.Valid)
        }
    }

    @Test fun interruptedWavIsRecoveredAndDurationBoundsAreExact() {
        val folder = temporary.newFolder()
        val created = AudioRecordingStore(folder).create()
        val file = File(folder, created.name.replace(".aac", ".wav"))
        assertTrue(created.renameTo(file))
        file.writeBytes(AudioRecordingStore.wavHeader(0) + ByteArray(32000))
        AudioRecordingStore(folder)
        val header = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32000, header.getInt(40))
        assertTrue(AudioRecordingPolicy.canContinue(1_799_999, 57_599_998))
        assertFalse(AudioRecordingPolicy.canContinue(1_800_000, 0))
        assertFalse(AudioRecordingPolicy.canContinue(0, 57_600_000))
    }
}
