package one.globalconnect.pinpad.logging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import one.globalconnect.pinpad.BuildConfig
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Temporary metadata-only production diagnostics; no wire payloads or SDK logcat capture. */
object ProductionLog {
    const val PUBLIC_PATH = "/sdcard/Logs/Pinpad/log_today.txt"
    private data class Entry(val date: LocalDate, val line: String)
    private val queue = ArrayBlockingQueue<Entry>(2048)
    private val dropped = AtomicLong()
    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pinpad-release-log").apply { isDaemon = true }
    }
    @Volatile private var context: Context? = null
    private var store: DailyLogStore? = null
    @Volatile var status = "Preparing release log."
        private set

    @Synchronized fun initialize(value: Context) {
        if (!BuildConfig.TEMPORARY_PRODUCTION_LOG_ENABLED) { status = "Release file logging is disabled."; return }
        if (context != null) return
        context = value.applicationContext
        record("APP", "START version=${BuildConfig.VERSION_NAME} build=${BuildConfig.BUILD_TYPE} " +
            "model=${Build.MODEL} android=${Build.VERSION.RELEASE} firmware=${Build.DISPLAY}")
        writer.scheduleWithFixedDelay(::flush, 0, 1, TimeUnit.SECONDS)
    }

    fun hasPublicAccess(value: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(value, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun record(category: String, message: String) {
        if (!BuildConfig.TEMPORARY_PRODUCTION_LOG_ENABLED || context == null) return
        val now = OffsetDateTime.now()
        val safeLine = message.replace('\n', ' ').replace('\r', ' ').take(1200)
        if (!queue.offer(Entry(now.toLocalDate(), "$now $category $safeLine\n"))) dropped.incrementAndGet()
    }

    fun sync() { if (context != null) writer.execute(::flush) }

    private fun flush() {
        val app = context ?: return
        try {
            val files = store ?: DailyLogStore(File(app.filesDir, "release-logs")).also { store = it }
            val entries = mutableListOf<Entry>()
            queue.drainTo(entries)
            val skipped = dropped.getAndSet(0)
            if (skipped > 0) entries += Entry(LocalDate.now(), "${OffsetDateTime.now()} LOG dropped=$skipped (queue full)\n")
            // Group consecutive dates without moving clock-change events out of order.
            var position = 0
            while (position < entries.size) {
                val date = entries[position].date
                val lines = StringBuilder()
                while (position < entries.size && entries[position].date == date) lines.append(entries[position++].line)
                files.append(date, lines.toString())
            }
            if (hasPublicAccess(app)) {
                files.publish(File(Environment.getExternalStorageDirectory(), "Logs/Pinpad"))
                status = "Release log: $PUBLIC_PATH\nMaximum 10 log files, 4 MiB each."
            } else {
                status = "Log saved inside the app. Allow storage access to publish it at $PUBLIC_PATH."
            }
        } catch (error: Exception) {
            status = "Release log storage error: ${error.javaClass.simpleName}. Check storage access and free space."
            Log.w("PinpadReleaseLog", status, error)
        }
    }
}
