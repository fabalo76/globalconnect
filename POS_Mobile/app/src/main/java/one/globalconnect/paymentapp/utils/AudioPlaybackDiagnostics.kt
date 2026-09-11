package one.globalconnect.paymentapp.utils

import android.content.Context
import android.media.AudioManager
import android.util.Log

/** Capture audio state at playback time without changing the terminal's volume settings. */
internal object AudioPlaybackDiagnostics {
    fun log(context: Context, event: String) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streams = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)
                .joinToString { stream ->
                    "stream=$stream volume=${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)} muted=${audio.isStreamMute(stream)}"
                }
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString { "${it.type}:${it.id}" }
            Log.d("PaymentAudio", "$event mode=${audio.mode} ringer=${audio.ringerMode} $streams availableOutputs=[$outputs]")
        }.onFailure { Log.w("PaymentAudio", "Unable to inspect audio state for $event", it) }
    }
}
