package one.globalconnect.xtmsagent.licensing

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ApplicationLicenseBroker {
    private const val TIMEOUT_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, (String) -> Unit>()

    fun register(requestId: String, callback: (String) -> Unit): Boolean {
        if (pending.putIfAbsent(requestId, callback) != null) return false
        handler.postDelayed({
            resolve(requestId, error(requestId, "LICENSE_REQUEST_TIMEOUT"))
        }, TIMEOUT_MS)
        return true
    }

    fun fail(requestId: String, code: String) {
        resolve(requestId, error(requestId, code))
    }

    fun complete(responseJson: String) {
        val requestId = runCatching { JSONObject(responseJson).optString("requestId") }.getOrDefault("")
        if (requestId.isNotBlank()) resolve(requestId, responseJson)
    }

    fun failAll(code: String) {
        pending.keys.toList().forEach { requestId -> fail(requestId, code) }
    }

    private fun resolve(requestId: String, responseJson: String) {
        val callback = pending.remove(requestId) ?: return
        handler.post {
            runCatching { callback(responseJson) }
        }
    }

    private fun error(requestId: String, code: String) = JSONObject()
        .put("requestId", requestId)
        .put("success", false)
        .put("errorCode", code)
        .toString()
}
