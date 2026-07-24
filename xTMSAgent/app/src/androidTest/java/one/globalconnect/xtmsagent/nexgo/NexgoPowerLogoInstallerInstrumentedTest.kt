package one.globalconnect.xtmsagent.nexgo

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NexgoPowerLogoInstallerInstrumentedTest {
    @Test
    fun installsValidatedPowerLogoWhenMutationIsExplicitlyEnabled() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowNexgoMutation") == "true")

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profile = NexgoRuntimeInspector.inspect(context).profile
        assertEquals(arguments.getString("expectedModel") ?: "CT20P", profile.modelKey)

        val sourceFile = File(
            requireNotNull(context.getExternalFilesDir("lab")),
            NexgoSystemAsset.POWER_LOGO.fixedFileName(profile),
        )
        val expectedSha256 = requireNotNull(arguments.getString("powerLogoSha256"))
        assertTrue(sourceFile.isFile)

        val validation = NexgoSystemAssetValidator.validatePowerLogo(
            profile,
            sourceFile,
            expectedSha256,
        )
        assertTrue("Validation result: $validation", validation.isValid)

        val result = runBlocking {
            NexgoSystemAssetInstaller.installPowerLogo(
                context = context,
                sourceFile = sourceFile,
                expectedSha256 = expectedSha256,
            )
        }

        assertTrue("Installer result: $result", result.success)
        assertEquals("installed", result.code)
        assertEquals(0, result.sdkResultCode)
    }
}
