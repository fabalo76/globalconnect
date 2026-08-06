package one.globalconnect.xtmsagent.net

import one.globalconnect.xtmsagent.TMSFunc
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object DeviceApi {
    fun deviceToken(serial: String, secret: String): String {
        if (secret.isBlank()) throw IllegalStateException("Device download secret is missing")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(serial.trim().uppercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun primaryUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl
        }

        val cfg = TMSFunc.tmsCfg
        return "${cfg.webScheme}://${cfg.apiHost.trim()}:${cfg.web_port}$pathOrUrl"
    }

    fun urls(pathOrUrl: String): List<String> {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return listOf(pathOrUrl)
        }

        val cfg = TMSFunc.tmsCfg
        val hosts = listOf(cfg.apiHost, cfg.api_host_fallback)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return hosts.map { host -> "${cfg.webScheme}://$host:${cfg.web_port}$pathOrUrl" }
    }

    fun isRecoverableHostFailure(error: Exception): Boolean =
        error is UnknownHostException ||
            error is SSLException ||
            error is ConnectException ||
            error is SocketTimeoutException
}
