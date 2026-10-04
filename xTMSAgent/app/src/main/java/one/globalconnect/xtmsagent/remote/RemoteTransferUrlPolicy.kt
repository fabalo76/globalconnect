package one.globalconnect.xtmsagent.remote

import java.net.URL

internal object RemoteTransferUrlPolicy {
    fun isAllowed(url: URL, selectedHost: String, standalone: Boolean): Boolean {
        if (url.protocol != "https" || url.port !in listOf(-1, 443) || url.userInfo != null || url.ref != null) return false
        if (url.host.endsWith(".amazonaws.com", ignoreCase = true)) return !standalone
        return standalone && url.host.equals(selectedHost, ignoreCase = true) && url.path == "/standalone/objects"
    }
}
