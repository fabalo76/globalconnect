package one.globalconnect.xtmsagent.recovery

internal data class StartupFailureState(
    val started: Long = 0,
    val pid: Int = 0,
    val version: Int = 0,
    val counted: Boolean = false,
    val failures: List<Long> = emptyList(),
    val recovery: Boolean = false,
    val retryArmed: Boolean = false,
) {
    fun failed(now: Long): StartupFailureState {
        if (recovery || started == 0L || counted) return this
        val recent = failures.filter { now - it in 0..WINDOW_MS } + now
        return copy(counted = true, failures = recent, recovery = retryArmed || recent.size >= 3)
    }

    fun begin(now: Long, processId: Int, build: Int, priorExitTime: Long?, priorExitReason: Int?): StartupFailureState {
        if (recovery) return this
        val prior = if (version == build && priorExitTime != null && priorExitTime >= started &&
            now - priorExitTime in 0..WINDOW_MS && priorExitReason in listOf(4, 5, 6)) failed(priorExitTime) else this
        if (prior.recovery) return prior
        return prior.copy(started = now, pid = processId, version = build, counted = false,
            failures = prior.failures.filter { now - it in 0..WINDOW_MS })
    }

    fun healthy(attempt: Long): StartupFailureState =
        if (!recovery && !counted && started == attempt) copy(started = 0, failures = emptyList(), retryArmed = false) else this

    fun retry(): StartupFailureState = copy(recovery = false, started = 0, counted = false, retryArmed = true)

    companion object { const val WINDOW_MS = 10 * 60 * 1000L }
}
