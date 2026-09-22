package one.globalconnect.xtmsagent.diagnostics

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Optional supplemental output. Persistent setup events do not depend on logcat access. */
object SetupLogcatCapture {
    fun capture(context: Context, attemptId: String) {
        val output = File.createTempFile("setup-logcat-", ".txt", context.cacheDir)
        var process: Process? = null
        try {
            NexgoDiagnosticsManager.record(context,
                "retry=$attemptId logcat.begin scope=app_permissions_only vendorLogsMayBeUnavailable=true " +
                    "window=last_200_lines_may_include_earlier_attempts")
            // Fixed tags only: no broad capture of transaction/network logs or root-auth material.
            process = ProcessBuilder("/system/bin/logcat", "-d", "-t", "200", "-v", "threadtime",
                "GeneralMethodImp:D", "DevicePolicyManager:D", "RCAccessibilitySetup:D", "*:S")
                .redirectErrorStream(true).redirectOutput(output).start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
            var lines = 0
            output.bufferedReader().use { reader ->
                while (lines < 200) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        NexgoDiagnosticsManager.record(context, "retry=$attemptId logcat.output $line")
                        lines++
                    }
                }
            }
            NexgoDiagnosticsManager.record(context,
                "retry=$attemptId logcat.end completed=$completed " +
                    "exitCode=${if (process.isAlive) "unavailable" else process.exitValue()} lines=$lines " +
                    "note=empty_or_successful_capture_does_not_prove_access_to_PSS_logs")
        } catch (error: Exception) {
            NexgoDiagnosticsManager.recordException(context, "retry=$attemptId logcat", error)
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            output.delete()
        }
    }
}
