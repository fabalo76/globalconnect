package one.globalconnect.paymentapp.transaction

import one.globalconnect.paymentapp.settlement.SettlementResult
import one.globalconnect.paymentapp.uicpos.pos.host.HostProcessingEvent

/**
 * Ordered stages that describe the lifecycle of a host communication.
 */
enum class ProcessingStatusStage {
    CONNECTING,
    SENDING,
    WAITING_FOR_RESPONSE,
    PROCESSING_RESPONSE,
    RESULT,
}

/**
 * Visual state for a processing step within the host communication flow.
 */
enum class ProcessingStatusStepState {
    PENDING,
    ACTIVE,
    COMPLETED,
    FAILED,
}

/**
 * Immutable representation of the UI that renders processing progress.
 */
data class ProcessingStatusUi(
    val steps: List<ProcessingStatusStep>,
) {
    init {
        require(steps.map(ProcessingStatusStep::stage).distinct().size == steps.size) {
            "Processing stages must be unique"
        }
    }

    fun stage(stage: ProcessingStatusStage): ProcessingStatusStep? =
        steps.firstOrNull { it.stage == stage }
}

/**
 * Individual processing step entry with its state and optional detail text.
 */
data class ProcessingStatusStep(
    val stage: ProcessingStatusStage,
    val title: String,
    val status: ProcessingStatusStepState,
    val detail: String? = null,
)

/**
 * Bundle of localized strings required to build the initial processing UI state.
 */
data class ProcessingStatusStrings(
    val connecting: String,
    val sending: String,
    val waitingForResponse: String,
    val processingResponse: String,
    val result: String,
    val pendingResult: String,
)

/**
 * Represents the UI state shown while a settlement target is being processed.
 *
 * @property acquirerName User-facing name of the acquirer whose settlement is running.
 * @property processingStatus Snapshot of the current progress through the host processing flow.
 */
data class SettlementProcessingState(
    val acquirerName: String,
    val processingStatus: ProcessingStatusUi,
    val batchUploadProgress: BatchUploadProgress? = null,
    val results: SettlementResultsUiState? = null,
)

data class BatchUploadProgress(
    val current: Int,
    val total: Int,
) {
    init {
        require(total > 0) { "Batch upload total must be greater than zero" }
        require(current in 1..total) { "Batch upload current must be within 1..total" }
    }
}

sealed class SettlementResultsUiState {
    data class Summary(val results: List<SettlementResult>) : SettlementResultsUiState()
    data class Message(val text: String) : SettlementResultsUiState()
}

/**
 * Creates the initial status state with the connecting step marked as active.
 */
fun createInitialProcessingStatus(strings: ProcessingStatusStrings): ProcessingStatusUi {
    val stages = listOf(
        ProcessingStatusStage.CONNECTING to strings.connecting,
        ProcessingStatusStage.SENDING to strings.sending,
        ProcessingStatusStage.WAITING_FOR_RESPONSE to strings.waitingForResponse,
        ProcessingStatusStage.PROCESSING_RESPONSE to strings.processingResponse,
        ProcessingStatusStage.RESULT to strings.result,
    )
    val steps = stages.mapIndexed { index, (stage, title) ->
        ProcessingStatusStep(
            stage = stage,
            title = title,
            status = if (index == 0) ProcessingStatusStepState.ACTIVE else ProcessingStatusStepState.PENDING,
            detail = if (stage == ProcessingStatusStage.RESULT) strings.pendingResult else null,
        )
    }
    return ProcessingStatusUi(steps)
}

/**
 * Returns a new [ProcessingStatusUi] with the supplied stage transitioned to the requested [state].
 *
 * Previous stages are automatically marked as completed when the transition does not represent a
 * failure, preserving their final state. Subsequent stages remain untouched to avoid resetting
 * previously surfaced errors.
 */
fun ProcessingStatusUi.updateStage(
    stage: ProcessingStatusStage,
    state: ProcessingStatusStepState,
    detail: String? = null,
): ProcessingStatusUi {
    val targetIndex = steps.indexOfFirst { it.stage == stage }
    if (targetIndex == -1) return this
    val updated = steps.mapIndexed { index, current ->
        when {
            index < targetIndex && state != ProcessingStatusStepState.FAILED -> {
                if (current.status == ProcessingStatusStepState.COMPLETED || current.status == ProcessingStatusStepState.FAILED) {
                    current
                } else {
                    current.copy(status = ProcessingStatusStepState.COMPLETED)
                }
            }
            index == targetIndex -> current.copy(
                status = state,
                detail = detail ?: current.detail,
            )
            else -> current
        }
    }
    return copy(steps = updated)
}

/**
 * Convenience helper to apply [HostProcessingEvent] updates to the UI representation.
 */
fun ProcessingStatusUi.updateForEvent(event: HostProcessingEvent): ProcessingStatusUi =
    when (event) {
        HostProcessingEvent.Connecting -> updateStage(
            ProcessingStatusStage.CONNECTING,
            ProcessingStatusStepState.ACTIVE,
        )
        HostProcessingEvent.Sending -> updateStage(
            ProcessingStatusStage.SENDING,
            ProcessingStatusStepState.ACTIVE,
        )
        HostProcessingEvent.WaitingForResponse -> updateStage(
            ProcessingStatusStage.WAITING_FOR_RESPONSE,
            ProcessingStatusStepState.ACTIVE,
        )
        HostProcessingEvent.ProcessingResponse -> updateStage(
            ProcessingStatusStage.PROCESSING_RESPONSE,
            ProcessingStatusStepState.ACTIVE,
        )
    }

/**
 * Returns the step that should currently be surfaced to the user.
 */
fun ProcessingStatusUi.currentStep(): ProcessingStatusStep? {
    val result = stage(ProcessingStatusStage.RESULT)
    if (result != null &&
        (result.status == ProcessingStatusStepState.COMPLETED || result.status == ProcessingStatusStepState.FAILED)
    ) {
        return result
    }

    val active = steps.firstOrNull { it.status == ProcessingStatusStepState.ACTIVE }
    if (active != null) return active

    val failed = steps.lastOrNull { it.status == ProcessingStatusStepState.FAILED }
    if (failed != null) return failed

    val completed = steps.lastOrNull { it.status == ProcessingStatusStepState.COMPLETED }
    if (completed != null) return completed

    return steps.firstOrNull()
}

/**
 * Indicates whether the processing result has reached a terminal state.
 */
fun ProcessingStatusUi.isResultFinal(): Boolean {
    val result = stage(ProcessingStatusStage.RESULT)
    return result != null &&
        (result.status == ProcessingStatusStepState.COMPLETED || result.status == ProcessingStatusStepState.FAILED)
}
