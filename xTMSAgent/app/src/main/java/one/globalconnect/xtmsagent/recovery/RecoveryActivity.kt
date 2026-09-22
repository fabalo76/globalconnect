package one.globalconnect.xtmsagent.recovery

import android.app.Activity
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.Instant

/** Plain Android UI deliberately avoids the normal application dependency graph. */
class RecoveryActivity : Activity() {
    private lateinit var status: TextView
    private var removingOwner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            textSize = 22f
            text = "xTMSAgent Recovery ${one.globalconnect.xtmsagent.BuildConfig.VERSION_NAME}"
        })
        content.addView(TextView(this).apply {
            text = "Normal TMS and remote control are temporarily disabled. Existing app data and logs are retained. Export the crash log, then choose the factory Home app in Android Settings."
            textSize = 16f
        })
        status = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        fun button(label: String, action: () -> Unit) {
            content.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Export crash log to Downloads") {
            status.text = "Exporting…"
            Thread {
                val message = runCatching { exportCrashLog() }.getOrElse { "Export failed: $it" }
                runOnUiThread { if (!isDestroyed) status.text = message }
            }.start()
        }
        button("Android Settings") { openSettings(Settings.ACTION_SETTINGS) }
        button("Choose Home app") { openSettings(Settings.ACTION_HOME_SETTINGS) }
        if (application is one.globalconnect.xtmsagent.XtmsAgentApplication) {
            button("Retry normal startup") {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Try normal startup?")
                    .setMessage("This starts the normal launcher and TMS services again. If startup fails again, recovery will return on the next launch. Logs and the device-owner removal preference will be kept.")
                    .setNegativeButton("Stay in recovery", null)
                    .setPositiveButton("Retry") { _, _ ->
                        StartupRecoveryGuard.allowOneRetry(applicationContext)
                        (application as one.globalconnect.xtmsagent.XtmsAgentApplication).startNormalStartup()
                        startActivity(Intent(this, StartupActivity::class.java))
                        finish()
                    }.show()
            }
        }
        button("Remove device owner") {
            if (!removingOwner) android.app.AlertDialog.Builder(this)
                .setTitle("Remove device ownership?")
                .setMessage("xTMSAgent will give up device management and stop automatic enrollment. This may release management restrictions. App data and logs will be kept; this does not factory-reset the device. Restoring ownership requires provisioning again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove device owner") { _, _ ->
                    removingOwner = true
                    status.text = "Removing device ownership…"
                    Thread {
                        val result = RecoveryOwnerRemoval.remove(applicationContext)
                        runOnUiThread {
                            removingOwner = false
                            if (!isDestroyed) status.text = result
                        }
                    }.start()
                }.show()
        }
        button("Allow log folder access") {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            }.onFailure { status.text = "Open Android Settings and allow All files access for xTMSAgent: $it" }
        }
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        RecoveryApplication.record(this, "Recovery screen resumed")
        val ownership = runCatching {
            val policy = getSystemService(android.app.admin.DevicePolicyManager::class.java)
            val admin = android.content.ComponentName(packageName, "one.globalconnect.xtmsagent.TmsDeviceAdminReceiver")
            val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo
            val candidates = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            RecoveryApplication.record(this, "Home resolved=${home?.packageName}/${home?.name}; candidates=$candidates")
            "Device owner: ${policy.isDeviceOwnerApp(packageName)}; device admin: ${policy.isAdminActive(admin)}; automatic enrollment disabled: ${RecoveryOwnerRemoval.autoProvisioningDisabled(this)}"
        }.getOrElse { "Device owner state unavailable: $it" }
        RecoveryApplication.record(this, ownership)
        status.text = "$ownership\n${one.globalconnect.xtmsagent.diagnostics.DailyFileLog.sync(this)}"
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { status.text = "Could not open settings: $it" }
    }

    private fun exportCrashLog(): String {
        val report = buildString {
            appendLine("xTMSAgent recovery export ${Instant.now()}")
            appendLine("Current version=${one.globalconnect.xtmsagent.BuildConfig.VERSION_NAME} model=${android.os.Build.MODEL}")
            appendLine("The saved diagnostics snapshot below is historical; it is not refreshed in recovery mode.")
            for (name in listOf("nexgo-diagnostics.txt", "nexgo-events.log", "recovery-events.log")) {
                appendLine("\n--- $name ---")
                val file = File(filesDir, "diagnostics/$name")
                appendLine(runCatching { if (file.isFile) file.readText().takeLast(512 * 1024) else "Not available" }
                    .getOrElse { "Read failed: $it" })
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                appendLine("\n--- Android process exits for this application ---")
                runCatching {
                    getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(packageName, 0, 10).forEach { info ->
                            appendLine("timestamp=${info.timestamp} process=${info.processName} reason=${info.reason} status=${info.status} description=${info.description}")
                            runCatching {
                                info.traceInputStream?.use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var size = 0
                                    while (size < buffer.size) {
                                        val count = input.read(buffer, size, buffer.size - size)
                                        if (count <= 0) break
                                        size += count
                                    }
                                    appendLine(String(buffer, 0, size, Charsets.UTF_8))
                                }
                            }.onFailure { appendLine("Trace unavailable: $it") }
                        }
                }.onFailure { appendLine("Exit history unavailable: $it") }
            }
        }
        val name = "xtmsagent-recovery-${System.currentTimeMillis()}.txt"
        // Keep the exported historical crash evidence in the requested daily log too.
        one.globalconnect.xtmsagent.diagnostics.DailyFileLog.record(this,
            "Recovery export (historical evidence):\n$report")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/xTMSAgent")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = checkNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(report.toByteArray(Charsets.UTF_8)) }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
        return "Saved to Downloads/xTMSAgent/$name"
    }
}
