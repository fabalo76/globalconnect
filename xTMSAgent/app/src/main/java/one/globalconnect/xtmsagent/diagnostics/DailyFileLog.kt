package one.globalconnect.xtmsagent.diagnostics

import android.content.Context
import android.os.Environment
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/** Diagnostic events only; independent of the SDK and normal launcher. */
object DailyFileLog {
    @Synchronized fun record(context: Context, message: String) {
        runCatching {
            val date = LocalDate.now().toString()
            val root = File(context.filesDir, "daily-logs")
            root.mkdirs()
            val source = File(root, "log_$date.txt")
            source.appendText("${ZonedDateTime.now()} ${message.take(64 * 1024)}\n")
            runCatching { publish(root, publicDirectory(), date) }
        }
    }

    @Synchronized fun sync(context: Context): String = runCatching {
        val root = File(context.filesDir, "daily-logs")
        val date = LocalDate.now().toString()
        publish(root, publicDirectory(), date)
        "/sdcard/xTMSAgent/log_today.txt"
    }.getOrElse { "Log saved privately. Public log unavailable: ${it.message ?: it.javaClass.simpleName}" }

    private fun publicDirectory(): File {
        check(android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            "Allow All files access, then return to xTMSAgent."
        }
        return File(Environment.getExternalStorageDirectory(), "xTMSAgent")
    }

    internal fun publish(privateDirectory: File, publicDirectory: File, date: String) {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        val source = File(privateDirectory, "log_$date.txt")
        check(source.isFile) { "No log entries yet" }
        check(publicDirectory.isDirectory || publicDirectory.mkdirs()) { "Cannot create log directory" }
        val marker = File(privateDirectory, "published-date.txt")
        val sameDay = marker.isFile && marker.readText() == date
        mirror(source, File(publicDirectory, "log_$date.txt"), true)
        mirror(source, File(publicDirectory, "log_today.txt"), sameDay)
        marker.writeText(date)
    }

    private fun mirror(source: File, destination: File, appendAllowed: Boolean) {
        val offset = if (appendAllowed && destination.isFile && destination.length() <= source.length())
            destination.length() else 0L
        java.io.RandomAccessFile(source, "r").use { input ->
            input.seek(offset)
            java.io.FileOutputStream(destination, offset > 0).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                // Closing flushes the stream. Avoid forcing two storage syncs per
                // setup event on the launcher thread; that can stall slow terminals.
            }
        }
    }
}
