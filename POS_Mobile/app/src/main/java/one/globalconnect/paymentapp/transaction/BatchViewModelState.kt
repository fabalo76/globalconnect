package one.globalconnect.paymentapp.transaction

data class BatchViewModelState(
    val batchState: BatchState = BatchState.STOPPED,
    val batchProgress: Int = 0,
    val errors: Int = 0
) {
    fun toUiState(): BatchUiState =
        when (batchState) {
            BatchState.STOPPED -> BatchUiState.STOPPED()
            BatchState.INPROGRESS -> BatchUiState.IN_PROGRESS(batchProgress)
            BatchState.COMPLETE -> BatchUiState.COMPLETE(errors)
        }
}

enum class BatchState {
    STOPPED,
    INPROGRESS,
    COMPLETE
}

sealed interface BatchUiState {
    class STOPPED : BatchUiState
    class IN_PROGRESS(val progressPercent: Int): BatchUiState

    class COMPLETE(val errors: Int): BatchUiState
}