package com.uic.uicpaymentapp.cardreader.nexgo

import android.util.Log
import com.nexgo.oaf.apiv3.device.beeper.Beeper

/**
 * Handles short beeper feedback for contactless card interactions.
 */
class CardReaderBeeper(
    private val beeper: Beeper,
) {
    private val TAG = "CardReaderBeeper"
    private val SUCCESS_BEEP_DURATION_MS = 600
    private val ERROR_BEEP_DURATION_MS = 200
    private val ERROR_PAUSE_SHORT_MS = 200L
    private val ERROR_PAUSE_LONG_MS = 300L

    fun onContactlessSuccess() {
        beep(SUCCESS_BEEP_DURATION_MS)
    }

    fun onContactlessError() {
        beep(ERROR_BEEP_DURATION_MS)
        pause(ERROR_PAUSE_SHORT_MS)
        beep(ERROR_BEEP_DURATION_MS)
        pause(ERROR_PAUSE_LONG_MS)
        beep(ERROR_BEEP_DURATION_MS)
    }

    private fun beep(durationMs: Int) {
        try {
            beeper.beep(durationMs)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to trigger beeper", error)
        }
    }

    private fun pause(durationMs: Long) {
        try {
            Thread.sleep(durationMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Beeper pause interrupted", error)
        }
    }
    private fun beep(durationMs: Short, param2 : Short) {
        try {
            beeper.beep(durationMs,param2 )
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to trigger beeper", error)
        }
    }
}
