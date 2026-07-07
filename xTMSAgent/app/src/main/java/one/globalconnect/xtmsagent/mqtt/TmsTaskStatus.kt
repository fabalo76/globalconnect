package one.globalconnect.xtmsagent.mqtt

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared status text for the TMS foreground notification.
 * A non-null value overrides the idle "TMS connected (termId)" text.
 * Set to null by tasks when they complete to restore the idle display.
 */
object TmsTaskStatus {
    val taskOverride = MutableStateFlow<String?>(null)
}
