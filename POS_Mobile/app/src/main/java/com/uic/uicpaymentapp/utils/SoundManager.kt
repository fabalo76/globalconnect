package com.uic.uicpaymentapp.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.ToneGenerator
import com.uic.uicpaymentapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Enum representing different sound effects in the application.
 */
enum class SoundEffect {
    NONE,
    KEY_CLICK,
    KEY_TICK,
    KEY_DELETE,
    KEY_SPACEBAR,
    KEY_INVALID,
    KEY_RETURN,
    KEY_STANDARD,
    TONE_DTMF_0
}

/**
 * Singleton SoundManager to handle sound playback in the application.
 */
object SoundManager {
    private var soundPool: SoundPool? = null
    private val soundMap = mutableMapOf<SoundEffect, Int>()
    private val loadingSamples = mutableMapOf<SoundEffect, Int>()
    private val sampleToEffect = mutableMapOf<Int, SoundEffect>()
    private val pendingPlayback = mutableSetOf<SoundEffect>()
    private lateinit var applicationContext: Context
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 80) // Volume level 80%
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var preloadScheduled = false
    private val soundResources = mapOf(
        SoundEffect.KEY_CLICK to R.raw.key_click,
        SoundEffect.KEY_TICK to R.raw.effect_tick,
        SoundEffect.KEY_DELETE to R.raw.keypress_delete,
        SoundEffect.KEY_SPACEBAR to R.raw.keypress_spacebar,
        SoundEffect.KEY_INVALID to R.raw.keypress_invalid,
        SoundEffect.KEY_RETURN to R.raw.keypress_return,
        SoundEffect.KEY_STANDARD to R.raw.keypress_standard
    )

    /**
     * Initializes the SoundPool and prepares it for lazy sound loading.
     * @param context Application context to load resources.
     */
    fun init(context: Context) {
        synchronized(lock) {
            if (!::applicationContext.isInitialized) {
                applicationContext = context.applicationContext
            }
            if (soundPool != null) {
                return
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val pool = SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build()

            pool.setOnLoadCompleteListener { _, sampleId, status ->
                var effect: SoundEffect? = null
                var shouldPlay = false
                synchronized(lock) {
                    effect = sampleToEffect.remove(sampleId)
                    if (effect != null) {
                        if (status != 0) {
                            loadingSamples.remove(effect)
                            pendingPlayback.remove(effect)
                            soundMap.remove(effect)
                            effect = null
                        } else {
                            loadingSamples.remove(effect)
                            soundMap[effect!!] = sampleId
                            shouldPlay = pendingPlayback.remove(effect)
                        }
                    }
                }

                if (status == 0 && effect != null && shouldPlay) {
                    playLoadedSample(sampleId, effect!!)
                }
            }

            soundPool = pool
        }
    }

    fun preload() {
        val context = synchronized(lock) {
            if (!::applicationContext.isInitialized) {
                return
            }
            if (preloadScheduled) {
                return
            }
            if (soundPool == null) {
                return
            }
            preloadScheduled = true
            applicationContext
        }

        preloadScope.launch {
            val pool = soundPool ?: return@launch
            soundResources.forEach { (effect, resId) ->
                synchronized(lock) {
                    if (!soundMap.containsKey(effect) && !loadingSamples.containsKey(effect)) {
                        val sampleId = pool.load(context, resId, 1)
                        if (sampleId != 0) {
                            loadingSamples[effect] = sampleId
                            sampleToEffect[sampleId] = effect
                        }
                    }
                }
            }
        }
    }

    /**
     * Plays a sound effect based on the given SoundEffect enum.
     * @param soundEffect The sound to play.
     */
    fun play(soundEffect: SoundEffect) {
        if (soundEffect == SoundEffect.NONE) {
            return
        }

        if (soundEffect == SoundEffect.TONE_DTMF_0) {
            toneGenerator.startTone(ToneGenerator.TONE_DTMF_0, 150)
            return
        }

        val pool = soundPool ?: return
        val context = if (::applicationContext.isInitialized) applicationContext else return

        val sampleToPlay = synchronized(lock) {
            val loaded = soundMap[soundEffect]
            if (loaded != null) {
                return@synchronized loaded
            }

            if (loadingSamples.containsKey(soundEffect)) {
                pendingPlayback.add(soundEffect)
                return@synchronized null
            }

            val resId = soundResources[soundEffect] ?: return
            val sampleId = pool.load(context, resId, 1)
            if (sampleId != 0) {
                loadingSamples[soundEffect] = sampleId
                sampleToEffect[sampleId] = soundEffect
                pendingPlayback.add(soundEffect)
            }
            null
        }

        if (sampleToPlay != null) {
            playLoadedSample(sampleToPlay, soundEffect)
        }
    }

    private fun playLoadedSample(sampleId: Int, soundEffect: SoundEffect) {
        val volume = when (soundEffect) {
            SoundEffect.KEY_SPACEBAR, SoundEffect.KEY_RETURN -> 0.7f  // ⬇ 30% Lower Volume (1.0 -> 0.7)
            else -> 1.0f  // Default volume
        }
        soundPool?.play(sampleId, volume, volume, 1, 0, 1f)
    }

    /**
     * Releases the SoundPool resources when no longer needed.
     */
    fun release() {
        synchronized(lock) {
            soundPool?.release()
            soundPool = null
            soundMap.clear()
            loadingSamples.clear()
            sampleToEffect.clear()
            pendingPlayback.clear()
            preloadScheduled = false
        }
        preloadScope.coroutineContext.cancelChildren()
    }
}
