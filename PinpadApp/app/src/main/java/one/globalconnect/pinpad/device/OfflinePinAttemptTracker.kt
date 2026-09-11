package one.globalconnect.pinpad.device

/** Tracks offline-PIN prompts so retry information is never shown on the first attempt. */
internal class OfflinePinAttemptTracker {
    private var promptCount = 0

    /** Clears prompt history at the start of each EMV transaction. */
    @Synchronized
    fun reset() {
        promptCount = 0
    }

    /**
     * Records a kernel PIN request.
     *
     * @param remainingTries card tries remaining as reported by the EMV kernel.
     * @return null for the initial request, otherwise the non-negative retry count to display.
     */
    @Synchronized
    fun recordPrompt(remainingTries: Int): Int? {
        val retry = promptCount > 0
        promptCount += 1
        return remainingTries.coerceAtLeast(0).takeIf { retry }
    }
}
