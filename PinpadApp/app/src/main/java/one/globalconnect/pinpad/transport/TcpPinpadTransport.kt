package one.globalconnect.pinpad.transport

import android.content.Context
import android.util.Log
import one.globalconnect.pinpad.logging.PinpadTraceLog
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class TcpPinpadTransport(
    context: Context,
    private val tcpPort: Int,
    private val serialNumber: String,
    private val modelName: String,
) : PINPADTransport {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val clientLock = Any()
    private val serverLock = Any()
    private val serverSockets = mutableMapOf<String, ServerSocket>()
    private val sendExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pinpad-tcp-send").apply { isDaemon = true }
    }
    @Volatile private var listener: PINPADTransport.Listener? = null
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var activeClient: Socket? = null

    override fun start(listener: PINPADTransport.Listener) {
        check(running.compareAndSet(false, true)) { "TCP/IP transport is already running" }
        this.listener = listener
        try {
            discoverySocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                soTimeout = DISCOVERY_RECEIVE_TIMEOUT_MS
            }
        } catch (error: Throwable) {
            stop()
            throw IllegalStateException("Unable to listen for IP discovery on UDP $DISCOVERY_PORT", error)
        }

        Thread(::networkListenerLoop, "pinpad-tcp-network").apply { isDaemon = true }.start()
        Thread(::discoveryLoop, "pinpad-udp-discovery").apply { isDaemon = true }.start()
        PinpadTraceLog.transport("IP listening TCP=$tcpPort discoveryUDP=$DISCOVERY_PORT")
    }

    override fun send(bytes: ByteArray) {
        if (!running.get()) return
        val payload = bytes.copyOf()
        try {
            sendExecutor.execute {
                val client = synchronized(clientLock) { activeClient } ?: return@execute
                runCatching {
                    synchronized(clientLock) {
                        if (client !== activeClient || client.isClosed) return@synchronized
                        client.getOutputStream().apply {
                            write(payload)
                            flush()
                        }
                    }
                }.onFailure { error ->
                    PinpadTraceLog.transport("IP send failed: ${error.message}")
                    Log.w(TAG, "TCP send failed; closing client", error)
                    closeClient(client)
                }
            }
        } catch (_: RejectedExecutionException) {
            // stop() has already shut the writer down.
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { discoverySocket?.close() }
        discoverySocket = null
        synchronized(serverLock) {
            serverSockets.values.forEach { runCatching { it.close() } }
            serverSockets.clear()
        }
        synchronized(clientLock) {
            runCatching { activeClient?.close() }
            activeClient = null
        }
        sendExecutor.shutdownNow()
        listener = null
    }

    private fun networkListenerLoop() {
        while (running.get()) {
            val desired = appContext.activeLanIpv4Addresses()
                .associateBy { it.address.hostAddress.orEmpty() }
            synchronized(serverLock) {
                // Do not tear down an existing listener or client after one
                // transient ConnectivityManager miss. Bound sockets naturally
                // fail if the physical network actually disappears; newly
                // observed addresses are added below.
                desired.forEach { (key, lanAddress) ->
                    if (key !in serverSockets) {
                        runCatching {
                            ServerSocket().apply {
                                reuseAddress = true
                                bind(InetSocketAddress(lanAddress.address, tcpPort))
                            }
                        }.onSuccess { socket ->
                            serverSockets[key] = socket
                            Thread(
                                { acceptLoop(socket, lanAddress.address) },
                                "pinpad-tcp-accept-$key",
                            ).apply { isDaemon = true }.start()
                            PinpadTraceLog.transport("IP listening address=$key:$tcpPort")
                            Log.i(TAG, "TCP listening on $key:$tcpPort")
                        }.onFailure { error ->
                            Log.w(TAG, "Unable to listen on $key:$tcpPort", error)
                            listener?.onTransportError(
                                IllegalStateException("Unable to listen on $key:$tcpPort", error),
                            )
                        }
                    }
                }
            }
            try {
                Thread.sleep(NETWORK_REFRESH_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket, localAddress: Inet4Address) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            } catch (error: Throwable) {
                if (running.get()) listener?.onTransportError(error)
                return
            }

            synchronized(clientLock) {
                runCatching { activeClient?.close() }
                activeClient = client
            }
            client.tcpNoDelay = true
            client.keepAlive = true
            PinpadTraceLog.transport(
                "IP client connected remote=${client.inetAddress.hostAddress}:${client.port} " +
                    "local=${localAddress.hostAddress}:$tcpPort",
            )
            Log.i(
                TAG,
                "TCP client connected remote=${client.inetAddress.hostAddress}:${client.port} " +
                    "local=${localAddress.hostAddress}:$tcpPort",
            )
            Thread({ readClient(client) }, "pinpad-tcp-client").apply { isDaemon = true }.start()
        }
    }

    private fun readClient(client: Socket) {
        val buffer = ByteArray(8 * 1024)
        try {
            val input = client.getInputStream()
            while (running.get() && !client.isClosed) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) listener?.onBytesReceived(buffer.copyOf(count))
            }
        } catch (error: IOException) {
            if (running.get() && !client.isClosed) {
                Log.i(TAG, "TCP client disconnected: ${error.message}")
            }
        } finally {
            closeClient(client)
            PinpadTraceLog.transport("IP client disconnected")
            Log.i(TAG, "TCP client disconnected remote=${client.inetAddress.hostAddress}:${client.port}")
        }
    }

    private fun discoveryLoop() {
        val buffer = ByteArray(512)
        while (running.get()) {
            val socket = discoverySocket ?: return
            val request = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(request)
                val message = String(request.data, request.offset, request.length, StandardCharsets.UTF_8).trim()
                if (message != DISCOVERY_REQUEST) continue
                val remote = request.address as? Inet4Address ?: continue
                val lanAddresses = appContext.activeLanIpv4Addresses()
                val advertised = lanAddresses.firstOrNull { it.isInSameSubnet(remote) }
                    ?: continue
                val payload = JSONObject()
                    .put("protocol", DISCOVERY_PROTOCOL)
                    .put("version", 1)
                    .put("serialNumber", serialNumber)
                    .put("model", modelName)
                    .put("address", advertised.address.hostAddress)
                    .put("tcpPort", tcpPort)
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, request.address, request.port))
                PinpadTraceLog.transport(
                    "IP discovery response remote=${request.address.hostAddress} " +
                        "address=${advertised.address.hostAddress}:$tcpPort",
                )
            } catch (_: SocketTimeoutException) {
                // Periodically wake so stop() is observed.
            } catch (_: SocketException) {
                if (!running.get()) return
            } catch (error: Throwable) {
                Log.w(TAG, "Discovery response failed", error)
            }
        }
    }

    private fun closeClient(client: Socket) {
        synchronized(clientLock) {
            if (activeClient === client) activeClient = null
        }
        runCatching { client.close() }
    }

    companion object {
        const val DISCOVERY_PORT = 39100
        const val DISCOVERY_REQUEST = "GLOBALCONNECT_PINPAD_DISCOVER_V1"
        const val DISCOVERY_PROTOCOL = "globalconnect-pinpad-discovery"
        private const val DISCOVERY_RECEIVE_TIMEOUT_MS = 1_000
        private const val NETWORK_REFRESH_MS = 2_000L
        private const val TAG = "TcpPinpadTransport"
    }
}
