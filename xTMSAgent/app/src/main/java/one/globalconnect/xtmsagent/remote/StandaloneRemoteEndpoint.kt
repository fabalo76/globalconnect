package one.globalconnect.xtmsagent.remote

import java.net.URI

object StandaloneRemoteEndpoint {
    fun parse(value: String, host: String, sessionId: String): Pair<String, String> {
        val endpoint = URI(value)
        require(endpoint.scheme == "wss" && endpoint.host.equals(host, true) &&
            endpoint.userInfo == null && endpoint.port in listOf(-1, 443) && endpoint.query == null &&
            endpoint.path == "/standalone/remote/$sessionId/master") {
            "REMOTE_ENDPOINT_INVALID: scheme=${endpoint.scheme}, host=${endpoint.host}, expectedHost=$host, port=${endpoint.port}, path=${endpoint.path}, session=$sessionId"
        }
        require(endpoint.fragment?.startsWith("token=") == true) { "REMOTE_TOKEN_INVALID" }
        val token = endpoint.fragment!!.removePrefix("token=")
        require(Regex("[0-9a-fA-F]{64}").matches(token)) { "REMOTE_TOKEN_INVALID" }
        val clean = URI(endpoint.scheme, null, endpoint.host, endpoint.port, endpoint.path, null, null)
        return clean.toString() to token
    }
}
