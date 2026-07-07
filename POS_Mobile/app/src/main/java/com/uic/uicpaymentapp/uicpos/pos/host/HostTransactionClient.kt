package com.uic.uicpaymentapp.uicpos.pos.host

import android.util.Log
import com.uic.pos.iso8583.IsoMessage
import com.uic.pos.iso8583.IsoMessageFactory
import com.uic.pos.iso8583.exception.Iso8583ParseException
import com.uic.pos.iso8583.util.IsoByteUtils
import com.uic.tms.payment_app.TMS_Acquirer
import com.uic.tms.payment_app.TMS_HostConnectionInfo
import com.uic.tms.payment_app.TMS_Terminal
import com.uic.uicpaymentapp.BuildConfig
import com.uic.uicpaymentapp.util.LogSanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val HOST_CLIENT_TAG = "HostTransactionClient"

/**
 * Handles ISO8583 transaction exchanges with acquirer hosts.
 */
class HostTransactionClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun createSession(): HostTransactionSession = HostTransactionSession(clock)

    suspend fun execute(
        request: HostTransactionRequest,
        session: HostTransactionSession? = null,
    ): HostTransactionResult = withContext(dispatcher) {
        val prepared = prepareFrame(request)

        val activeSession = session?.takeIf { it.isOpen }
        if (activeSession != null) {
            return@withContext executeOnSession(activeSession, request, prepared)
        }

        val attempts = request.attempts.coerceAtLeast(1)
        var lastError: Throwable? = null
        repeat(attempts) { attemptIndex ->
            if (request.primaryEndpoint != null) {
                val retries = request.primaryRetries.coerceAtLeast(1)
                repeat(retries) { retryIndex ->
                    try {
                        return@withContext transmit(
                            request = request,
                            prepared = prepared,
                            endpoint = HostEndpoint.EndpointType.PRIMARY,
                            address = request.primaryEndpoint,
                            attemptIndex = attemptIndex,
                            retryIndex = retryIndex,
                            session = session,
                        )
                    } catch (error: Throwable) {
                        lastError = error
                        session?.close()
                        Log.w(HOST_CLIENT_TAG, "Primary host attempt failed", error)
                    }
                }
            }

            if (request.secondaryEndpoint != null) {
                val retries = request.secondaryRetries.coerceAtLeast(1)
                repeat(retries) { retryIndex ->
                    try {
                        return@withContext transmit(
                            request = request,
                            prepared = prepared,
                            endpoint = HostEndpoint.EndpointType.SECONDARY,
                            address = request.secondaryEndpoint,
                            attemptIndex = attemptIndex,
                            retryIndex = retryIndex,
                            session = session,
                        )
                    } catch (error: Throwable) {
                        lastError = error
                        session?.close()
                        Log.w(HOST_CLIENT_TAG, "Secondary host attempt failed", error)
                    }
                }
            }
        }

        throw lastError ?: IOException("Unable to contact any host endpoint")
    }

    private suspend fun executeOnSession(
        session: HostTransactionSession,
        request: HostTransactionRequest,
        prepared: PreparedFrame,
    ): HostTransactionResult {
        return try {
            session.sendPrepared(request, prepared)
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private suspend fun transmit(
        request: HostTransactionRequest,
        prepared: PreparedFrame,
        endpoint: HostEndpoint.EndpointType,
        address: HostAddress,
        attemptIndex: Int,
        retryIndex: Int,
        session: HostTransactionSession?,
    ): HostTransactionResult {
        if (address.port <= 0) {
            throw IOException("Host port is not configured for ${address.host}")
        }

        val socket = if (request.useTls) createTlsSocket(request) else SocketFactory.getDefault().createSocket()

        return if (session == null) {
            socket.use {
                connectSocket(it, request, address)
                sendFrame(
                    socket = it,
                    request = request,
                    prepared = prepared,
                    endpoint = HostEndpoint(address, endpoint),
                    attemptIndex = attemptIndex,
                    retryIndex = retryIndex,
                    clock = clock,
                )
            }
        } else {
            try {
                connectSocket(socket, request, address)
                session.bind(
                    socket = socket,
                    endpoint = HostEndpoint(address, endpoint),
                    attemptIndex = attemptIndex,
                    retryIndex = retryIndex,
                )
                session.sendPrepared(request, prepared)
            } catch (error: Throwable) {
                session.close()
                throw error
            }
        }
    }

    private fun createTlsSocket(request: HostTransactionRequest): Socket {
        val factory = request.sslSocketFactory ?: run {
            Log.w(HOST_CLIENT_TAG, "No cached SSL factory for this acquirer — building from bundled CAs")
            val trustManagers = if (request.ipProfile.ignoreTlsTrustErrors) {
                Log.w(HOST_CLIENT_TAG, "TLS trust validation disabled for IPTab=${request.ipProfile.IPTabID}")
                buildTrustAllManagers()
            } else {
                buildMergedTrustManagers(emptyList(), BundledCertificates.get(), HOST_CLIENT_TAG)
            }
            val ctx = SSLContext.getInstance("TLSv1.2")
            ctx.init(null, trustManagers, null)
            ctx.socketFactory
        }
        return factory.createSocket()
    }

}

class HostTransactionSession internal constructor(private val clock: Clock) : AutoCloseable {
    private var socket: Socket? = null
    private var endpoint: HostEndpoint? = null
    private var attemptIndex: Int = 0
    private var retryIndex: Int = 0

    val isOpen: Boolean
        get() = socket?.isClosed == false

    internal fun bind(socket: Socket, endpoint: HostEndpoint, attemptIndex: Int, retryIndex: Int) {
        this.socket = socket
        this.endpoint = endpoint
        this.attemptIndex = attemptIndex
        this.retryIndex = retryIndex
    }

    internal suspend fun sendPrepared(
        request: HostTransactionRequest,
        prepared: PreparedFrame,
    ): HostTransactionResult {
        val activeSocket = socket ?: throw IllegalStateException("Session is not connected")
        val activeEndpoint = endpoint ?: throw IllegalStateException("Session endpoint is not set")
        return try {
            sendFrame(
                socket = activeSocket,
                request = request,
                prepared = prepared,
                endpoint = activeEndpoint,
                attemptIndex = attemptIndex,
                retryIndex = retryIndex,
                clock = clock,
            )
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun close() {
        val socketToClose = socket
        socket = null
        endpoint = null
        if (socketToClose != null) {
            runCatching { socketToClose.close() }
        }
    }
}

private fun readFrame(
    input: InputStream,
    lengthBytes: Int,
    lengthType: com.uic.pos.iso8583.IsoLengthType,
    clearPan: String?,
    track1: String?,
    track2: String?,
    track3: String?,
): ByteArray {
    val header = if (lengthBytes > 0) ByteArray(lengthBytes) else ByteArray(0)
    Log.d(HOST_CLIENT_TAG, "Reading ISO8583 frame (lengthBytes=$lengthBytes lengthType=$lengthType)")
    if (lengthBytes > 0) {
        readFully(input, header, lengthBytes)
    }
    val payloadLength = if (lengthBytes > 0) {
        val value = IsoByteUtils.bytesToInt(header, 0, lengthBytes, lengthType)
        if (value <= 0) throw IOException("Invalid ISO8583 length prefix: $value")
        val hexPrefix = LogSanitizer.sanitizeHexPayload(
            clearPan = clearPan,
            track1 = track1,
            track2 = track2,
            track3 = track3,
        ) { header.toHexString() }
        Log.d(HOST_CLIENT_TAG, "Length prefix=$hexPrefix decodedLength=$value")
        value
    } else {
        val available = input.available().takeIf { it > 0 }
            ?: throw IOException("Unable to determine payload length")
        Log.d(HOST_CLIENT_TAG, "No length prefix configured; using available bytes=$available")
        available
    }
    val payload = ByteArray(payloadLength)
    readFully(input, payload, payloadLength)
    Log.d(HOST_CLIENT_TAG, "Successfully read $payloadLength bytes from stream")
    return payload
}

private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
    var offset = 0
    while (offset < length) {
        val read = input.read(buffer, offset, length - offset)
        if (read == -1) {
            throw EOFException("Stream closed while reading data")
        }
        offset += read
    }
}

internal data class PreparedFrame(
    val frame: ByteArray,
    val payload: ByteArray,
    val headerLength: Int,
)

private fun prepareFrame(request: HostTransactionRequest): PreparedFrame {
    val frame = request.message.toByteArray(request.lengthConfig.lengthBytes, request.lengthConfig.lengthType)
    val headerLength = (request.message.header?.length ?: 0) / 2
    val payload = if (request.lengthConfig.lengthBytes > 0) {
        frame.copyOfRange(request.lengthConfig.lengthBytes, frame.size)
    } else {
        frame
    }

    IsoMessageDebugLogger.logMessage(HOST_CLIENT_TAG, "Host request", request.message, payload)
    if (request.lengthConfig.lengthBytes > 0) {
        val prefix = frame.copyOfRange(0, request.lengthConfig.lengthBytes)
        val hexPrefix = LogSanitizer.sanitizeHexPayload(
            clearPan = request.clearPan,
            track1 = request.track1,
            track2 = request.track2,
            track3 = request.track3,
        ) { prefix.toHexString() }
        Log.d(HOST_CLIENT_TAG, "ISO8583 length prefix (${prefix.size} bytes)=$hexPrefix")
    }
    val hexPayload = LogSanitizer.sanitizeHexPayload(
        clearPan = request.clearPan,
        track1 = request.track1,
        track2 = request.track2,
        track3 = request.track3,
    ) { payload.toHexString() }
    Log.d(HOST_CLIENT_TAG, "ISO8583 request payload (${payload.size} bytes)=$hexPayload")
    return PreparedFrame(frame, payload, headerLength)
}

private suspend fun connectSocket(
    socket: Socket,
    request: HostTransactionRequest,
    address: HostAddress,
) {
    Log.d(
        HOST_CLIENT_TAG,
        "Connecting to ${address.displayValue} using ${if (request.useTls) "TLS" else "TCP"} " +
            "(connectTimeout=${request.connectTimeoutMs}ms readTimeout=${request.readTimeoutMs}ms)"
    )
    request.onStatusChanged?.invoke(HostProcessingEvent.Connecting)
    socket.connect(InetSocketAddress(address.host, address.port), request.connectTimeoutMs)
    socket.soTimeout = request.readTimeoutMs
    Log.d(
        HOST_CLIENT_TAG,
        "Connected to ${address.displayValue} local=${socket.localAddress}:${socket.localPort} soTimeout=${socket.soTimeout}"
    )

    if (socket is SSLSocket) {
        Log.d(HOST_CLIENT_TAG, "Starting TLS handshake with ${address.displayValue}")
        socket.startHandshake()
        val session = socket.session
        Log.d(
            HOST_CLIENT_TAG,
            "TLS session established protocol=${session.protocol} cipher=${session.cipherSuite}"
        )
        if (BuildConfig.ENABLE_SSL_DEBUG_LOGS) {
            try {
                session.peerCertificates.forEachIndexed { index, certificate ->
                    Log.d(HOST_CLIENT_TAG, "TLS peer certificate[$index] ${certificate}")
                }
            } catch (error: SSLPeerUnverifiedException) {
                Log.w(HOST_CLIENT_TAG, "Unable to inspect TLS peer certificates", error)
            }
        }
    }

    request.onConnected?.let { callback ->
        callback()
    }
}

private fun sendFrame(
    socket: Socket,
    request: HostTransactionRequest,
    prepared: PreparedFrame,
    endpoint: HostEndpoint,
    attemptIndex: Int,
    retryIndex: Int,
    clock: Clock,
): HostTransactionResult {
    val start = System.nanoTime()
    socket.soTimeout = request.readTimeoutMs
    val output = socket.getOutputStream()
    request.onStatusChanged?.invoke(HostProcessingEvent.Sending)
    val frameHex = LogSanitizer.sanitizeHexPayload(
        clearPan = request.clearPan,
        track1 = request.track1,
        track2 = request.track2,
        track3 = request.track3,
    ) { prepared.frame.toHexString() }
    Log.d(HOST_CLIENT_TAG, "Sending ISO8583 frame (${prepared.frame.size} bytes)=$frameHex")
    output.write(prepared.frame)
    output.flush()
    Log.d(HOST_CLIENT_TAG, "Frame sent to ${endpoint.address.displayValue}")

    request.onStatusChanged?.invoke(HostProcessingEvent.WaitingForResponse)
    val payload = readFrame(
        input = socket.getInputStream(),
        lengthBytes = request.lengthConfig.lengthBytes,
        lengthType = request.lengthConfig.lengthType,
        clearPan = request.clearPan,
        track1 = request.track1,
        track2 = request.track2,
        track3 = request.track3,
    )
    val roundTrip = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    val payloadHex = LogSanitizer.sanitizeHexPayload(
        clearPan = request.clearPan,
        track1 = request.track1,
        track2 = request.track2,
        track3 = request.track3,
    ) { payload.toHexString() }
    Log.d(HOST_CLIENT_TAG, "Received ISO8583 payload (${payload.size} bytes)=$payloadHex")

    request.onStatusChanged?.invoke(HostProcessingEvent.ProcessingResponse)
    val isoMessage = try {
        request.isoFactory.parse(payload, prepared.headerLength)
    } catch (error: Iso8583ParseException) {
        throw IOException("Failed to parse host response", error)
    }

    IsoMessageDebugLogger.logMessage(HOST_CLIENT_TAG, "Host response", isoMessage, payload)

    return HostTransactionResult(
        isoMessage = isoMessage,
        rawRequest = prepared.frame,
        rawResponse = payload,
        endpoint = endpoint,
        attempt = attemptIndex + 1,
        retry = retryIndex + 1,
        roundTripMs = roundTrip,
        timestamp = LocalDateTime.now(clock),
    )
}

/**
 * Network configuration required to transmit an ISO8583 transaction.
 */
data class HostTransactionRequest(
    val acquirer: TMS_Acquirer,
    val ipProfile: TMS_HostConnectionInfo,
    val terminal: TMS_Terminal,
    val message: IsoMessage,
    val clearPan: String? = null,
    val track1: String? = null,
    val track2: String? = null,
    val track3: String? = null,
    val lengthConfig: LengthConfig,
    val primaryEndpoint: HostAddress?,
    val secondaryEndpoint: HostAddress?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val attempts: Int,
    val primaryRetries: Int,
    val secondaryRetries: Int,
    val useTls: Boolean,
    val sslSocketFactory: SSLSocketFactory? = null,
    val isoFactory: IsoMessageFactory,
    val onConnected: (suspend () -> Unit)? = null,
    val onStatusChanged: ((HostProcessingEvent) -> Unit)? = null,
)

/**
 * Outcome of a host transaction attempt.
 */
data class HostTransactionResult(
    val isoMessage: IsoMessage,
    val rawRequest: ByteArray,
    val rawResponse: ByteArray,
    val endpoint: HostEndpoint,
    val attempt: Int,
    val retry: Int,
    val roundTripMs: Long,
    val timestamp: LocalDateTime,
)

sealed class HostProcessingEvent {
    object Connecting : HostProcessingEvent()
    object Sending : HostProcessingEvent()
    object WaitingForResponse : HostProcessingEvent()
    object ProcessingResponse : HostProcessingEvent()
}
