package one.globalconnect.pinpad.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.SystemClock
import one.globalconnect.pinpad.logging.ProductionLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class AndroidAudioCapture(context: Context, file: File,
    private val foreground: (Boolean) -> Unit,
) : AudioCaptureSession {
    private val requested = AtomicBoolean(true)
    @Volatile override var running = true
        private set
    @Volatile override var failure: Throwable? = null
        private set
    private val audio = context.getSystemService(AudioManager::class.java)
    private val oldMode = audio.mode
    private val oldSpeaker = audio.isSpeakerphoneOn
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pinpad:AudioRecording")
    private val worker: Thread

    init {
        val recorder = MediaRecorder()
        try {
            foreground(true)
            audio.mode = AudioManager.MODE_NORMAL
            audio.isSpeakerphoneOn = false
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(AudioRecordingPolicy.SAMPLE_RATE)
            recorder.setAudioEncodingBitRate(AudioRecordingPolicy.AAC_BIT_RATE)
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnErrorListener { _, what, extra ->
                failure = IllegalStateException("AAC recorder error $what/$extra")
                requested.set(false)
            }
            recorder.prepare()
            recorder.start()
            val started = SystemClock.elapsedRealtime()
            wakeLock.acquire(AudioRecordingPolicy.MAX_DURATION_MS + 10_000)
            ProductionLog.record("AUDIO", "started file=${file.name} microphone=standard codec=AAC-LC bitrate=${AudioRecordingPolicy.AAC_BIT_RATE}")
            worker = Thread({
                try {
                    while (requested.get() && SystemClock.elapsedRealtime() - started < AudioRecordingPolicy.MAX_DURATION_MS &&
                        file.length() < AudioRecordingPolicy.MAX_AAC_BYTES) Thread.sleep(25)
                    recorder.stop()
                } catch (error: Exception) {
                    failure = error
                } finally {
                    runCatching { recorder.release() }.onFailure { if (failure == null) failure = it }
                    // ADTS consists of independently framed AAC packets. Retain only
                    // complete frames, bounded to 30 minutes, even after an encoder error.
                    runCatching {
                        val info = AacAdts.scan(file, repair = true)
                        check(info.durationMs > 0) { "AAC encoder produced no complete frames" }
                    }.onFailure { if (failure == null) failure = it }
                    ProductionLog.record("AUDIO", "finished file=${file.name} bytes=${file.length()} elapsedMs=${SystemClock.elapsedRealtime() - started} failure=${failure?.message ?: "none"}")
                    restore()
                    running = false
                }
            }, "pinpad-audio-capture").also { it.start() }
        } catch (error: Exception) {
            ProductionLog.record("AUDIO", "startupFailed type=${error.javaClass.simpleName} detail=${error.message}")
            runCatching { recorder.release() }
            restore()
            running = false
            throw error
        }
    }

    private fun restore() {
        runCatching { audio.isSpeakerphoneOn = oldSpeaker; audio.mode = oldMode }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        runCatching { foreground(false) }
    }

    override fun stop(): Boolean {
        requested.set(false)
        worker.join(3000)
        return !running
    }
}
