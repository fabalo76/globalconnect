package one.globalconnect.xtmsagent.remote

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
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
        .build()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var closed = false

    fun connect() {
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
        httpClient.newWebSocket(request, socketListener)
    }

    fun sendAnswer(recipientClientId: String, description: SessionDescription) {
        val payload = JSONObject()
            .put("type", "answer")
            .put("sdp", description.description)
            .toString()
        send("SDP_ANSWER", recipientClientId, encodePayload(payload))
    }

    fun sendIceCandidate(recipientClientId: String, candidate: IceCandidate) {
        val payload = JSONObject()
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .toString()
        send("ICE_CANDIDATE", recipientClientId, encodePayload(payload))
    }

    fun disconnect() {
        closed = true
        webSocket?.close(1000, "session ended")
        webSocket = null
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun send(action: String, recipientClientId: String, messagePayload: String) {
        val message = JSONObject()
            .put("action", action)
            .put("recipientClientId", recipientClientId)
            .put("senderClientId", "")
            .put("messagePayload", messagePayload)
            .toString()
        webSocket?.send(message)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@KinesisSignalingClient.webSocket = webSocket
            Log.i(SIGNALING_TAG, "Kinesis signaling connected")
            listener.onSignalingOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val event = JSONObject(text)
                val messageType = event.optString("messageType")
                val senderClientId = event.optString("senderClientId")
                val payload = event.optString("messagePayload")
                when (messageType.uppercase()) {
                    "SDP_OFFER" -> {
                        val offer = JSONObject(decodePayload(payload))
                        listener.onSdpOffer(senderClientId, offer.getString("sdp"))
                    }
                    "ICE_CANDIDATE" -> {
                        val candidateJson = JSONObject(decodePayload(payload))
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
                        Log.d(SIGNALING_TAG, "Signaling status response: $text")
                    }
                }
            } catch (e: Exception) {
                Log.w(SIGNALING_TAG, "Failed to process signaling message: ${e.message}")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@KinesisSignalingClient.webSocket = null
            if (!closed) listener.onSignalingClosed(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@KinesisSignalingClient.webSocket = null
            if (!closed) listener.onSignalingError(t.message ?: "signaling failure")
        }
    }

    private fun encodePayload(payload: String): String {
        return Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
    }

    private fun decodePayload(payload: String): String {
        return String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
    }
}
