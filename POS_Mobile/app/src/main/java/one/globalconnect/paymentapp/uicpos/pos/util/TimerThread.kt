package one.globalconnect.paymentapp.uicpos.pos.util

import android.util.Log
import kotlin.concurrent.thread

class TimerThread{

    companion object {
        private val TAG = TimerThread::class.java.simpleName
    }

    private var timerStarted = false
    private var timerTick: Int = 0


    fun startTimer(timeoutSec: Int) {

        if(timerStarted) {
            return
        }

        if(timeoutSec == 0) {
            return
        }

        // timer thread
        timerStarted = true
        thread(start = true) {
            while (timerStarted) {
                // wait 1 sec
                Thread.sleep(1000)
                timerTick += 1

                if (timerStarted && timerTick> timeoutSec) {
                    Log.d(TAG, "Timeout")
                    // timeout
                    timerStarted = false
                    timerTick = 0
                    break;
                }
            }
            Log.d(TAG, "Stop")
        }
    }

    fun stopTimer() {
        timerStarted = false
    }

    fun isTimeout(): Any {
        return !timerStarted
    }
}