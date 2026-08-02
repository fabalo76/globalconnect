package one.globalconnect.pinpad.ui

internal class RapidTapSequence(
    private val requiredTaps: Int,
    private val maximumGapMs: Long,
) {
    private var tapCount = 0
    private var lastTapAtMs = Long.MIN_VALUE

    init {
        require(requiredTaps > 0)
        require(maximumGapMs > 0)
    }

    fun registerTap(atMs: Long): Boolean {
        tapCount = if (
            lastTapAtMs != Long.MIN_VALUE &&
            atMs >= lastTapAtMs &&
            atMs - lastTapAtMs <= maximumGapMs
        ) {
            tapCount + 1
        } else {
            1
        }
        lastTapAtMs = atMs
        if (tapCount < requiredTaps) {
            return false
        }
        reset()
        return true
    }

    fun reset() {
        tapCount = 0
        lastTapAtMs = Long.MIN_VALUE
    }
}
