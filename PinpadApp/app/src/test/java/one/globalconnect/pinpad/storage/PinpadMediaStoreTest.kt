package one.globalconnect.pinpad.storage

import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinpadMediaStoreTest {
    @Test
    fun downloadsListsUploadsPlaysAndInitializesMedia() {
        val dir = createTempDirectory("pinpad-media").toFile()
        try {
            val store = PinpadMediaStore(dir, Unit)
            val mp3 = "ID3sample-audio".toByteArray()
            val mp4 = byteArrayOf(0, 0, 0, 16) + "ftypisomvideo".toByteArray()

            assertEquals('F', download(store, "welcome.mp3", mp3))
            assertEquals('F', download(store, "promo.mp4", mp4))
            assertEquals(
                listOf(
                    PinpadMediaStore.MediaEntry("promo.mp4", PinpadMediaStore.MediaType.Mp4, mp4.size.toLong()),
                    PinpadMediaStore.MediaEntry("welcome.mp3", PinpadMediaStore.MediaType.Mp3, mp3.size.toLong()),
                ),
                store.table(),
            )

            val playable = store.playableFile("promo.mp4")
            assertEquals('0', playable.status)
            assertEquals(PinpadMediaStore.MediaType.Mp4, playable.type)
            assertTrue(playable.path?.endsWith("promo.mp4") == true)

            assertContentEquals(mp3, upload(store, "welcome.mp3"))
            assertEquals(
                listOf('0', '2', '1'),
                store.delete(listOf("welcome.mp3", "missing.mp4", "invalid.txt")),
            )
            assertEquals(listOf("promo.mp4"), store.table().map { it.name })
            assertTrue(store.initialize())
            assertTrue(store.table().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsupportedExtensionsAndInvalidMediaContent() {
        val dir = createTempDirectory("pinpad-media").toFile()
        try {
            val store = PinpadMediaStore(dir, Unit)

            assertEquals('C', download(store, "image.jpg", "not-media".toByteArray()))
            assertEquals('9', download(store, "invalid.mp3", "not-an-mp3".toByteArray()))
            assertEquals('9', download(store, "invalid.mp4", "not-an-mp4".toByteArray()))
            assertTrue(store.table().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun acceptsMediaSplitAcrossMultiplePackets() {
        val dir = createTempDirectory("pinpad-media").toFile()
        try {
            val store = PinpadMediaStore(dir, Unit)
            val media = "ID3".toByteArray() + ByteArray(2_048) { (it % 251).toByte() }

            assertEquals('F', download(store, "long.mp3", media, packetChars = 317))
            assertContentEquals(media, upload(store, "long.mp3"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun accepts8192CharacterPacketsAndDecodesIncrementally() {
        val dir = createTempDirectory("pinpad-media-8192").toFile()
        try {
            val store = PinpadMediaStore(dir, Unit)
            val media = "ID3".toByteArray() + ByteArray(7_000) { (it % 251).toByte() }

            assertEquals('F', download(store, "large.mp3", media))
            assertContentEquals(media, upload(store, "large.mp3"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun acceptsLegacyThreeDigitPacketSize() {
        val dir = createTempDirectory("pinpad-media-legacy").toFile()
        try {
            val store = PinpadMediaStore(dir, Unit)
            val media = "ID3legacy-audio".toByteArray()

            assertEquals(
                'F',
                download(store, "legacy.mp3", media, packetChars = 317, sizeFieldWidth = 3),
            )
            assertContentEquals(media, upload(store, "legacy.mp3"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun download(
        store: PinpadMediaStore,
        name: String,
        bytes: ByteArray,
        packetChars: Int = 1_024,
        sizeFieldWidth: Int = 4,
    ): Char {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val chunks = encoded.chunked(packetChars)
        var status = '0'
        chunks.forEachIndexed { index, data ->
            val packetType = if (index == chunks.lastIndex) '1' else '0'
            val packetName = if (index == 0) name else ""
            val payload = buildString {
                append(packetType)
                append("%06d".format(index))
                append('1')
                append(FS)
                append(packetName)
                append(FS)
                append("%0${sizeFieldWidth}d".format(data.length))
                append(data)
            }
            status = store.downloadPacket(payload)
            if (index < chunks.lastIndex) assertEquals('0', status)
        }
        return status
    }

    private fun upload(store: PinpadMediaStore, name: String): ByteArray {
        val encoded = StringBuilder()
        var packet = store.startUpload(name)
        while (true) {
            assertEquals(10 + packet.data.length, packet.payload().length)
            encoded.append(packet.data)
            if (packet.type == '1') break
            assertEquals('0', packet.type)
            packet = store.nextUploadPacket()
        }
        return Base64.getDecoder().decode(encoded.toString())
    }

    companion object {
        private const val FS = '\u001C'
    }
}
