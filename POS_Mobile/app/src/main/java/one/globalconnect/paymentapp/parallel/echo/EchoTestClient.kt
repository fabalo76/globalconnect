package one.globalconnect.paymentapp.parallel.echo

import android.util.Log
import com.uic.pos.iso8583.IsoLengthType
import com.uic.pos.iso8583.IsoMessage
import com.uic.pos.iso8583.IsoMessageFactory
import com.uic.pos.iso8583.exception.Iso8583Exception
import com.uic.pos.iso8583.exception.Iso8583ParseException
import com.uic.pos.iso8583.util.IsoByteUtils
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.paymentapp.BuildConfig
import one.globalconnect.paymentapp.uicpos.pos.host.IsoFieldFormatter
import one.globalconnect.paymentapp.uicpos.pos.host.IsoMessageDebugLogger
import one.globalconnect.paymentapp.uicpos.pos.host.IsoMessageFactoryProvider
import one.globalconnect.paymentapp.uicpos.pos.host.StanProvider
import one.globalconnect.paymentapp.uicpos.pos.host.HostAddress
import one.globalconnect.paymentapp.uicpos.pos.host.HostEndpoint
import one.globalconnect.paymentapp.uicpos.pos.host.HostProcessingEvent
import one.globalconnect.paymentapp.uicpos.pos.host.LengthConfig
import one.globalconnect.paymentapp.uicpos.pos.host.BundledCertificates
import one.globalconnect.paymentapp.uicpos.pos.host.buildMergedTrustManagers
import one.globalconnect.paymentapp.uicpos.pos.host.buildTrustAllManagers
import one.globalconnect.paymentapp.uicpos.pos.host.toHexString
import one.globalconnect.paymentapp.util.LogSanitizer
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

/**
 * Executes ISO8583 echo tests against the configured acquirer hosts.
 */
class EchoTestClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val factoryProvider: (String) -> IsoMessageFactory = IsoMessageFactoryProvider::factoryFor,
    private val stanSupplier: () -> String = { StanProvider.nextStan() }
) {

    suspend fun execute(request: EchoTestRequest): EchoTestResult = withContext(dispatcher) {
        val isoFactory = factoryProvider(ISO_CONFIG)
        val timestamp = LocalDateTime.now(clock)
        Log.d(
            TAG,
            "Executing echo test for acquirer=${request.acquirer.AcquirerName} (${request.acquirer.AcqID}) attempts=${request.attempts} primaryRetries=${request.primaryRetries} secondaryRetries=${request.secondaryRetries}"
        )
        Log.d(
            TAG,
            "Primary endpoint=${request.primaryEndpoint?.displayValue ?: "<none>"} secondary endpoint=${request.secondaryEndpoint?.displayValue ?: "<none>"}"
        )
        Log.d(
            TAG,
            "Timeout configuration: connect=${request.connectTimeoutMs}ms read=${request.readTimeoutMs}ms useTls=${request.useTls}"
        )
        Log.d(
            TAG,
            "Length configuration: bytes=${request.lengthConfig.lengthBytes} type=${request.lengthConfig.lengthType}"
        )
        val message = buildIsoMessage(request.acquirer, isoFactory, timestamp)
        val frame = message.toByteArray(request.lengthConfig.lengthBytes, request.lengthConfig.lengthType)
        val headerLength = (message.header?.length ?: 0) / 2
        val payload = if (request.lengthConfig.lengthBytes > 0) {
            frame.copyOfRange(request.lengthConfig.lengthBytes, frame.size)
        } else {
            frame
        }

        IsoMessageDebugLogger.logMessage(TAG, "Echo request", message, payload)

        if (request.lengthConfig.lengthBytes > 0) {
            val prefix = frame.copyOfRange(0, request.lengthConfig.lengthBytes)
            val hexPayload = LogSanitizer.sanitizeHexPayload { prefix.toHexString() }
            Log.d(TAG, "ISO8583 length prefix (${prefix.size} bytes)=$hexPayload")
        }
        val hexPayload = LogSanitizer.sanitizeHexPayload { payload.toHexString() }
        Log.d(TAG, "ISO8583 request payload (${payload.size} bytes)=$hexPayload")
        Log.d(TAG, "Encoded frame size=${frame.size} bytes headerLength=$headerLength bytes")

        var lastError: Throwable? = null
        val attempts = request.attempts.coerceAtLeast(1)
        repeat(attempts) { attemptIndex ->
            Log.d(TAG, "Beginning attempt ${attemptIndex + 1} of $attempts")
            if (request.primaryEndpoint != null) {
                val retries = request.primaryRetries.coerceAtLeast(1)
                repeat(retries) { retryIndex ->
                    Log.d(TAG, "Attempt ${attemptIndex + 1} retry ${retryIndex + 1} targeting PRIMARY endpoint")
                    try {
                        return@withContext transmit(
                            frame = frame,
                            headerLength = headerLength,
                            endpoint = HostEndpoint.EndpointType.PRIMARY,
                            address = request.primaryEndpoint,
                            request = request,
                            isoFactory = isoFactory,
                            timestamp = timestamp
                        ).copy(attempt = attemptIndex + 1, retry = retryIndex + 1)
                    } catch (error: Throwable) {
                        lastError = error
                        Log.w(TAG, "Primary host attempt failed", error)
                    }
                }
            }

            if (request.secondaryEndpoint != null) {
                val retries = request.secondaryRetries.coerceAtLeast(1)
                repeat(retries) { retryIndex ->
                    Log.d(TAG, "Attempt ${attemptIndex + 1} retry ${retryIndex + 1} targeting SECONDARY endpoint")
                    try {
                        return@withContext transmit(
                            frame = frame,
                            headerLength = headerLength,
                            endpoint = HostEndpoint.EndpointType.SECONDARY,
                            address = request.secondaryEndpoint,
                            request = request,
                            isoFactory = isoFactory,
                            timestamp = timestamp
                        ).copy(attempt = attemptIndex + 1, retry = retryIndex + 1)
                    } catch (error: Throwable) {
                        lastError = error
                        Log.w(TAG, "Secondary host attempt failed", error)
                    }
                }
            }
        }

        throw lastError ?: IOException("Unable to contact any host endpoint")
    }

    private fun buildIsoMessage(
        acquirer: TMS_Acquirer,
        isoFactory: IsoMessageFactory,
        timestamp: LocalDateTime
    ): IsoMessage {
        try {
            Log.d(
                TAG,
                "Building ISO8583 echo request for acquirer=${acquirer.AcquirerName} (${acquirer.AcqID})"
            )
            val message = isoFactory.newMessage()
            val header = tpduFor(acquirer.NII)
            Log.d(TAG, "Using TPDU header $header for NII=${acquirer.NII}")
            message.setHeader(header)
            message.setMessageType(MESSAGE_TYPE)

            val fieldValues = linkedMapOf<Int, String>()

            fieldValues[3] = PROCESSING_CODE
            message.setFieldValue(3, PROCESSING_CODE)

            val transmissionDateTime = IsoFieldFormatter.transmissionDateTime(timestamp)
            val stan = stanSupplier()
            val localTime = IsoFieldFormatter.localTime(timestamp)
            val localDate = IsoFieldFormatter.localDate(timestamp)
            val nii = IsoFieldFormatter.numeric(acquirer.NII, 3)
            val terminalId = IsoFieldFormatter.alphaNumeric(acquirer.AcqTermID, 8)
            val merchantId = IsoFieldFormatter.alphaNumeric(acquirer.MerchID, 15)
            val currencyCode = IsoFieldFormatter.numeric(acquirer.CurrencyCode, 3)

            message.setFieldValue(7, transmissionDateTime)
            message.setFieldValue(11, stan)
            message.setFieldValue(12, localTime)
            message.setFieldValue(13, localDate)
            message.setFieldValue(24, nii)
            message.setFieldValue(41, terminalId)
            message.setFieldValue(42, merchantId)
            message.setFieldValue(49, currencyCode)

            fieldValues[7] = transmissionDateTime
            fieldValues[11] = stan
            fieldValues[12] = localTime
            fieldValues[13] = localDate
            fieldValues[24] = nii
            fieldValues[41] = terminalId
            fieldValues[42] = merchantId
            fieldValues[49] = currencyCode

            IsoMessageDebugLogger.logConfiguredFields(TAG, "Echo request", fieldValues)
            return message
        } catch (error: Iso8583Exception) {
            throw IOException("Unable to encode ISO8583 echo message", error)
        }
    }

    private fun transmit(
        frame: ByteArray,
        headerLength: Int,
        endpoint: HostEndpoint.EndpointType,
        address: HostAddress,
        request: EchoTestRequest,
        isoFactory: IsoMessageFactory,
        timestamp: LocalDateTime
    ): EchoTestResult {
        if (address.port <= 0) {
            throw IOException("Host port is not configured for ${address.host}")
        }

        val start = System.nanoTime()
        val socket = if (request.useTls) createTlsSocket(request) else SocketFactory.getDefault().createSocket()

        socket.use {
            Log.d(
                TAG,
                "Connecting to ${address.displayValue} using ${if (request.useTls) "TLS" else "TCP"} (connectTimeout=${request.connectTimeoutMs}ms readTimeout=${request.readTimeoutMs}ms)"
            )
            request.onStatusChanged?.invoke(HostProcessingEvent.Connecting)
            it.connect(InetSocketAddress(address.host, address.port), request.connectTimeoutMs)
            it.soTimeout = request.readTimeoutMs
            Log.d(TAG, "Connected to ${address.displayValue} localAddress=${it.localAddress}:${it.localPort} soTimeout=${it.soTimeout}")

            if (it is SSLSocket) {
                Log.d(TAG, "Starting TLS handshake with ${address.displayValue}")
                it.startHandshake()
                val session = it.session
                Log.d(TAG, "TLS session established protocol=${session.protocol} cipher=${session.cipherSuite}")
                if (BuildConfig.ENABLE_SSL_DEBUG_LOGS) {
                    try {
                        session.peerCertificates.forEachIndexed { index, certificate ->
                            Log.d(
                                TAG,
                                "TLS peer certificate[$index] ${certificate}"
                            )
                        }
                    } catch (error: SSLPeerUnverifiedException) {
                        Log.w(TAG, "Unable to inspect TLS peer certificates", error)
                    }
                }
            }

            val output = it.getOutputStream()
            request.onStatusChanged?.invoke(HostProcessingEvent.Sending)
            val frameHex = LogSanitizer.sanitizeHexPayload { frame.toHexString() }
            Log.d(TAG, "Sending ISO8583 frame (${frame.size} bytes)=$frameHex")
            output.write(frame)
            output.flush()
            Log.d(TAG, "Frame sent to ${address.displayValue}")

            request.onStatusChanged?.invoke(HostProcessingEvent.WaitingForResponse)
            val payload = readFrame(it.getInputStream(), request.lengthConfig.lengthBytes, request.lengthConfig.lengthType)
            val roundTrip = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            val payloadHex = LogSanitizer.sanitizeHexPayload { payload.toHexString() }
            Log.d(TAG, "Received ISO8583 payload (${payload.size} bytes)=$payloadHex")
            Log.d(TAG, "Parsing ISO8583 response using headerLength=$headerLength")

            request.onStatusChanged?.invoke(HostProcessingEvent.ProcessingResponse)
            val isoMessage = try {
                isoFactory.parse(payload, headerLength)
            } catch (error: Iso8583ParseException) {
                throw IOException("Failed to parse host response", error)
            }

            IsoMessageDebugLogger.logMessage(TAG, "Echo response", isoMessage, payload)

            val responseCode = isoMessage.getFieldValue(39)
            Log.d(TAG, "Echo response code=$responseCode roundTrip=${roundTrip}ms endpoint=${endpoint.name}")
            return EchoTestResult(
                status = EchoTestStatus.Success(
                    responseCode = responseCode,
                    endpoint = HostEndpoint(address, endpoint),
                    roundTripMs = roundTrip,
                    timestamp = timestamp
                ),
                isoMessage = isoMessage,
                rawRequest = frame,
                rawResponse = payload
            )
        }
    }

    private fun createTlsSocket(request: EchoTestRequest): Socket {
        val factory = request.sslSocketFactory ?: run {
            Log.w(TAG, "No cached SSL factory for this acquirer — building from bundled CAs")
            val trustManagers = if (request.ipProfile.ignoreTlsTrustErrors) {
                Log.w(TAG, "TLS trust validation disabled for IPTab=${request.ipProfile.IPTabID}")
                buildTrustAllManagers()
            } else {
                buildMergedTrustManagers(emptyList(), BundledCertificates.get(), TAG)
            }
            val ctx = SSLContext.getInstance("TLSv1.2")
            ctx.init(null, trustManagers, null)
            ctx.socketFactory
        }
        return factory.createSocket()
    }

    private fun readFrame(input: InputStream, lengthBytes: Int, lengthType: IsoLengthType): ByteArray {
        val header = if (lengthBytes > 0) ByteArray(lengthBytes) else ByteArray(0)
        Log.d(TAG, "Reading ISO8583 frame (lengthBytes=$lengthBytes lengthType=$lengthType)")
        if (lengthBytes > 0) {
            readFully(input, header, lengthBytes)
        }
        val payloadLength = if (lengthBytes > 0) {
            val value = IsoByteUtils.bytesToInt(header, 0, lengthBytes, lengthType)
            if (value <= 0) throw IOException("Invalid ISO8583 length prefix: $value")
            val hexPrefix = LogSanitizer.sanitizeHexPayload { header.toHexString() }
            Log.d(TAG, "Length prefix=$hexPrefix decodedLength=$value")
            value
        } else {
            val available = input.available().takeIf { it > 0 }
                ?: throw IOException("Unable to determine payload length")
            Log.d(TAG, "No length prefix configured; using available bytes=$available")
            available
        }
        val payload = ByteArray(payloadLength)
        readFully(input, payload, payloadLength)
        Log.d(TAG, "Successfully read $payloadLength bytes from stream")
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

    companion object {
        private const val TAG = "EchoTestClient"
        private const val ISO_CONFIG = "iso8583_ISSWITCH_config.xml"
        private const val MESSAGE_TYPE = "0800"
        private const val PROCESSING_CODE = "990000"

        fun tpduFor(nii: Long): String {
            val numeric = nii.toString().padStart(3, '0')
            return "600${numeric}0000"
        }
    }
}

/**
 * Bundles together the information required to perform an echo request.
 */
data class EchoTestRequest(
    val acquirer: TMS_Acquirer,
    val ipProfile: TMS_HostConnectionInfo,
    val terminal: TMS_Terminal,
    val primaryEndpoint: HostAddress?,
    val secondaryEndpoint: HostAddress?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val attempts: Int,
    val primaryRetries: Int,
    val secondaryRetries: Int,
    val useTls: Boolean,
    val sslSocketFactory: SSLSocketFactory? = null,
    val lengthConfig: LengthConfig,
    val onStatusChanged: ((HostProcessingEvent) -> Unit)? = null
) {
    override fun toString(): String {
        val primaryValue = primaryEndpoint?.displayValue ?: "<none>"
        val secondaryValue = secondaryEndpoint?.displayValue ?: "<none>"
        return buildString {
            append("EchoTestRequest(")
            append("acquirer=")
            append(acquirer.AcquirerName)
            append(" (")
            append(acquirer.AcqID)
            append("), ")
            append("primary=")
            append(primaryValue)
            append(", secondary=")
            append(secondaryValue)
            append(", connectTimeoutMs=")
            append(connectTimeoutMs)
            append(", readTimeoutMs=")
            append(readTimeoutMs)
            append(", attempts=")
            append(attempts)
            append(", primaryRetries=")
            append(primaryRetries)
            append(", secondaryRetries=")
            append(secondaryRetries)
            append(", useTls=")
            append(useTls)
            append(", lengthBytes=")
            append(lengthConfig.lengthBytes)
            append(", lengthType=")
            append(lengthConfig.lengthType)
            append(')')
        }
    }
}

/**
 * Outcome of a network exchange with the host.
 */
data class EchoTestResult(
    val status: EchoTestStatus,
    val isoMessage: IsoMessage,
    val rawRequest: ByteArray,
    val rawResponse: ByteArray,
    val attempt: Int = 1,
    val retry: Int = 1
)

sealed class EchoTestStatus {
    object Idle : EchoTestStatus()
    object InProgress : EchoTestStatus()
    data class Success(
        val responseCode: String?,
        val endpoint: HostEndpoint,
        val roundTripMs: Long,
        val timestamp: LocalDateTime
    ) : EchoTestStatus()

    data class Failure(val reason: String) : EchoTestStatus()
}
