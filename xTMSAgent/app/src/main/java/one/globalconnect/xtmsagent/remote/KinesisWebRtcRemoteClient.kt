package one.globalconnect.xtmsagent.remote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val WEBRTC_TAG = "KinesisWebRtcClient"
private const val DATA_CHANNEL_LABEL = "uic-control"
private const val VIDEO_TRACK_ID = "XTMSAGENT_SCREEN"
private const val VIDEO_STREAM_ID = "XTMSAGENT_REMOTE_SCREEN"

class KinesisWebRtcRemoteClient(
    private val context: Context,
    private val config: RemoteControlConfig,
    private val projectionData: Intent,
    private val onViewerConnected: () -> Unit,
    private val onSessionEnded: () -> Unit,
) {
    private val eglBase = EglBase.create()
    private val inputHandler: RemoteInputHandler
    private val screenWidth: Int
    private val screenHeight: Int
    private val screenDpi: Int
    private val dataSaver = isCellular()
    private var currentQuality: RemoteVideoQuality

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var signalingClient: KinesisSignalingClient? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var remoteDescriptionSet = false
    private var viewerClientId: String? = null
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private val stopped = AtomicBoolean(false)

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenDpi = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        currentQuality = if (dataSaver) RemoteVideoQuality.LOW else RemoteVideoQuality.MEDIUM
        inputHandler = RemoteInputHandler(screenWidth, screenHeight, ::applyQuality)
    }

    fun start() {
        initializePeerConnectionFactory(context)
        peerConnectionFactory = createPeerConnectionFactory()
        peerConnection = createPeerConnection()
        startScreenCapture()

        signalingClient = KinesisSignalingClient(config, object : KinesisSignalingListener {
            override fun onSignalingOpen() {
                Log.i(WEBRTC_TAG, "Waiting for viewer offer on ${config.channelName}")
            }

            override fun onSdpOffer(senderClientId: String, sdp: String) {
                viewerClientId = senderClientId
                onViewerConnected()
                handleSdpOffer(senderClientId, sdp)
            }

            override fun onIceCandidate(senderClientId: String, candidate: IceCandidate) {
                if (senderClientId == viewerClientId) addRemoteIce(candidate)
            }

            override fun onSignalingClosed(reason: String) {
                Log.w(WEBRTC_TAG, "Signaling closed: $reason")
                stop()
            }

            override fun onSignalingError(reason: String) {
                Log.e(WEBRTC_TAG, "Signaling error: $reason")
                stop()
            }
        }).also { it.connect() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { signalingClient?.disconnect() } catch (_: Exception) {}
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { videoTrack?.dispose() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        try { peerConnectionFactory?.dispose() } catch (_: Exception) {}
        try { eglBase.release() } catch (_: Exception) {}
        signalingClient = null
        videoCapturer = null
        videoTrack = null
        peerConnection = null
        peerConnectionFactory = null
        onSessionEnded()
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection {
        val iceServers = buildIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return requireNotNull(peerConnectionFactory?.createPeerConnection(rtcConfig, peerObserver)) {
            "could not create WebRTC peer connection"
        }
    }

    private fun startScreenCapture() {
        val factory = requireNotNull(peerConnectionFactory)
        val capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(WEBRTC_TAG, "MediaProjection stopped")
                stop()
            }
        })
        val videoSource = factory.createVideoSource(false)
        val surfaceTextureHelper = SurfaceTextureHelper.create("xTMSAgentScreenCapture", eglBase.eglBaseContext)
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        val capture = currentQuality.captureSize(screenWidth, screenHeight)
        capturer.startCapture(capture.width, capture.height, currentQuality.fps)

        val track = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        val sender = peerConnection?.addTrack(track, listOf(VIDEO_STREAM_ID))
        videoSender = sender
        applyBitrate(currentQuality)

        videoCapturer = capturer
        videoTrack = track
        Log.i(WEBRTC_TAG, "Remote video quality=${currentQuality.name.lowercase()} ${capture.width}x${capture.height}@${currentQuality.fps} ${currentQuality.bitrateBps}bps")
    }

    private fun applyQuality(value: String) {
        val quality = RemoteVideoQuality.from(value) ?: return
        if (quality == currentQuality) return
        currentQuality = quality
        val capture = quality.captureSize(screenWidth, screenHeight)
        try {
            videoCapturer?.changeCaptureFormat(capture.width, capture.height, quality.fps)
            applyBitrate(quality)
            Log.i(WEBRTC_TAG, "Remote video quality changed to ${quality.name.lowercase()} ${capture.width}x${capture.height}@${quality.fps} ${quality.bitrateBps}bps")
        } catch (e: Exception) {
            Log.w(WEBRTC_TAG, "Failed to change remote video quality to ${quality.name.lowercase()}: ${e.message}")
        }
    }

    private fun applyBitrate(quality: RemoteVideoQuality) {
        val sender = videoSender ?: return
        sender.parameters = sender.parameters.apply {
            encodings.forEach { it.maxBitrateBps = quality.bitrateBps }
        }
    }

    private fun handleSdpOffer(senderClientId: String, sdp: String) {
        val peer = peerConnection ?: return
        peer.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                drainRemoteIce()
                createAndSendAnswer(senderClientId)
            }

            override fun onSetFailure(error: String) {
                Log.e(WEBRTC_TAG, "Failed to set remote SDP offer: $error")
                stop()
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun createAndSendAnswer(recipientClientId: String) {
        val peer = peerConnection ?: return
        peer.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                peer.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        signalingClient?.sendAnswer(recipientClientId, description)
                    }

                    override fun onSetFailure(error: String) {
                        Log.e(WEBRTC_TAG, "Failed to set local SDP answer: $error")
                        stop()
                    }
                }, description)
            }

            override fun onCreateFailure(error: String) {
                Log.e(WEBRTC_TAG, "Failed to create SDP answer: $error")
                stop()
            }
        }, MediaConstraints())
    }

    private fun addRemoteIce(candidate: IceCandidate) {
        val peer = peerConnection ?: return
        if (!remoteDescriptionSet) {
            pendingRemoteIce += candidate
            return
        }
        peer.addIceCandidate(candidate)
    }

    private fun drainRemoteIce() {
        val peer = peerConnection ?: return
        pendingRemoteIce.forEach { peer.addIceCandidate(it) }
        pendingRemoteIce.clear()
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        iceServers += PeerConnection.IceServer
            .builder("stun:stun.kinesisvideo.${config.region}.amazonaws.com:443")
            .createIceServer()
        config.iceServers.forEach { ice ->
            val builder = PeerConnection.IceServer.builder(ice.uris)
            if (!ice.username.isNullOrBlank()) builder.setUsername(ice.username)
            if (!ice.password.isNullOrBlank()) builder.setPassword(ice.password)
            iceServers += builder.createIceServer()
        }
        return iceServers
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val recipient = viewerClientId ?: return
            signalingClient?.sendIceCandidate(recipient, candidate)
        }

        override fun onDataChannel(dataChannel: DataChannel) {
            if (dataChannel.label() != DATA_CHANNEL_LABEL) {
                Log.d(WEBRTC_TAG, "Ignoring data channel ${dataChannel.label()}")
                return
            }
            dataChannel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    Log.i(WEBRTC_TAG, "Control data channel state=${dataChannel.state()}")
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary) {
                        Log.d(WEBRTC_TAG, "Ignoring binary control data channel message")
                        return
                    }
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    val message = String(data, Charsets.UTF_8)
                    Log.d(WEBRTC_TAG, "Control data channel message received: $message")
                    inputHandler.handle(message)
                }
            })
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(WEBRTC_TAG, "ICE connection state=$state")
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            ) {
                stop()
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private fun isCellular(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private val factoryInitialized = AtomicBoolean(false)

        private fun initializePeerConnectionFactory(context: Context) {
            if (factoryInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .createInitializationOptions(),
                )
            }
        }
    }
}

private data class CaptureSize(val width: Int, val height: Int)

private enum class RemoteVideoQuality(
    val maxWidth: Int,
    val fps: Int,
    val bitrateBps: Int,
) {
    LOW(640, 8, 350_000),
    MEDIUM(960, 12, 900_000),
    HIGH(1280, 15, 2_000_000);

    fun captureSize(screenWidth: Int, screenHeight: Int): CaptureSize {
        val scale = minOf(1.0, maxWidth.toDouble() / screenWidth.toDouble())
        return CaptureSize(
            width = (screenWidth * scale).roundToInt().coerceAtLeast(320),
            height = (screenHeight * scale).roundToInt().coerceAtLeast(240),
        )
    }

    companion object {
        fun from(value: String): RemoteVideoQuality? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}
