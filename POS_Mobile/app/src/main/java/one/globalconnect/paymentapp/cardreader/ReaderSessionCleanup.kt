package one.globalconnect.paymentapp.cardreader

/** Late disposal must not cancel a newer screen or interrupt completed sensory playback. */
internal fun shouldCancelReaderSession(ownsListener: Boolean, searchActive: Boolean, sdkRunning: Boolean): Boolean =
    ownsListener && (searchActive || sdkRunning)
