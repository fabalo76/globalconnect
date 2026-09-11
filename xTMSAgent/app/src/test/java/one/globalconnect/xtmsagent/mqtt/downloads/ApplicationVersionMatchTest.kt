package one.globalconnect.xtmsagent.mqtt.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationVersionMatchTest {
    @Test
    fun `same positive version skips the download`() {
        assertTrue(isSameApplicationVersion(installedVersionCode = 58, requestedVersionCode = 58))
    }

    @Test
    fun `newer installed version does not satisfy a downgrade request`() {
        assertFalse(isSameApplicationVersion(installedVersionCode = 59, requestedVersionCode = 58))
    }

    @Test
    fun `older installed version does not satisfy an upgrade request`() {
        assertFalse(isSameApplicationVersion(installedVersionCode = 57, requestedVersionCode = 58))
    }

    @Test
    fun `lower requested version selects the privileged rollback path`() {
        assertTrue(isApplicationDowngrade(installedVersionCode = 60, requestedVersionCode = 58))
        assertFalse(isApplicationDowngrade(installedVersionCode = 58, requestedVersionCode = 58))
        assertFalse(isApplicationDowngrade(installedVersionCode = 57, requestedVersionCode = 58))
        assertFalse(isApplicationDowngrade(installedVersionCode = null, requestedVersionCode = 58))
    }

    @Test
    fun `missing or invalid requested version does not skip the download`() {
        assertFalse(isSameApplicationVersion(installedVersionCode = 58, requestedVersionCode = null))
        assertFalse(isSameApplicationVersion(installedVersionCode = 58, requestedVersionCode = 0))
        assertFalse(isSameApplicationVersion(installedVersionCode = null, requestedVersionCode = 58))
    }
}
