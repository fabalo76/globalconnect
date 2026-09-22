package one.globalconnect.pinpad.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded release diagnostics. Only connection metadata and byte counts, never wire payloads. */
object ConnectionLog {
    private var directory: File? = null
    private val lock = Any()
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(256),
        ThreadPoolExecutor.DiscardPolicy(),
    )

    fun initialize(context: Context) {
        directory = context.filesDir
        record("START version=${one.globalconnect.pinpad.BuildConfig.VERSION_NAME} model=${android.os.Build.MODEL}")
    }

    fun record(message: String) {
        val dir = directory ?: return
        val line = "${Instant.now()} ${message.replace('\n', ' ').take(600)}\n"
        writer.execute {
            synchronized(lock) {
                runCatching {
                    val file = File(dir, "connection_current.txt")
                    if (file.length() > 256 * 1024) {
                        file.copyTo(File(dir, "connection_previous.txt"), overwrite = true)
                        file.writeText("")
                    }
                    file.appendText(line)
                }.onFailure { Log.w("PinpadConnectionLog", "Connection log unavailable", it) }
            }
        }
    }

    fun exportText(): String = synchronized(lock) {
        val dir = directory ?: return@synchronized "Connection log not initialized."
        listOf("connection_previous.txt", "connection_current.txt").joinToString("") { name ->
            File(dir, name).let { if (it.exists()) it.readText() else "" }
        }
    }
}
