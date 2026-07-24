package one.globalconnect.xtmsagent.mqtt

import kotlinx.coroutines.flow.MutableStateFlow

enum class TmsStatusSeverity {
    CONNECTING,
    CONNECTED,
    WARNING,
    ERROR,
}

data class TmsConnectionStatus(
    val text: String,
    val severity: TmsStatusSeverity,
    val connected: Boolean = false,
)

internal fun shouldShowTmsConnectionStatus(
    status: TmsConnectionStatus,
    launcherConfigApplied: Boolean,
): Boolean = !status.connected &&
    (status.severity == TmsStatusSeverity.ERROR || !launcherConfigApplied)

/**
 * Shared status text for the TMS foreground notification and launcher warning strip.
 */
object TmsTaskStatus {
    val taskOverride = MutableStateFlow<String?>(null)
    val connection = MutableStateFlow(
        TmsConnectionStatus("TMS starting", TmsStatusSeverity.CONNECTING)
    )

    fun connecting(termId: String, detail: String = "Connecting to TMS") {
        connection.value = TmsConnectionStatus("$detail ($termId)", TmsStatusSeverity.CONNECTING)
    }

    fun connected(termId: String) {
        connection.value = TmsConnectionStatus(
            "TMS connected ($termId)",
            TmsStatusSeverity.CONNECTED,
            connected = true
        )
    }

    fun disconnected(message: String) {
        connection.value = TmsConnectionStatus(message, TmsStatusSeverity.WARNING)
    }

    fun terminalNotRegistered(termId: String) {
        connection.value = TmsConnectionStatus(
            "TMS registration error: terminal $termId is not registered",
            TmsStatusSeverity.ERROR
        )
    }

    fun connectionFailed(error: Throwable, nextRetryMs: Long? = null) {
        val causeText = buildCauseText(error)
        val alreadyProvisioned = isIotCertificateAlreadyProvisioned(causeText)
        val base = classify(causeText, error)
        val retry = nextRetryMs
            ?.takeIf { it > 0 && !alreadyProvisioned }
            ?.let { " Retry in ${((it + 999) / 1000)}s." }
            ?: ""
        connection.value = TmsConnectionStatus(base + retry, TmsStatusSeverity.ERROR)
    }

    private fun classify(text: String, error: Throwable): String {
        return when {
            isIotCertificateAlreadyProvisioned(text) ->
                "TMS certificate is already provisioned for this terminal. Contact support for assistance."

            text.contains("not verified", ignoreCase = true) ||
                text.contains("SSLPeerUnverifiedException", ignoreCase = true) ||
                text.contains("certificate", ignoreCase = true) ->
                "TMS security error: certificate mismatch on network"

            text.contains("Unable to resolve host", ignoreCase = true) ||
                text.contains("UnknownHostException", ignoreCase = true) ||
                text.contains("No address associated with hostname", ignoreCase = true) ->
                "TMS network error: cannot resolve server hostname"

            text.contains("Device download secret is missing", ignoreCase = true) ->
                "TMS configuration error: device secret is missing"

            text.contains("401", ignoreCase = true) ||
                text.contains("403", ignoreCase = true) ||
                text.contains("NOT_AUTHORIZED", ignoreCase = true) ->
                "TMS authorization error: device credentials rejected"

            else ->
                "TMS connection error: ${error.message ?: "server unavailable"}"
        }
    }

    private fun buildCauseText(error: Throwable): String {
        val parts = ArrayList<String>()
        var cause: Throwable? = error
        while (cause != null && parts.size < 6) {
            parts.add(cause.javaClass.simpleName)
            cause.message?.let { parts.add(it) }
            cause = cause.cause
        }
        return parts.joinToString(" | ")
    }
}

internal fun isIotCertificateAlreadyProvisioned(causeText: String): Boolean =
    causeText.contains("IOT_CERTIFICATE_ALREADY_PROVISIONED", ignoreCase = true)
