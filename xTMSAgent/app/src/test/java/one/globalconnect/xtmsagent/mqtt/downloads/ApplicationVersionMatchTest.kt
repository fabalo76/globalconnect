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
    fun `different version does not skip the download`() {
        assertFalse(isSameApplicationVersion(installedVersionCode = 59, requestedVersionCode = 58))
        assertFalse(isSameApplicationVersion(installedVersionCode = 57, requestedVersionCode = 58))
    }

    @Test
    fun `missing or invalid requested version does not skip the download`() {
        assertFalse(isSameApplicationVersion(installedVersionCode = 58, requestedVersionCode = null))
        assertFalse(isSameApplicationVersion(installedVersionCode = 58, requestedVersionCode = 0))
        assertFalse(isSameApplicationVersion(installedVersionCode = null, requestedVersionCode = 58))
    }
}
