package one.globalconnect.xtmsagent.remote

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import one.globalconnect.logging.DeviceLogStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object DeviceLogCommands {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    private val completed = linkedMapOf<String, String>()
    fun handle(context: Context, request: JSONObject) {
        val id = request.optString("requestId")
        if (runCatching { UUID.fromString(id) }.isFailure) return
        val expiry = request.optLong("expiresAt")
        if (expiry !in System.currentTimeMillis()..(System.currentTimeMillis() + 11 * 60000)) return
        runCatching { worker.execute {
            if (System.currentTimeMillis() > expiry) return@execute
            val result = completed[id]?.let(::JSONObject) ?: JSONObject().apply {
                put("logRequestId", id)
                try {
                    when (request.getString("operation")) {
                        "enable" -> {
                            val pkg = request.getString("packageName")
                            require(compatible(context).contains(pkg)) { "LOG_APP_UNSUPPORTED" }
                            val hours = request.getInt("hours")
                            require(hours in 1..72) { "LOG_DURATION_INVALID" }
                            val response = control(context, pkg, "enable", hours, request.getLong("loggingExpiresAt"))
                            require(response.getBoolean("ok")) { response.getString("code") ?: "LOG_APP_UNSUPPORTED" }
                            put("expiresAt", response.getLong("expiresAt"))
                            put("packages", JSONArray().put(pkg))
                        }
                        "stop" -> {
                            val stopped = JSONArray(); val failures = JSONArray()
                            compatible(context).forEach { pkg ->
                                if (runCatching { control(context, pkg, "stop").getBoolean("ok") }.getOrDefault(false)) stopped.put(pkg)
                                else failures.put(pkg)
                            }
                            put("packages", stopped)
                            put("failedPackages", failures)
                            require(failures.length() == 0) { "LOG_STOP_FAILED" }
                        }
                        "collect" -> {
                            val uploaded = JSONArray(); val unsupported = JSONArray(); val failed = JSONArray()
                            val slots = request.getJSONArray("uploads")
                            require(slots.length() <= 32) { "LOG_TOO_MANY_APPS" }
                            val apps = compatible(context)
                            for (i in 0 until slots.length()) {
                                val slot = slots.getJSONObject(i); val pkg = slot.getString("packageName")
                                if (!apps.contains(pkg)) { unsupported.put(pkg); continue }
                                try {
                                val response = control(context, pkg, "collect")
                                require(response.getBoolean("ok")) { "LOG_COLLECTION_FAILED" }
                                val uri = Uri.parse(response.getString("logsUri"))
                                require(uri.scheme == "content" && uri.authority == "$pkg.device-logs" && uri.path == "/collected") { "LOG_INVALID_RESPONSE" }
                                val temp = File.createTempFile("collected-logs-", ".gz", context.cacheDir)
                                try {
                                    context.contentResolver.openInputStream(uri).use { input ->
                                        requireNotNull(input) { "LOG_COLLECTION_FAILED" }
                                        temp.outputStream().use { output ->
                                            val buffer = ByteArray(8192); var total = 0L
                                            while (true) { val n = input.read(buffer); if (n < 0) break
                                                total += n; require(total <= 5 * 1024 * 1024) { "LOG_TOO_LARGE" }; output.write(buffer, 0, n) }
                                        }
                                    }
                                    temp.inputStream().use { RemoteFileCommands.put(slot.getString("uploadUrl"), "application/gzip", it, temp.length()) }
                                    uploaded.put(pkg)
                                } finally { temp.delete(); runCatching { control(context, pkg, "release") } }
                                } catch (_: Exception) { failed.put(pkg) }
                            }
                            put("packages", uploaded); put("unsupported", unsupported)
                            put("failedPackages", failed)
                            require(uploaded.length() > 0) { if (failed.length() > 0) "LOG_COLLECTION_FAILED" else "LOG_APP_UNSUPPORTED" }
                        }
                        else -> error("LOG_OPERATION_INVALID")
                    }
                    put("ok", true)
                } catch (e: Exception) { put("ok", false); put("code", e.message?.takeIf { it.startsWith("LOG_") } ?: "LOG_COLLECTION_FAILED") }
            }.also { completed[id] = it.toString(); if (completed.size > 64) completed.remove(completed.keys.first()) }
            runCatching { val bytes = result.toString().toByteArray()
                RemoteFileCommands.put(request.getString("resultUrl"), "application/json", bytes.inputStream(), bytes.size.toLong()) }
        } }
    }

    @Suppress("DEPRECATION")
    private fun compatible(context: Context): Set<String> = context.packageManager.queryBroadcastReceivers(Intent(DeviceLogStore.ACTION), 0)
        .filter { it.activityInfo.permission == DeviceLogStore.PERMISSION &&
            context.packageManager.checkSignatures(context.packageName, it.activityInfo.packageName) == PackageManager.SIGNATURE_MATCH }
        .map { it.activityInfo.packageName }.toSet()

    private fun control(context: Context, pkg: String, operation: String, hours: Int = 0, loggingExpiresAt: Long = 0): Bundle {
        val latch = CountDownLatch(1); var response = Bundle()
        context.sendOrderedBroadcast(Intent(DeviceLogStore.ACTION).setPackage(pkg).setComponent(ComponentName(pkg, "one.globalconnect.logging.DeviceLogControlReceiver"))
            .putExtra("senderPackage", context.packageName).putExtra("operation", operation).putExtra("hours", hours).putExtra("loggingExpiresAt", loggingExpiresAt),
            DeviceLogStore.PERMISSION, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { response = getResultExtras(false) ?: Bundle(); latch.countDown() }
            }, Handler(Looper.getMainLooper()), 0, null, null)
        require(latch.await(20, TimeUnit.SECONDS)) { "LOG_APP_TIMEOUT" }
        require(response.getString("packageName") == pkg) { "LOG_INVALID_RESPONSE" }
        return response
    }
}
