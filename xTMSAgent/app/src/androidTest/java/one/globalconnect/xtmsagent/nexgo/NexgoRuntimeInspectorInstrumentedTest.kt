package one.globalconnect.xtmsagent.nexgo

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.globalconnect.xtmsagent.diagnostics.NexgoDiagnosticsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NexgoRuntimeInspectorInstrumentedTest {
    @Test
    fun knownNexgoDeviceProducesVerifiedRuntimeSnapshot() {
        val snapshot = NexgoRuntimeInspector.inspect(ApplicationProvider.getApplicationContext())

        Log.i(TAG, snapshot.toJson().toString())
        assertTrue(snapshot.profile.isKnownModel)
        if (snapshot.profile.modelKey != "N6ProLite") {
            assertTrue(snapshot.profile.commandProfileVerified)
        }
        assertNotNull(snapshot.profile.display)
        assertTrue(snapshot.pss.installed)
        assertEquals(64, snapshot.pss.apkSha256?.length)
    }

    @Test
    fun fieldDiagnosticReportIsPersistedWithoutSensitiveControlData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modelKey = NexgoRuntimeInspector.inspect(context).profile.modelKey

        val file = NexgoDiagnosticsManager.refresh(context)
        val report = NexgoDiagnosticsManager.readDisplayReport(context)

        assertTrue(file.isFile)
        assertTrue(report.contains(modelKey))
        assertTrue(!report.contains("privateKey", ignoreCase = true))
        assertTrue(report.contains("commandEnvironment"))
        assertTrue(report.contains("screen"))
        assertTrue(!report.contains("DOWNLOAD_CREDENTIAL_SECRET"))
    }

    @Test
    fun sharingDoesNotAppendEventsBackIntoTheSnapshot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        NexgoDiagnosticsManager.shareFile(context)
        NexgoDiagnosticsManager.shareFile(context)
        val report = NexgoDiagnosticsManager.readDisplayReport(context)
        assertEquals(1, Regex("Recent diagnostic events").findAll(report).count())
    }

    @Test
    fun downloadsExportContainsFreshSnapshotAndEvents() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val exported = NexgoDiagnosticsManager.exportToPublicDownloads(context)
        try {
            val content = context.contentResolver.openInputStream(exported.uri)!!
                .bufferedReader().use { it.readText() }
            assertTrue(exported.displayPath.startsWith("Download/xTMSAgent/"))
            assertTrue(content.contains("\"reportSchemaVersion\": 3"))
            assertTrue(content.contains("Recent diagnostic events"))
        } finally {
            context.contentResolver.delete(exported.uri, null, null)
        }
    }

    private companion object {
        const val TAG = "NexgoRuntimeTest"
    }
}
