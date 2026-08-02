package one.globalconnect.pinpad.comms

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.globalconnect.pinpad.logging.PinpadTraceLog

enum class SerialConnectionState {
    Stopped,
    Opening,
    Open,
    Error,
}

data class SerialDiagnosticEvent(
    val timestampMs: Long,
    val direction: String,
    val summary: String,
)

data class SerialDiagnosticsSnapshot(
    val connectionState: SerialConnectionState = SerialConnectionState.Stopped,
    val endpoint: String = "",
    val error: String? = null,
    val events: List<SerialDiagnosticEvent> = emptyList(),
)

object PinpadSerialDiagnostics {
    private val mainHandler = Handler(Looper.getMainLooper())

    var snapshot: SerialDiagnosticsSnapshot by mutableStateOf(SerialDiagnosticsSnapshot())
        private set

    fun opening(endpoint: String) {
        update {
            SerialDiagnosticsSnapshot(
                connectionState = SerialConnectionState.Opening,
                endpoint = endpoint,
            )
        }
    }

    fun opened(endpoint: String) {
        update {
            it.copy(
                connectionState = SerialConnectionState.Open,
                endpoint = endpoint,
                error = null,
            )
        }
    }

    fun stopped() {
        update {
            it.copy(
                connectionState = SerialConnectionState.Stopped,
                error = null,
            )
        }
    }

    fun failed(endpoint: String, error: Throwable) {
        val message = error.message
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_ERROR_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: error.javaClass.simpleName
        update {
            it.copy(
                connectionState = SerialConnectionState.Error,
                endpoint = endpoint.ifBlank { it.endpoint },
                error = message,
            )
        }
    }

    fun received(bytes: ByteArray) {
        traffic("RX", bytes)
    }

    fun transmitted(bytes: ByteArray) {
        traffic("TX", bytes)
    }

    private fun traffic(direction: String, bytes: ByteArray) {
        val event = SerialDiagnosticEvent(
            timestampMs = System.currentTimeMillis(),
            direction = direction,
            summary = "${PinpadTraceLog.diagnosticWireSummary(direction, bytes)} • ${bytes.size} byte(s)",
        )
        update { current ->
            current.copy(
                connectionState = if (direction == "RX") SerialConnectionState.Open else current.connectionState,
                error = if (direction == "RX") null else current.error,
                events = (current.events + event).takeLast(MAX_EVENTS),
            )
        }
    }

    private fun update(block: (SerialDiagnosticsSnapshot) -> SerialDiagnosticsSnapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            snapshot = block(snapshot)
        } else {
            mainHandler.post { snapshot = block(snapshot) }
        }
    }

    private const val MAX_EVENTS = 5
    private const val MAX_ERROR_CHARS = 180
}
