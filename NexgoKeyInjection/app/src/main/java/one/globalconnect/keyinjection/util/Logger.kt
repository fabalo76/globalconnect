package one.globalconnect.keyinjection.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight logger that writes to Android's Log and optionally to a
 * persistent file. File logging can be enabled at runtime and each entry
 * includes timestamp and call site information.
 */
object Logger {
    private var fileLoggingEnabled: Boolean = false
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var fileMinimumLogLevel: Int = Log.DEBUG

    /**
     * Enable logging to a file in the app's external files directory.
     *
     * @param context application context used to resolve the directory
     */
    fun enableFileLogging(context: Context) {
        val dir = context.getExternalFilesDir(null)
        val file = File(dir, "logcat.txt")
        try {
            file.parentFile?.mkdirs()
            file.createNewFile()
            logFile = file
            fileLoggingEnabled = true
            Log.i("Logger", "Logging to file $file")
        } catch (e: IOException) {
            Log.e("Logger", "enableFileLogging: ${e.message}")
        }
    }

    /** Disable logging to file. */
    fun disableFileLogging() {
        fileLoggingEnabled = false
        logFile = null
    }

    /**
     * Set the minimum log level for file output.
     *
     * @param level Android log level constant (e.g., [Log.INFO])
     */
    fun setFileMinimumLogLevel(level: Int) {
        fileMinimumLogLevel = level
    }

    private fun logToFile(priority: Int, tag: String, message: String) {
        if (!fileLoggingEnabled || priority < fileMinimumLogLevel) return
        val file = logFile ?: return
        val element = Throwable().stackTrace.getOrNull(2) ?: return
        val timestamp = dateFormat.format(Date())
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "F"
            else -> "V"
        }
        val line = "$timestamp $level/$tag: $message (${element.fileName}:${element.lineNumber})\n"
        try {
            synchronized(this) { file.appendText(line) }
        } catch (e: IOException) {
            Log.e("Logger", "logToFile: ${e.message}")
        }
    }

    /**
     * Log a debug [message] with [tag].
     *
     * @param tag log tag
     * @param message message to record
     */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        logToFile(Log.DEBUG, tag, message)
    }

    /**
     * Log an informational [message] with [tag].
     *
     * @param tag log tag
     * @param message message to record
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        logToFile(Log.INFO, tag, message)
    }

    /**
     * Log a warning [message] with [tag].
     *
     * @param tag log tag
     * @param message message to record
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        logToFile(Log.WARN, tag, message)
    }

    /**
     * Log an error [message] with [tag]. If [throwable] is provided, its stack
     * trace is also written.
     *
     * @param tag log tag
     * @param message message to record
     * @param throwable optional exception associated with the error
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val msg = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
        logToFile(Log.ERROR, tag, msg)
    }
}

/** Convert a byte array to a space separated hexadecimal string. */
fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
