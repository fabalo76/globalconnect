package one.globalconnect.xtmsagent.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import one.globalconnect.xtmsagent.TMSFunc
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class StandaloneRemoteScreenClient(
    private val context: Context,
    private val config: RemoteControlConfig,
    private val projectionData: Intent,
    private val onViewerConnected: () -> Unit,
    private val onSessionEnded: () -> Unit,
) : RemoteScreenClient {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).build()
    private val stopped = AtomicBoolean(false)
    private val captureLock = Any()
    private val thread = HandlerThread("StandaloneRemoteScreen")
    private var socket: WebSocket? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val metrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }
    @Volatile private var quality = qualityRank(config.quality).coerceAtMost(qualityRank(config.maxQuality))
    private val input = RemoteInputHandler(metrics.widthPixels, metrics.heightPixels) {
        quality = qualityRank(it).coerceAtMost(qualityRank(config.maxQuality))
    }
    private var previousFrameNanos = 0L

    override fun start() {
        val (cleanEndpoint, token) = StandaloneRemoteEndpoint.parse(requireNotNull(config.endpoints["WSS"]),
            TMSFunc.tmsCfg.apiHost, config.sessionId)
        thread.start()
        socket = http.newWebSocket(Request.Builder().url(cleanEndpoint.toString())
            .header("Sec-WebSocket-Protocol", "globalconnect.remote.v1, auth.$token").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped.get() || text.length > 8192) return
                try {
                    if (JSONObject(text).optString("type") == "peerConnected") {
                        Handler(thread.looper).post {
                            if (!stopped.get()) { startCapture(); if (!stopped.get()) onViewerConnected() }
                        }
                    } else input.handle(text)
                } catch (_: Exception) { stop() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { stop() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { stop() }
        })
    }

    private fun startCapture() = synchronized(captureLock) {
        if (stopped.get() || projection != null) return@synchronized
        try {
            val handler = Handler(thread.looper)
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val activeProjection = requireNotNull(manager.getMediaProjection(Activity.RESULT_OK, projectionData))
            projection = activeProjection
            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stop() }
            }, handler)
            val images = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            reader = images
            images.setOnImageAvailableListener({ captureFrame(it) }, handler)
            display = activeProjection.createVirtualDisplay("GlobalConnectRemote", metrics.widthPixels,
                metrics.heightPixels, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                images.surface, null, handler)
        } catch (_: Exception) { stop() }
    }

    private fun captureFrame(images: ImageReader) = synchronized(captureLock) {
        if (stopped.get()) return@synchronized
        try {
            images.acquireLatestImage()?.use { image ->
                val activeSocket = socket ?: return@use
                val now = System.nanoTime()
                val fps = intArrayOf(6, 10, 15)[quality]
                if (now - previousFrameNanos < 1_000_000_000L / fps || activeSocket.queueSize() > 256 * 1024) return@use
                previousFrameNanos = now
                val plane = image.planes[0]
                val padding = (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                val padded = Bitmap.createBitmap(image.width + padding, image.height, Bitmap.Config.ARGB_8888)
                var cropped: Bitmap? = null
                var scaled: Bitmap? = null
                try {
                    padded.copyPixelsFromBuffer(plane.buffer)
                    cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    val width = minOf(image.width, intArrayOf(360, 540, 720)[quality])
                    val height = (image.height.toDouble() * width / image.width).roundToInt().coerceAtLeast(1)
                    scaled = Bitmap.createScaledBitmap(cropped, width, height, true)
                    val output = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, intArrayOf(40, 55, 70)[quality], output)
                    if (output.size() <= 2 * 1024 * 1024) activeSocket.send(output.toByteArray().toByteString())
                } finally {
                    if (scaled !== cropped && scaled !== padded) scaled?.recycle()
                    if (cropped !== padded) cropped?.recycle()
                    padded.recycle()
                }
            }
        } catch (_: Exception) { stop() }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        socket?.cancel(); socket = null
        synchronized(captureLock) {
            runCatching { display?.release() }; display = null
            runCatching { reader?.close() }; reader = null
            runCatching { projection?.stop() }; projection = null
        }
        thread.quitSafely()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
        onSessionEnded()
    }

    private fun qualityRank(value: String) = when (value) { "medium" -> 1; "high" -> 2; else -> 0 }
}
