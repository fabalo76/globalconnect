package one.globalconnect.xtmsagent.requirements

import android.os.Build
import android.util.Log
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.net.DeviceApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ApplicationRequirementManager {
    fun request(
        requestId: String,
        capability: String,
        requesterPackage: String,
    ): String {
        val cfg = TMSFunc.tmsCfg
        val serial = cfg.sn.ifBlank { MainActivity.vg_sSN }
        val body = JSONObject()
            .put("requestId", requestId)
            .put("capability", capability)
            .put("requesterPackage", requesterPackage)
            .put("modelName", Build.MODEL)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .toString()
        val token = DeviceApi.deviceToken(serial, cfg)
        val path = "/v1/devices/${urlEncode(serial)}/application-requirements"
        var lastFailure: Exception? = null

        for (url in DeviceApi.urls(path)) {
            try {
                Log.i(TAG, "Application requirement requestId=$requestId capability=$capability host=${URL(url).host}")
                return post(url, token, body).also {
                    Log.i(TAG, "Application requirement requestId=$requestId completed")
                }
            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "Application requirement requestId=$requestId host=${URL(url).host} failed",
                    exception,
                )
                if (!DeviceApi.isRecoverableHostFailure(exception)) throw exception
                lastFailure = exception
            }
        }
        throw lastFailure ?: IllegalStateException("Application requirement endpoint is unavailable")
    }

    private fun post(url: String, token: String, body: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Device $token")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.doInput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseBody = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Application requirement HTTP ${connection.responseCode}: $responseBody",
                )
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val TAG = "AppRequirementManager"
}
