package one.globalconnect.pinpad.audio

import java.io.File

interface AudioCaptureSession {
    val running: Boolean
    val failure: Throwable?
    fun stop(): Boolean
}

/** Serialized lifecycle operations; the capture worker never needs this monitor to finish. */
class AudioRecordingController(
    private val supported: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val storeFactory: () -> AudioRecordingStore,
    private val capture: (File) -> AudioCaptureSession,
) {
    private val store by lazy(storeFactory)
    private var session: AudioCaptureSession? = null
    private var activeFile: File? = null

    @Synchronized fun command(command: String, payload: String): String {
        if (!supported()) return "U"
        return try {
            when (command) {
                "M20" -> start(payload)
                "M21" -> if (payload.isEmpty()) stop() else "1"
                "M22" -> if (payload.isEmpty()) list() else "1"
                "M23" -> {
                    val parts = payload.split('\u001c')
                    val offset = parts.getOrNull(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
                    when {
                        parts.size != 2 || offset == null -> "1"
                        session?.running == true && activeFile?.name == parts[0] -> "3"
                        else -> store.packet(parts[0], offset)
                    }
                }
                "M24" -> if (session?.running == true && activeFile?.name == payload) "3" else store.delete(payload).toString()
                "M25" -> {
                    if (payload.isNotEmpty()) "1"
                    else if (session?.running == true && session?.stop() != true) "3"
                    else {
                        session = null
                        activeFile = null
                        store.deleteAll().toString()
                    }
                }
                else -> "1"
            }
        } catch (_: SecurityException) { "2" } catch (_: Exception) { "6" }
    }

    private fun start(payload: String): String {
        if (payload.isNotEmpty()) return "1"
        if (!permissionGranted()) return "2"
        if (session?.running == true) return "3\u001c${activeFile?.name}"
        session = null
        activeFile = null
        val file = store.create()
        try {
            session = capture(file)
            activeFile = file
            return "0\u001c${file.name}"
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun stop(): String {
        val current = session ?: return "5"
        if (!current.stop()) return "3"
        val result = "${if (current.failure == null) '0' else '6'}\u001c${activeFile?.name}"
        session = null
        activeFile = null
        return result
    }

    private fun list(): String = "0" + store.files().joinToString("") { file ->
        val current = file == activeFile
        val state = if (current && session?.running == true) "R" else if (current && session?.failure != null) "F" else "S"
        val size = file.length()
        "\u001c$state|$size|${store.durationMs(file)}|${file.name}"
    }

    @Synchronized fun reset() {
        session?.stop()
        // A slow device stays busy until its worker has actually finalized the audio.
        if (session?.running != true) { session = null; activeFile = null }
    }
}
