package one.globalconnect.xtmsagent.remote

internal class RemotePeerLifecycleGate {
    data class StopDecision(
        val newlyRequested: Boolean,
        val canFinalize: Boolean,
    )

    private var stopRequested = false
    private var negotiationInFlight = false
    private var finalized = false

    @Synchronized
    fun tryBeginNegotiation(): Boolean {
        if (stopRequested || finalized || negotiationInFlight) return false
        negotiationInFlight = true
        return true
    }

    @Synchronized
    fun requestStop(): StopDecision {
        if (stopRequested) return StopDecision(newlyRequested = false, canFinalize = false)
        stopRequested = true
        return StopDecision(newlyRequested = true, canFinalize = !negotiationInFlight)
    }

    @Synchronized
    fun completeNegotiation(): Boolean {
        negotiationInFlight = false
        return stopRequested && !finalized
    }

    @Synchronized
    fun isStopping(): Boolean = stopRequested || finalized

    @Synchronized
    fun tryFinalize(): Boolean {
        if (!stopRequested || negotiationInFlight || finalized) return false
        finalized = true
        return true
    }
}
