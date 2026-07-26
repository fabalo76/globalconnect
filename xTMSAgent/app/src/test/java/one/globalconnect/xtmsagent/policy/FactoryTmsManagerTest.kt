package one.globalconnect.xtmsagent.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryTmsManagerTest {
    @Test
    fun `reports missing package before checking device owner`() {
        val backend = FakeBackend(installed = false, deviceOwner = false)

        val status = FactoryTmsManager.readStatus(backend)

        assertEquals(FactoryTmsState.NOT_INSTALLED, status.state)
    }

    @Test
    fun `requires device ownership to manage installed package`() {
        val backend = FakeBackend(installed = true, deviceOwner = false)

        val status = FactoryTmsManager.readStatus(backend)

        assertEquals(FactoryTmsState.DEVICE_OWNER_REQUIRED, status.state)
    }

    @Test
    fun `maps hidden package to disabled state`() {
        val backend = FakeBackend(hidden = true)

        val status = FactoryTmsManager.readStatus(backend)

        assertEquals(FactoryTmsState.DISABLED, status.state)
    }

    @Test
    fun `maps package-manager-disabled package to disabled state`() {
        val backend = FakeBackend(hidden = false, enabled = false)

        val status = FactoryTmsManager.readStatus(backend)

        assertEquals(FactoryTmsState.DISABLED, status.state)
    }

    @Test
    fun `disabling factory TMS hides and verifies package`() {
        val backend = FakeBackend(hidden = false)

        val result = FactoryTmsManager.applyEnabled(backend, enabled = false)

        assertTrue(result.success)
        assertTrue(result.changed)
        assertEquals(FactoryTmsState.DISABLED, result.status.state)
        assertEquals(listOf(true), backend.hiddenRequests)
    }

    @Test
    fun `enabling factory TMS unhides and verifies package`() {
        val backend = FakeBackend(hidden = true)

        val result = FactoryTmsManager.applyEnabled(backend, enabled = true)

        assertTrue(result.success)
        assertTrue(result.changed)
        assertEquals(FactoryTmsState.ENABLED, result.status.state)
        assertEquals(listOf(false), backend.hiddenRequests)
    }

    @Test
    fun `already-applied policy does not call device policy manager`() {
        val backend = FakeBackend(hidden = true)

        val result = FactoryTmsManager.applyEnabled(backend, enabled = false)

        assertTrue(result.success)
        assertFalse(result.changed)
        assertTrue(backend.hiddenRequests.isEmpty())
    }

    @Test
    fun `rejected policy operation is reported as failure`() {
        val backend = FakeBackend(hidden = false, acceptSetHidden = false)

        val result = FactoryTmsManager.applyEnabled(backend, enabled = false)

        assertFalse(result.success)
        assertEquals(FactoryTmsState.ERROR, result.status.state)
        assertEquals("policy_verification_failed", result.code)
    }

    @Test
    fun `unverified policy operation is reported as failure`() {
        val backend = FakeBackend(hidden = false, applySetHidden = false)

        val result = FactoryTmsManager.applyEnabled(backend, enabled = false)

        assertFalse(result.success)
        assertEquals(FactoryTmsState.ERROR, result.status.state)
        assertEquals("policy_verification_failed", result.code)
    }

    private class FakeBackend(
        private val installed: Boolean = true,
        private val deviceOwner: Boolean = true,
        private var hidden: Boolean = false,
        private val enabled: Boolean = true,
        private val acceptSetHidden: Boolean = true,
        private val applySetHidden: Boolean = true,
    ) : FactoryTmsPolicyBackend {
        val hiddenRequests = mutableListOf<Boolean>()

        override fun isInstalled(): Boolean = installed

        override fun isDeviceOwner(): Boolean = deviceOwner

        override fun isHidden(): Boolean = hidden

        override fun isEnabled(): Boolean = enabled

        override fun setHidden(hidden: Boolean): Boolean {
            hiddenRequests += hidden
            if (acceptSetHidden && applySetHidden) {
                this.hidden = hidden
            }
            return acceptSetHidden
        }
    }
}
