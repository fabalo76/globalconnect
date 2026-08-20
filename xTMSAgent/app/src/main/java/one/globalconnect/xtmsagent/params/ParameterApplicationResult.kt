package one.globalconnect.xtmsagent.params

internal enum class ParameterApplicationResult {
    COMPLETED,
    DEFERRED,
    FAILED,
}

internal fun parameterApplicationResult(status: String?): ParameterApplicationResult =
    when (status?.trim()?.lowercase()) {
        "completed" -> ParameterApplicationResult.COMPLETED
        "deferred" -> ParameterApplicationResult.DEFERRED
        else -> ParameterApplicationResult.FAILED
    }
