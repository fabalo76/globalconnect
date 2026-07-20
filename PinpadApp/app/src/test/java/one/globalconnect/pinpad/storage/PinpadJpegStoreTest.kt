package one.globalconnect.pinpad.storage

import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinpadJpegStoreTest {
    @Test
    fun downloadsSelectsUploadsAndDeletesJpeg() {
        val dir = createTempDirectory("pinpad-jpeg").toFile()
        try {
            val store = PinpadJpegStore(dir, Unit)
            val fs = '\u001C'
            val data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

            assertEquals('F', store.downloadPacket("10001${fs}LOGO${fs}${"%03d".format(data.length)}$data"))
            assertEquals(listOf(PinpadJpegStore.JpegEntry("LOGO", selected = false)), store.table())
            assertEquals(listOf('0'), store.select('1', listOf("LOGO")))
            assertEquals(listOf(PinpadJpegStore.JpegEntry("LOGO", selected = true)), store.table())

            val upload = store.startUpload("LOGO")
            assertEquals('1', upload.type)
            assertEquals(0, upload.seqNo)
            assertEquals(data, upload.data)

            assertEquals(listOf('0'), store.delete(listOf("LOGO")))
            assertTrue(store.table().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidDownloadPacketShape() {
        val dir = createTempDirectory("pinpad-jpeg").toFile()
        try {
            val store = PinpadJpegStore(dir, Unit)

            assertEquals('1', store.downloadPacket("20001\u001CX\u001C000"))
            assertEquals('7', store.downloadPacket("10001BAD"))
            assertEquals('B', store.downloadPacket("10001\u001C\u001C000"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadsBase64SplitAcrossPackets() {
        val dir = createTempDirectory("pinpad-jpeg").toFile()
        try {
            val store = PinpadJpegStore(dir, Unit)
            val fs = '\u001C'
            val data = Base64.getEncoder().encodeToString("hello world".toByteArray())
            val chunks = listOf(data.take(5), data.drop(5).take(5), data.drop(10))

            assertEquals('0', store.downloadPacket("00001${fs}LOGO${fs}${"%03d".format(chunks[0].length)}${chunks[0]}"))
            assertEquals('0', store.downloadPacket("00011${fs}${fs}${"%03d".format(chunks[1].length)}${chunks[1]}"))
            assertEquals('F', store.downloadPacket("10021${fs}${fs}${"%03d".format(chunks[2].length)}${chunks[2]}"))

            assertEquals(listOf(PinpadJpegStore.JpegEntry("LOGO", selected = false)), store.table())
            assertEquals('0', store.setIdleLogo("LOGO"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
