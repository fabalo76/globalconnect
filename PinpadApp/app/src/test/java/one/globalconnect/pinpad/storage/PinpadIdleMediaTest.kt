package one.globalconnect.pinpad.storage

import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PinpadIdleMediaTest {
    @Test fun selectionPersistsAndSwitchesBetweenImageVideoAndText() = fixture { jpegs, media, config ->
        val idle = PinpadIdleMedia(jpegs, media, config)
        assertNull(idle.selection)
        assertEquals('0', idle.setJpeg("LOGO"))
        assertNull(idle.selection) // J7 assigns; J8 enables.
        assertEquals('0', idle.enableJpeg('1'))
        assertFalse(idle.selection!!.video)
        assertEquals("LOGO", restarted(config).selection!!.name)
        assertEquals('0', idle.select("promo.mp4", true))
        assertTrue(idle.selection!!.video)
        assertTrue(restarted(config).selection!!.video)
        assertEquals('0', idle.enableJpeg('1'))
        assertFalse(idle.selection!!.video)
        idle.clear()
        assertNull(restarted(config).selection)
    }

    @Test fun rejectsMissingFilesAndAudioWithoutChangingCurrentSelection() = fixture { jpegs, media, config ->
        val idle = PinpadIdleMedia(jpegs, media, config)
        assertEquals('2', idle.enableJpeg('1'))
        assertEquals('1', idle.enableJpeg('x'))
        assertEquals('0', idle.select("LOGO", false))
        val original = idle.selection
        assertEquals('2', idle.select("missing.mp4", true))
        assertEquals('1', idle.select("sound.mp3", true))
        assertEquals('1', idle.setJpeg("../LOGO"))
        assertEquals(original, idle.selection)
        assertEquals(listOf("LOGO", "promo.mp4"), idle.choices().map { it.name })
    }

    @Test fun deletedMediaFallsBackToTextAndDisabledImagesStayDisabled() = fixture { jpegs, media, config ->
        val idle = PinpadIdleMedia(jpegs, media, config)
        idle.select("LOGO", false)
        idle.select("promo.mp4", true)
        media.delete(listOf("promo.mp4"))
        idle.refresh()
        assertNull(idle.selection)
        assertNull(restarted(config).selection)
        idle.enableJpeg('1')
        jpegs.delete(listOf("LOGO"))
        idle.refresh()
        assertNull(idle.selection)
        assertEquals('2', idle.enableJpeg('1'))
    }

    private fun restarted(config: File) = PinpadIdleMedia(
        PinpadJpegStore(File(config.parentFile, "jpeg"), Unit),
        PinpadMediaStore(File(config.parentFile, "media"), Unit),
        config,
    )

    private fun fixture(test: (PinpadJpegStore, PinpadMediaStore, File) -> Unit) {
        val dir = createTempDirectory("idle-media").toFile()
        try {
            val jpegs = PinpadJpegStore(File(dir, "jpeg"), Unit)
            val media = PinpadMediaStore(File(dir, "media"), Unit)
            val data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
            assertEquals('F', jpegs.downloadPacket("10001\u001CLOGO\u001C${"%03d".format(data.length)}$data"))
            val source = File(dir, "source")
            source.writeBytes(byteArrayOf(0, 0, 0, 16) + "ftypisomvideo".toByteArray())
            assertTrue(media.importManagedMedia("promo.mp4", source))
            source.writeBytes("ID3sound".toByteArray())
            assertTrue(media.importManagedMedia("sound.mp3", source))
            test(jpegs, media, File(dir, "idle.properties"))
        } finally { dir.deleteRecursively() }
    }
}
