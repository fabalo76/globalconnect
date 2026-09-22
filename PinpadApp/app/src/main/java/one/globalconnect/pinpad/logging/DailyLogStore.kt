package one.globalconnect.pinpad.logging

import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate

/** Today plus nine daily archives. Only files matching our log names are rotated. */
class DailyLogStore(private val directory: File, private val maxBytes: Int = 4 * 1024 * 1024) {
    private val publishedSizes = mutableMapOf<String, Long>()
    private var publishedDirectory: File? = null
    private val dayMarker = File(directory, "current-day")

    init { check(directory.isDirectory || directory.mkdirs()) }

    @Synchronized fun append(date: LocalDate, text: String) {
        val day = date.toString()
        val today = File(directory, "log_today.txt")
        val previousDay = dayMarker.takeIf(File::isFile)?.readText()?.trim()
        if (previousDay != day) {
            if (previousDay?.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) == true && today.isFile) {
                val archived = File(directory, "log_$previousDay.txt")
                today.copyTo(archived, overwrite = true)
                check(today.delete())
                publishedSizes.remove(archived.name)
            }
            dayMarker.writeText(day)
            publishedSizes.remove(today.name)
        }
        archives(directory).dropLast(9).forEach { check(it.delete()) }
        today.appendText(text)
        if (today.length() > maxBytes) {
            val tail = RandomAccessFile(today, "r").use { input ->
                val keep = (maxBytes / 2).coerceAtLeast(1)
                input.seek((input.length() - keep).coerceAtLeast(0))
                ByteArray(minOf(keep.toLong(), input.length()).toInt()).also(input::readFully)
            }.toString(Charsets.UTF_8).substringAfter('\n', "")
            today.writeText("[Earlier entries discarded: daily size limit]\n$tail")
            publishedSizes.remove(today.name)
        }
    }

    @Synchronized fun publish(targetDirectory: File) {
        check(targetDirectory.isDirectory || targetDirectory.mkdirs()) { "Cannot create public log folder" }
        if (publishedDirectory != targetDirectory) { publishedSizes.clear(); publishedDirectory = targetDirectory }
        val sources = archives(directory) + listOf(File(directory, "log_today.txt")).filter(File::isFile)
        val retained = sources.map { it.name }.toSet()
        logs(targetDirectory).filter { it.name !in retained }.forEach { check(it.delete()) }
        for (source in sources) {
            val target = File(targetDirectory, source.name)
            val known = publishedSizes[source.name]
            val offset = if (known != null && target.isFile && target.length() == known && source.length() >= known) known else 0L
            if (offset == source.length() && target.isFile) continue
            // The source remains authoritative if a write or permission grant is interrupted.
            RandomAccessFile(source, "r").use { input ->
                input.seek(offset)
                java.io.FileOutputStream(target, offset > 0).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            publishedSizes[source.name] = source.length()
        }
    }

    private fun archives(root: File): List<File> = root.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("log_[0-9]{4}-[0-9]{2}-[0-9]{2}\\.txt")) }.sortedBy { it.name }
    private fun logs(root: File): List<File> = archives(root) + listOf(File(root, "log_today.txt")).filter(File::isFile)
}
