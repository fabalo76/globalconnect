package one.globalconnect.xtmsagent.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteControlAccessibilityProvisionerTest {
    @Test
    fun rawComponentListWorksWithRuntimeExecWithoutLiteralQuotes() {
        val services = "example.reader/.ReaderService:one.globalconnect.xtmsagent/.RemoteService"
        assertEquals("/system/bin/settings --user 0 put secure enabled_accessibility_services $services",
            RemoteControlAccessibilityProvisioner.accessibilityEnableCommands(services, true)[0])
        assertEquals("/system/bin/settings --user 0 put secure accessibility_enabled 1",
            RemoteControlAccessibilityProvisioner.accessibilityEnableCommands(services, true)[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawComponentListRejectsShellMetacharacters() {
        RemoteControlAccessibilityProvisioner.accessibilityEnableCommands("pkg/.Service;id", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawComponentListRejectsWhitespace() {
        RemoteControlAccessibilityProvisioner.accessibilityEnableCommands("pkg/.Service other", true)
    }

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
