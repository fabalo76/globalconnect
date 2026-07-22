package one.globalconnect.paymentapp.transaction

import android.util.Log
import one.globalconnect.paymentapp.uicpos.pos.host.HostProcessingEvent

/**
 * Stateful helper that keeps track of the progress UI for host-bound operations.
 *
 * Host transactions (sale, refund, settlement, etc.) all emit the same sequence of
 * ISO8583 lifecycle events. This class centralises the transformation from those
 * low-level events into the user-facing [ProcessingStatusUi] representation so the
 * various flows can share a single implementation.
 */
class HostProcessingStateMachine(
    private val strings: ProcessingStatusStrings,
) {
    private var current: ProcessingStatusUi = createInitialProcessingStatus(strings)

    /**
     * Resets the internal state machine to the initial "connecting" step and returns the
     * fresh [ProcessingStatusUi] snapshot. Call this whenever a new host transmission begins.
     */
    fun restart(): ProcessingStatusUi {
        current = createInitialProcessingStatus(strings)
        logState("restart")
        return current
    }

    /**
     * Applies a low-level host connectivity event and returns the updated UI snapshot.
     */
    fun onEvent(event: HostProcessingEvent): ProcessingStatusUi {
        Log.d(TAG, "onEvent event=$event")
        current = current.updateForEvent(event)
        logState("onEvent event=$event")
        return current
    }

    /**
     * Marks the flow as completed successfully with the supplied detail message.
     */
    fun onSuccess(detail: String): ProcessingStatusUi {
        current = current.updateStage(
            stage = ProcessingStatusStage.RESULT,
            state = ProcessingStatusStepState.COMPLETED,
            detail = detail,
        )
        Log.d(TAG, "onSuccess detail=$detail")
        logState("onSuccess")
        return current
    }

    /**
     * Marks the flow as failed with the supplied detail message.
     */
    fun onFailure(detail: String): ProcessingStatusUi {
        current = current.updateStage(
            stage = ProcessingStatusStage.RESULT,
            state = ProcessingStatusStepState.FAILED,
            detail = detail,
        )
        Log.d(TAG, "onFailure detail=$detail")
        logState("onFailure")
        return current
    }

    fun onWaitingForResponseDetail(detail: String): ProcessingStatusUi {
        val stage = current.stage(ProcessingStatusStage.WAITING_FOR_RESPONSE)
            ?: return current
        current = current.updateStage(
            stage = ProcessingStatusStage.WAITING_FOR_RESPONSE,
            state = stage.status,
            detail = detail,
        )
        Log.d(TAG, "onWaitingForResponseDetail detail=$detail")
        logState("onWaitingForResponseDetail")
        return current
    }

    private fun logState(action: String) {
        val summary = current.steps.joinToString(separator = " | ") { step ->
            buildString {
                append(step.stage)
                append('=')
                append(step.status)
                step.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    append(" (")
                    append(detail)
                    append(')')
                }
            }
        }
        Log.d(TAG, "state[$action]=$summary")
    }

    private companion object {
        private const val TAG = "HostProcessingStateMachine"
    }
}
