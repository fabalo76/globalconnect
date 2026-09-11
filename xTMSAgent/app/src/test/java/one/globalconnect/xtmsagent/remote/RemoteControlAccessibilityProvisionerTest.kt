package one.globalconnect.xtmsagent.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteControlAccessibilityProvisionerTest {
    @Test
    fun appendServicePreservesExistingServicesAndAvoidsDuplicates() {
        val target = "one.globalconnect.xtmsagent/.remote.RemoteControlAccessibilityService"

        val result = RemoteControlAccessibilityProvisioner.appendService(
            "example.reader/.ReaderService:$target",
            target,
        )

        assertEquals("example.reader/.ReaderService:$target", result)
    }

    @Test
    fun enableCommandsQuoteTheServiceListAndEnableAccessibility() {
        val commands = RemoteControlAccessibilityProvisioner.accessibilityEnableCommands(
            "example.reader/.ReaderService:one.globalconnect.xtmsagent/.RemoteService",
        )

        assertEquals(
            listOf(
                "settings put secure enabled_accessibility_services " +
                    "'example.reader/.ReaderService:one.globalconnect.xtmsagent/.RemoteService'",
                "settings put secure accessibility_enabled 1",
            ),
            commands,
        )
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'before'\\''after'", RemoteControlAccessibilityProvisioner.shellSingleQuote("before'after"))
    }
}
