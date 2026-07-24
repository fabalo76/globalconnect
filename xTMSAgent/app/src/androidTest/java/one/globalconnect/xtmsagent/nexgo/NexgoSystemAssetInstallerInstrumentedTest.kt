package one.globalconnect.xtmsagent.nexgo

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NexgoSystemAssetInstallerInstrumentedTest {
    @Test
    fun installsValidatedBootAnimationWhenMutationIsExplicitlyEnabled() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowNexgoMutation") == "true")

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profile = NexgoRuntimeInspector.inspect(context).profile
        assertEquals(arguments.getString("expectedModel") ?: "CT20P", profile.modelKey)

        val sourceFile = File(
            requireNotNull(context.getExternalFilesDir("lab")),
            NexgoSystemAsset.BOOT_ANIMATION.fixedFileName(profile),
        )
        val expectedSha256 = requireNotNull(arguments.getString("assetSha256"))
        assertTrue(sourceFile.isFile)

        val result = runBlocking {
            NexgoSystemAssetInstaller.installAnimation(
                context = context,
                asset = NexgoSystemAsset.BOOT_ANIMATION,
                sourceFile = sourceFile,
                expectedSha256 = expectedSha256,
            )
        }

        assertTrue("Installer result: $result", result.success)
        assertEquals("installed", result.code)
        assertEquals(0, result.sdkResultCode)
    }
}
