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
        assertTrue(snapshot.profile.commandProfileVerified)
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
        assertTrue(!report.contains("executeCmd", ignoreCase = true))
        assertTrue(!report.contains("commandBase", ignoreCase = true))
    }

    private companion object {
        const val TAG = "NexgoRuntimeTest"
    }
}
