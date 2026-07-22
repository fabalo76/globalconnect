package one.globalconnect.paymentapp.records

import android.util.Log
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val LOG_FILE_PATH = "debug_log.txt"
private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"

private const val LOG_DELIMITER = "\n[END_LOG_ENTRY]\n"
private const val DATE_DELIMITER = "[END_TIMESTAMP]\n"
private const val TXN_DELIMITER = "[END_TXN]\n"
private const val DETAILS_DELIMITER = "[END_PROCINFO]\n"
private const val ERROR_MESSAGE_DELIMITER = "[END_ERR_MSG]"

fun writeToDebugLog(procInfo: ProcInfo?, transaction: Transaction, errMessage: String) {
    val logFile: File = File(GlobalConnectPaymentApplication.instance.filesDir, LOG_FILE_PATH)
    if (!logFile.exists()) {
        try {
            logFile.createNewFile()
        } catch (e: IOException) {
            println(e.message)
            return
        } catch (securityException: SecurityException) {
            println(securityException.message)
            return
        }
    }

    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_FORMAT))
    logFile.appendText("$timestamp $DATE_DELIMITER")
    logFile.appendText("$transaction $TXN_DELIMITER")
    procInfo?.let {
        logFile.appendText("$it $DETAILS_DELIMITER")
    }
    logFile.appendText("$errMessage $ERROR_MESSAGE_DELIMITER")
    logFile.appendText(LOG_DELIMITER)
}

fun readDebugLog() {
    val logFile: File = File(GlobalConnectPaymentApplication.instance.filesDir, LOG_FILE_PATH)
    try {
        val fileContent = logFile.readText().split(LOG_DELIMITER)
        for (entry in fileContent) {
            Log.d("DebugLog", entry)
        }
    } catch (e: IOException) {
        Log.e("DebugLog", "Error reading log file: ${e.message}")
    }

}

fun deleteOldLogEntries() {
    val logFile: File = File(GlobalConnectPaymentApplication.instance.filesDir, LOG_FILE_PATH)
    if (!logFile.exists()) {
        println("Debug log file does not exist")
        return
    }
    val currentDateTime = LocalDateTime.now()
    val cutoffDateTime = currentDateTime.minusMonths(6)
    val dateFormat = DateTimeFormatter.ofPattern(DATE_FORMAT)

    val tempLogFile = File("${logFile.absolutePath}.temp")

    try {
        val fileContent = logFile.readText()
        val logEntries = fileContent.split(LOG_DELIMITER)

        tempLogFile.bufferedWriter().use { writer ->
            for (entry in logEntries) {
                val timeStampString = entry.substringBefore(DATE_DELIMITER).trim()
                try {
                    val timestamp = LocalDateTime.parse(timeStampString, dateFormat)
                    if (timestamp != null && timestamp.isAfter(cutoffDateTime)) {
                        writer.write(entry)
                        writer.newLine()
                    } else {
                        // Stop deletion process when newer log entry is encountered
                        break
                    }
                } catch (e: Exception) {
                    println("Error processing log entry: $entry")
                }
            }
        }

        tempLogFile.renameTo(logFile)
    } catch (e: IOException) {
        println("Error reading or writing log file: ${e.message}")
    }
}

