package one.globalconnect.xtmsagent.remote

import android.util.Log
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val SIGNALING_TAG = "KinesisSignaling"

interface KinesisSignalingListener {
    fun onSignalingOpen()
    fun onSdpOffer(senderClientId: String, sdp: String)
    fun onIceCandidate(senderClientId: String, candidate: IceCandidate)
    fun onSignalingClosed(reason: String)
    fun onSignalingError(reason: String)
}

class KinesisSignalingClient(
    private val config: RemoteControlConfig,
    private val listener: KinesisSignalingListener,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var socketGeneration = 0
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null

    fun connect() {
        if (closed) return
        openSocket()
    }

    private fun openSocket() {
        if (closed) return
        val wssEndpoint = config.endpoints["WSS"]
            ?: throw IllegalArgumentException("Kinesis WSS endpoint missing")
        val signedUrl = KinesisSigV4Signer.signMasterUrl(
            wssEndpoint = wssEndpoint,
            channelArn = config.channelArn,
            region = config.region,
            credentials = config.credentials,
        )
        val request = Request.Builder()
            .url(signedUrl)
            .addHeader("User-Agent", "xTMSAgent-RemoteControl/1.0")
            .build()
        val generation = ++socketGeneration
        Log.i(
            SIGNALING_TAG,
            "Opening Kinesis signaling socket generation=$generation " +
                "reconnectAttempt=$reconnectAttempt"
        )
        httpClient.newWebSocket(request, socketListener(generation))
    }

    fun sendAnswer(recipientClientId: String, description: SessionDescription) {
        val payload = JSONObject()
            .put("type", "answer")
            .put("sdp", description.description)
            .toString()
        send("SDP_ANSWER", recipientClientId, KinesisPayloadCodec.encode(payload))
    }

    fun sendIceCandidate(recipientClientId: String, candidate: IceCandidate) {
        val payload = JSONObject()
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .toString()
        send("ICE_CANDIDATE", recipientClientId, KinesisPayloadCodec.encode(payload))
    }

    fun disconnect() {
        if (closed) return
        closed = true
        socketGeneration++
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        webSocket?.cancel()
        webSocket = null
        httpClient.dispatcher.cancelAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun send(action: String, recipientClientId: String, messagePayload: String) {
        if (closed) return
        val message = JSONObject()
            .put("action", action)
            .put("recipientClientId", recipientClientId)
            .put("senderClientId", "")
            .put("messagePayload", messagePayload)
            .toString()
        webSocket?.send(message)
    }

    private fun socketListener(generation: Int) = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed || generation != socketGeneration) {
                    webSocket.cancel()
                    return
                }
                this@KinesisSignalingClient.webSocket = webSocket
                reconnectAttempt = 0
                reconnectRunnable?.let(mainHandler::removeCallbacks)
                reconnectRunnable = null
                Log.i(SIGNALING_TAG, "Kinesis signaling connected generation=$generation")
                listener.onSignalingOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed || generation != socketGeneration) return
                try {
                    val event = JSONObject(text)
                    val messageType = event.optString("messageType")
                    val senderClientId = event.optString("senderClientId")
                    val payload = event.optString("messagePayload")
                    when (messageType.uppercase()) {
                        "SDP_OFFER" -> {
                            val offer = JSONObject(KinesisPayloadCodec.decode(payload))
                            if (closed) return
                            listener.onSdpOffer(senderClientId, offer.getString("sdp"))
                        }
                        "ICE_CANDIDATE" -> {
                            val candidateJson = JSONObject(KinesisPayloadCodec.decode(payload))
                            if (closed) return
                            listener.onIceCandidate(
                                senderClientId,
                                IceCandidate(
                                    candidateJson.optString("sdpMid"),
                                    candidateJson.optInt("sdpMLineIndex", 0),
                                    candidateJson.getString("candidate"),
                                ),
                            )
                        }
                        "STATUS_RESPONSE" -> {
                            Log.d(SIGNALING_TAG, "Signaling status response received")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(SIGNALING_TAG, "Failed to process signaling message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != socketGeneration) return
                this@KinesisSignalingClient.webSocket = null
                if (!closed) scheduleReconnect("closed code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != socketGeneration) return
                this@KinesisSignalingClient.webSocket = null
                if (!closed) {
                    val responseDetail = response?.code?.let { " HTTP $it" }.orEmpty()
                    scheduleReconnect(
                        (t.message ?: "signaling failure") + responseDetail
                    )
                }
            }
        }

    private fun scheduleReconnect(reason: String) {
        if (closed || reconnectRunnable != null) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(
                SIGNALING_TAG,
                "Kinesis signaling recovery exhausted after $reconnectAttempt attempts: $reason"
            )
            listener.onSignalingError(reason)
            return
        }

        val delayMs = RECONNECT_DELAYS_MS[
            reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
        ]
        reconnectAttempt++
        Log.w(
            SIGNALING_TAG,
            "Kinesis signaling interrupted: $reason; reconnecting in ${delayMs}ms " +
                "(attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)"
        )
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            if (closed) return@Runnable
            runCatching(::openSocket).onFailure { error ->
                scheduleReconnect(error.message ?: "signaling reconnect failed")
            }
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 6
        private val RECONNECT_DELAYS_MS = longArrayOf(
            1_000L,
            2_000L,
            4_000L,
            8_000L,
            15_000L,
            30_000L,
        )
    }
}

internal object KinesisPayloadCodec {
    fun encode(payload: String): String {
        return Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun decode(payload: String): String {
        val bytes = runCatching { Base64.getDecoder().decode(payload) }
            .getOrElse { Base64.getUrlDecoder().decode(payload) }
        return String(bytes, Charsets.UTF_8)
    }
}
