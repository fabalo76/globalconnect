package one.globalconnect.xtmsagent.profiles
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class DeviceProfileWorkerTest {
    @Test fun mixedProfileRetainsSupportedSettingsAndReportsSkippedReasons() {
        val results = JSONObject()
        val supported = DeviceProfileWorker.selectSupportedSettings(linkedMapOf(
            "automaticBrightness" to null, "brightnessPercent" to null,
            "screenTimeoutSeconds" to "screen_timeout_not_supported",
            "mobileDataEnabled" to "firmware_setting_unsupported", "wifiEnabled" to null,
        ), results)
        assertEquals(listOf("automaticBrightness", "brightnessPercent", "wifiEnabled"), supported)
        assertEquals("skipped_firmware_setting_unsupported", results.getString("mobileDataEnabled"))
        assertEquals("skipped_screen_timeout_not_supported", results.getString("screenTimeoutSeconds"))
        assertFalse(results.has("brightnessPercent"))
    }
    @Test fun whollyUnsupportedProfileHasNoWritesAndKeepsEveryReason() {
        val results = JSONObject()
        assertTrue(DeviceProfileWorker.selectSupportedSettings(linkedMapOf(
            "brightnessPercent" to "device_owner_required", "mediaVolumePercent" to "fixed_volume",
        ), results).isEmpty())
        assertEquals(2, results.length())
        assertEquals("skipped_device_owner_required", results.getString("brightnessPercent"))
        assertEquals("skipped_fixed_volume", results.getString("mediaVolumePercent"))
    }
    @Test fun exitsAirplaneModeBeforeEnablingRadiosAndEntersItLast() {
        assertEquals(listOf("airplaneModeEnabled", "wifiEnabled", "mobileDataEnabled"),
            DeviceProfileWorker.orderedKeys(JSONObject("{\"wifiEnabled\":true,\"mobileDataEnabled\":true,\"airplaneModeEnabled\":false}")))
        assertEquals(listOf("wifiEnabled", "airplaneModeEnabled"),
            DeviceProfileWorker.orderedKeys(JSONObject("{\"wifiEnabled\":false,\"airplaneModeEnabled\":true}")))
    }
    @Test fun acceptsTypedRadioSettingsButRejectsUnknownCommands() {
        assertTrue(DeviceProfileWorker.validSettings(JSONObject("{\"wifiEnabled\":false,\"bluetoothEnabled\":true}")))
        assertFalse(DeviceProfileWorker.validSettings(JSONObject("{\"wifiEnabled\":1}")))
        assertFalse(DeviceProfileWorker.validSettings(JSONObject("{\"executeCmd\":\"anything\"}")))
        assertFalse(DeviceProfileWorker.validSettings(JSONObject("{\"airplaneModeEnabled\":true,\"mobileDataEnabled\":true}")))
    }
    @Test fun requiresManualBrightnessForFixedValue() {
        assertFalse(DeviceProfileWorker.validSettings(JSONObject("{\"brightnessPercent\":50}")))
        assertTrue(DeviceProfileWorker.validSettings(JSONObject("{\"brightnessPercent\":50,\"automaticBrightness\":false}")))
    }
    @Test fun rejectsCoercedNumbersAndUnknownTimeZones() {
        assertFalse(DeviceProfileWorker.validSettings(JSONObject("{\"screenTimeoutSeconds\":\"60\"}")))
        assertFalse(DeviceProfileWorker.validSettings(JSONObject("{\"timeZone\":\"Invalid/Zone\",\"automaticTimeZone\":false}")))
    }
    @Test fun acceptsVerifiedBaseline() {
        assertTrue(DeviceProfileWorker.validSettings(JSONObject("{\"screenTimeoutSeconds\":60,\"timeZone\":\"America/Costa_Rica\",\"automaticTimeZone\":false,\"mediaVolumePercent\":50}")))
    }
    @Test fun acceptsAndroidUtcOffsetTimeZones() {
        assertTrue(DeviceProfileWorker.validTimeZone("Etc/GMT+6"))
        assertTrue(DeviceProfileWorker.validTimeZone("Asia/Kolkata"))
        assertFalse(DeviceProfileWorker.validTimeZone("GMT-06:00"))
    }
    @Test fun ignoresUnpairedVendorTimeoutValues() {
        val values = arrayOf("15000", "30000", "60000", "120000", "300000", "600000", "1800000", "2147483000")
        assertEquals(setOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000), DeviceProfileWorker.pairedTimeoutValues(7, values))
    }
}
