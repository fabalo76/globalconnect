package one.globalconnect.pinpad.storage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Properties

/** Idle configuration is separate from the current transaction display. */
class PinpadIdleMedia(
    private val jpegs: PinpadJpegStore,
    private val media: PinpadMediaStore,
    private val config: File,
) {
    data class Selection(
        val name: String,
        val path: String,
        val video: Boolean,
        val modified: Long = File(path).lastModified(),
        val size: Long = File(path).length(),
    )

    private var videoName: String? = runCatching {
        Properties().apply { if (config.isFile) config.inputStream().use { load(it) } }
            .getProperty("video")
    }.getOrNull()

    var selection: Selection? by mutableStateOf(null)
        private set

    init { refresh() }

    fun choices(): List<Selection> = jpegs.table().mapNotNull {
        jpegs.showFile(it.name).path?.let { path -> Selection(it.name, path, false) }
    } + media.table().filter { it.type == PinpadMediaStore.MediaType.Mp4 }.mapNotNull {
        media.playableFile(it.name).path?.let { path -> Selection(it.name, path, true) }
    }

    fun select(name: String, video: Boolean): Char {
        if (!video) {
            val status = jpegs.setIdleLogo(name)
            return if (status == '0') enableJpeg('1') else status
        }
        val file = media.playableFile(name)
        if (file.status != '0') return file.status
        if (file.type != PinpadMediaStore.MediaType.Mp4) return '1'
        saveVideo(name)
        jpegs.setIdleLogoEnabled('0')
        refresh()
        return '0'
    }

    fun setJpeg(name: String): Char = jpegs.setIdleLogo(name).also { refresh() }

    fun enableJpeg(op: Char): Char = jpegs.setIdleLogoEnabled(op).also {
        if (it == '0') { saveVideo(null); refresh() }
    }

    fun clear() { enableJpeg('0') }

    fun refresh() {
        val video = videoName?.let { media.playableFile(it) }
        selection = if (video?.path != null && video.type == PinpadMediaStore.MediaType.Mp4) {
            Selection(videoName!!, video.path, true)
        } else {
            jpegs.enabledIdleLogoPath()?.let { Selection(File(it).name, it, false) }
        }
    }

    private fun saveVideo(name: String?) {
        config.parentFile?.mkdirs()
        val properties = Properties().apply { if (name != null) setProperty("video", name) }
        config.outputStream().use { properties.store(it, "Pinpad idle video") }
        videoName = name
    }
}
