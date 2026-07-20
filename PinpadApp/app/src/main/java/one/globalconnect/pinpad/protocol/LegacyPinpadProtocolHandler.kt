package one.globalconnect.pinpad.protocol

import one.globalconnect.pinpad.logging.PinpadTraceLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LegacyPinpadProtocolHandler(
    private val parser: PINPADStreamParser,
    private val sessionController: PINPADSessionController,
    private val asyncResponseSender: (ByteArray) -> Unit = {},
    private val ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pinpad-ack-retry").apply { isDaemon = true }
    },
) : PinpadProtocolHandler {
    private val retryLock = Any()
    private var pendingResponse: PendingResponse? = null
    private var ackTimeout: ScheduledFuture<*>? = null

    override fun onBytesReceived(bytes: ByteArray): List<ByteArray> {
        PinpadTraceLog.protocol("chunk received len=${bytes.size}")
        val events = parser.accept(bytes)
        PinpadTraceLog.protocol("parser events=${events.size}")
        val responses = events.flatMapIndexed { index, event ->
            if (event.isAckBeforePipelinedFrame(events, index)) {
                acknowledgePendingResponseBeforePipelinedFrame()
                sessionController.suppressPendingFinalEot("host ACK was followed by another frame")
                emptyList()
            } else {
                val retryResponse = onPendingControl(event)
                if (retryResponse != null) {
                    retryResponse
                } else {
                    clearPendingResponseBeforeNewFrame(event)
                    sessionController.onInbound(event).also(::trackResponseForAck)
                }
            }
        }
        PinpadTraceLog.protocol("responses generated=${responses.size} totalBytes=${responses.sumOf { it.size }}")
        return responses
    }

    override fun shutdown() {
        synchronized(retryLock) {
            ackTimeout?.cancel(false)
            ackTimeout = null
            pendingResponse = null
        }
        sessionController.shutdown()
        scheduler.shutdownNow()
    }

    override fun cancelActiveOperation() {
        sessionController.cancelActiveOperation()
    }

    override fun onKeypadKey(key: PinpadKeypadKey) {
        sessionController.onKeypadKey(key)
    }

    fun sendAsyncResponse(response: ByteArray) {
        trackResponseForAck(listOf(response))
        asyncResponseSender(response)
    }

    private fun onPendingControl(event: PINPADInbound): List<ByteArray>? {
        if (event !is PINPADInbound.Control) return null
        return when (event.value) {
            PINPADControl.ACK -> {
                synchronized(retryLock) {
                    if (pendingResponse != null) {
                        clearPendingResponseLocked()
                        PinpadTraceLog.protocol("response acknowledged by host")
                    }
                }
                null
            }
            PINPADControl.NAK -> {
                synchronized(retryLock) {
                    if (pendingResponse == null) {
                        null
                    } else {
                        listOf(retryForNakLocked())
                    }
                }
            }
            PINPADControl.EOT -> {
                synchronized(retryLock) {
                    if (pendingResponse != null) {
                        clearPendingResponseLocked()
                        PinpadTraceLog.protocol("pending response cleared by host EOT")
                    }
                }
                null
            }
            else -> null
        }
    }

    private fun acknowledgePendingResponseBeforePipelinedFrame() {
        synchronized(retryLock) {
            if (pendingResponse != null) {
                clearPendingResponseLocked()
                PinpadTraceLog.protocol("response acknowledged by host before pipelined frame")
            }
        }
    }

    private fun clearPendingResponseBeforeNewFrame(event: PINPADInbound) {
        if (event !is PINPADInbound.Frame) return
        synchronized(retryLock) {
            if (pendingResponse != null) {
                clearPendingResponseLocked()
                PinpadTraceLog.protocol("pending response cleared by new inbound frame command=${event.frame.commandId}")
            }
        }
    }

    private fun trackResponseForAck(responses: List<ByteArray>) {
        if (responses.isEmpty()) return
        synchronized(retryLock) {
            if (responses.any { it.isControl(PINPADControl.EOT) }) {
                clearPendingResponseLocked()
                return
            }

            val frame = responses.lastOrNull { it.size > 1 } ?: return
            pendingResponse = PendingResponse(frame = frame, timeoutRetriesSent = 0)
            scheduleAckTimeoutLocked()
            PinpadTraceLog.protocol(
                "response awaiting ACK len=${frame.size} timeoutMs=$ackTimeoutMs maxRetries=$maxRetries",
            )
        }
    }

    private fun scheduleAckTimeoutLocked() {
        ackTimeout?.cancel(false)
        ackTimeout = scheduler.schedule(::onAckTimeout, ackTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun onAckTimeout() {
        val response = synchronized(retryLock) {
            if (pendingResponse == null) {
                null
            } else {
                retryOrAbortTimeoutLocked()
            }
        }
        if (response != null) asyncResponseSender(response)
    }

    private fun retryForNakLocked(): ByteArray {
        val pending = pendingResponse ?: return byteArrayOf(PINPADControl.EOT)
        scheduleAckTimeoutLocked()
        PinpadTraceLog.protocol("response NAK; retransmitting len=${pending.frame.size}")
        return pending.frame
    }

    private fun retryOrAbortTimeoutLocked(): ByteArray {
        val pending = pendingResponse ?: return byteArrayOf(PINPADControl.EOT)
        if (pending.timeoutRetriesSent < maxRetries) {
            val retryNumber = pending.timeoutRetriesSent + 1
            pendingResponse = pending.copy(timeoutRetriesSent = retryNumber)
            scheduleAckTimeoutLocked()
            PinpadTraceLog.protocol("response ACK timeout; retry=$retryNumber/$maxRetries len=${pending.frame.size}")
            return pending.frame
        }

        clearPendingResponseLocked()
        sessionController.abortPendingResponse()
        PinpadTraceLog.protocol("response ACK timeout after $maxRetries retries; ending session with EOT")
        return byteArrayOf(PINPADControl.EOT)
    }

    private fun clearPendingResponseLocked() {
        ackTimeout?.cancel(false)
        ackTimeout = null
        pendingResponse = null
    }

    private fun ByteArray.isControl(control: Byte): Boolean = size == 1 && first() == control

    private fun PINPADInbound.isAckBeforePipelinedFrame(events: List<PINPADInbound>, index: Int): Boolean {
        return this is PINPADInbound.Control &&
            value == PINPADControl.ACK &&
            events.drop(index + 1).any { it is PINPADInbound.Frame }
    }

    private data class PendingResponse(
        val frame: ByteArray,
        val timeoutRetriesSent: Int,
    )

    private companion object {
        private const val DEFAULT_ACK_TIMEOUT_MS = 5_000L
        private const val DEFAULT_MAX_RETRIES = 2
    }
}
