package one.globalconnect.xtmsagent.net

import one.globalconnect.xtmsagent.TMSFunc
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object DeviceApi {
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
