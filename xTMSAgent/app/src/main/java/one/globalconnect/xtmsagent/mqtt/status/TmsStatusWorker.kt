package one.globalconnect.xtmsagent.mqtt.status

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import one.globalconnect.xtmsagent.BuildConfig
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.mqtt.persistence.TmsCredentialStore
import one.globalconnect.xtmsagent.nexgo.NexgoRuntimeInspector
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TmsStatusWorker"

/**
 * WorkManager Worker that publishes a JSON alive report to the TMS MQTT broker
 * every ~20 minutes (within the 15-30 min window specified in the integration contract).
 *
 * JSON format: {"s":1,"sig":75,"bat":90,"ver":"xTMSAgent"}
 *   s   : 1 = operational, 0 = degraded
 *   sig : signal strength 0-100
 *   bat : battery 0-100, or -1 = wired/AC power
 *   ver : application version string
 *
 * Topic: tms/device/{TermID}/heartbeat at QoS 0 (fire-and-forget).
 * QoS 0 is correct here — a missed heartbeat is covered by the next one.
 */
class TmsStatusWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val payload = buildStatusPayload()
            TmsMqttManager.publishStatus(payload)
            Log.d(TAG, "Status published: ${String(payload)}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Status publish failed: ${e.message}")
            Result.retry()
        }
    }

    // Periodic heartbeat — lightweight, no apps/os/mdl to keep radio overhead low.
    private fun buildStatusPayload(): ByteArray {
        val json = JSONObject().apply {
            put("s",   1)
            put("sig", readSignalStrength(applicationContext))
            put("bat", readBatteryLevel(applicationContext))
            put("ver", BuildConfig.VERSION_NAME)
            // blk=1  while blocked (server records acknowledgment timestamp).
            // blk=0  exactly once after a self-unlock (clears block on server DB).
            // Omit   entirely otherwise.
            readLocalIpAddress()?.let { put("pip", it) }
            put("net", readNetworkMedia(applicationContext))
            val store = TmsCredentialStore(applicationContext)
            when {
                store.isSelfUnlockPending() -> {
                    put("blk", 0)
                    store.saveSelfUnlockPending(false)
                }
                store.isBlocked() -> put("blk", 1)
            }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val WORK_NAME = "TmsStatusReport"

        /**
         * Schedules the periodic status report with WorkManager.
         * Idempotent — safe to call on every service start; KEEP policy prevents duplicates.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<TmsStatusWorker>(
                repeatInterval = TMSFunc.mqttCfg.status_interval.toLong(),
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Status report scheduled (every 20 min)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Builds a full status payload including os, mdl, and installed app inventory.
         *
         * Called on-demand when:
         *   1. The TMS broker sends a `report_status` command (stale/missing app data).
         *   2. An app is installed or uninstalled (InstallReceiver triggers this).
         *
         * The periodic heartbeat [buildStatusPayload] intentionally omits these heavy fields;
         * the server uses the 7-day [LastAppsReportOn] window to avoid redundant requests.
         */
        fun buildFullPayload(context: Context): ByteArray {
            val json = JSONObject().apply {
                put("s",   1)
                put("sig", readSignalStrength(context))
                put("bat", readBatteryLevel(context))
                put("ver", BuildConfig.VERSION_NAME)
                put("os",  "Android ${Build.VERSION.RELEASE}")
                put("mdl", Build.MODEL)
                put("nexgo", NexgoRuntimeInspector.inspect(context).toJson())
                val apps = buildInstalledAppsString(context)
                put("apps", apps)
                // Device identity fields (only in full payload, not periodic heartbeat)
                readDeviceSerial(context)?.let { put("sn", it) }
                readImei(context, 0)?.let { put("imei1", it) }
                readImei(context, 1)?.let { put("imei2", it) }
                readIccid(context, 0)?.let { put("iccid1", it) }
                readIccid(context, 1)?.let { put("iccid2", it) }
                readLastLocation(context)?.let { (lat, lng) ->
                    put("lat", lat)
                    put("lng", lng)
                }
                readLocalIpAddress()?.let { put("pip", it) }
                put("net", readNetworkMedia(context))
                val store = TmsCredentialStore(context)
                when {
                    store.isSelfUnlockPending() -> {
                        put("blk", 0)
                        store.saveSelfUnlockPending(false)
                    }
                    store.isBlocked() -> put("blk", 1)
                }
            }
            val jsonStr = json.toString()
            Log.d(TAG, "Full status payload: $jsonStr")
            return jsonStr.toByteArray(Charsets.UTF_8)
        }

        /**
         * Returns the hardware serial number from the Nexgo device SDK.
         * Falls back to the serial already provisioned in the encrypted credential store.
         */
        private fun readDeviceSerial(context: Context): String? {
            return try {
                val info = com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(context).getDeviceInfo()
                info?.sn?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.w(TAG, "Device serial (Nexgo SDK) unavailable: ${e.message}")
                runCatching { TmsCredentialStore(context).loadTermId() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
        }

        /**
         * Returns the IMEI for [slotIndex] (0 = SIM 1, 1 = SIM 2).
         * Requires READ_PHONE_STATE (or READ_PRIVILEGED_PHONE_STATE on Android 10+).
         * Returns null if the permission is denied or the slot has no SIM.
         */
        @SuppressLint("HardwareIds", "MissingPermission")
        private fun readImei(context: Context, slotIndex: Int): String? {
            return try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tm.getImei(slotIndex)
                } else {
                    if (slotIndex == 0) @Suppress("DEPRECATION") tm.deviceId else null
                }
                imei?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                Log.w(TAG, "IMEI[$slotIndex] unavailable (permission denied): ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "IMEI[$slotIndex] unavailable: ${e.message}")
                null
            }
        }

        /**
         * Returns the ICCID for [slotIndex] (0 = SIM 1, 1 = SIM 2) via [SubscriptionManager].
         * Requires READ_PHONE_STATE.
         * Returns null if the slot has no active SIM or the permission is denied.
         */
        @SuppressLint("MissingPermission")
        private fun readIccid(context: Context, slotIndex: Int): String? {
            return try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as SubscriptionManager
                @Suppress("DEPRECATION")
                val info = sm.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
                info?.iccId?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                Log.w(TAG, "ICCID[$slotIndex] unavailable (permission denied): ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "ICCID[$slotIndex] unavailable: ${e.message}")
                null
            }
        }

        /** Normalises WiFi RSSI to 0-100. Returns 0 on cellular or if unreadable. */
        fun readSignalStrength(context: Context): Int {
            return try {
                @Suppress("DEPRECATION")
                val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val rssi = wm.connectionInfo.rssi
                @Suppress("DEPRECATION")
                WifiManager.calculateSignalLevel(rssi, 101)
            } catch (e: Exception) {
                Log.w(TAG, "Signal strength unavailable: ${e.message}")
                0
            }
        }

        /** Returns battery percentage 0-100, or -1 if the device is on wired/AC power. */
        fun readBatteryLevel(context: Context): Int {
            return try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL) {
                    -1
                } else {
                    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Battery level unavailable: ${e.message}")
                -1
            }
        }

        /**
         * Returns the device's first active non-loopback IPv4 address, or null.
         * No permissions required — reads from network interface list.
         */
        private fun readLocalIpAddress(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces()
                    ?.asSequence()
                    ?.filter { it.isUp && !it.isLoopback }
                    ?.flatMap { it.inetAddresses.asSequence() }
                    ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                    ?.hostAddress
            } catch (e: Exception) {
                Log.w(TAG, "Local IP unavailable: ${e.message}")
                null
            }
        }

        /**
         * Returns the active network transport type as a short string.
         * Values: "wifi", "ethernet", "cellular1" (SIM slot 0), "cellular2" (SIM slot 1),
         * "cellular" (slot undetermined), "none".
         * Requires ACCESS_NETWORK_STATE (normal permission, no runtime grant needed).
         */
        @SuppressLint("MissingPermission")
        private fun readNetworkMedia(context: Context): String {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "none")
                        ?: return "none"
                    when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                            try {
                                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                                        as SubscriptionManager
                                val subId = SubscriptionManager.getDefaultDataSubscriptionId()
                                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return "cellular"
                                when (sm.getActiveSubscriptionInfo(subId)?.simSlotIndex) {
                                    0 -> "cellular1"
                                    1 -> "cellular2"
                                    else -> "cellular"
                                }
                            } catch (_: Exception) { "cellular" }
                        }
                        else -> "none"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    when (cm.activeNetworkInfo?.type) {
                        ConnectivityManager.TYPE_WIFI     -> "wifi"
                        ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                        ConnectivityManager.TYPE_MOBILE   -> "cellular"
                        else -> "none"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network media unavailable: ${e.message}")
                "none"
            }
        }

        // GPS cold-start indoors can take >60 s; network provider fixes in 1-2 s.
        // We register all providers simultaneously and take whichever responds first.
        /**
         * Returns the device's current location as (latitude, longitude), or null.
         *
         * 1. [TmsLocationTracker] — instant, continuously updated since service start.
         * 2. System cache via [LocationManager.getLastKnownLocation] across all providers.
         * 3. Blocking single-shot request (max [LOC_FALLBACK_TIMEOUT_MS]) registered on
         *    ALL enabled providers simultaneously — whichever fires first wins.
         *    This only runs when both the tracker and the system cache are empty, i.e.
         *    the very first status report on a cold-started device.
         */
        private const val LOC_FALLBACK_TIMEOUT_MS = 8_000L

        @SuppressLint("MissingPermission")
        private fun readLastLocation(context: Context): Pair<Double, Double>? {
            // ── 1. Continuously tracked (instant) ─────────────────────────────
            TmsLocationTracker.lastLocation?.let {
                Log.d(TAG, "GPS (tracker): lat=${it.first}, lng=${it.second}")
                return it
            }

            // ── Permission check before touching LocationManager ───────────────
            val hasFine   = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                Log.w(TAG, "GPS skipped: location permission not granted at runtime.")
                return null
            }

            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

            Log.d(TAG, "GPS tracker empty — enabled providers: $providers")

            // ── 2. System last-known cache ────────────────────────────────────
            providers
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { it.accuracy >= 0f }           // 0 = unknown accuracy, still usable
                .minByOrNull { it.accuracy }
                ?.let { loc ->
                    Log.d(TAG, "GPS (system cache): lat=${loc.latitude}, lng=${loc.longitude}, " +
                            "acc=${loc.accuracy}m, provider=${loc.provider}")
                    return Pair(loc.latitude, loc.longitude)
                }

            if (providers.isEmpty()) {
                Log.w(TAG, "GPS: no providers enabled.")
                return null
            }

            // ── 3. Blocking single-shot on all providers — first fix wins ─────
            Log.d(TAG, "GPS: cache empty, single-shot request on $providers (max ${LOC_FALLBACK_TIMEOUT_MS}ms)…")
            val latch    = CountDownLatch(1)
            var result: Location? = null
            val won      = AtomicBoolean(false)
            val ht       = HandlerThread("TmsGpsFallback").also { it.start() }
            val listeners = mutableListOf<LocationListener>()

            val onFix: (Location) -> Unit = { loc ->
                if (won.compareAndSet(false, true)) { result = loc; latch.countDown() }
            }

            try {
                for (provider in providers) {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) = onFix(loc)
                        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }
                    runCatching {
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(provider, listener, ht.looper)
                        listeners += listener
                    }
                }
                latch.await(LOC_FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } finally {
                listeners.forEach { runCatching { lm.removeUpdates(it) } }
                ht.quit()
            }

            return result?.let {
                Log.d(TAG, "GPS (single-shot): lat=${it.latitude}, lng=${it.longitude}, " +
                        "acc=${it.accuracy}m, provider=${it.provider}")
                Pair(it.latitude, it.longitude)
            } ?: run {
                Log.w(TAG, "GPS: no fix in ${LOC_FALLBACK_TIMEOUT_MS}ms. " +
                        "Providers tried: $providers. Check Location mode = High accuracy.")
                null
            }
        }

        /**
         * Returns a semicolon-separated inventory of managed apps on the device.
         * Format: "packageName|appLabel|versionName;..."
         * Fields use '|' as separator; '|' and ';' are stripped from labels.
         * Truncated to 2000 chars to fit the DB column [TermMain.AppsInstalled NVARCHAR(2000)].
         *
         * Includes both:
         *  - Pure user-installed apps (no FLAG_SYSTEM)
         *  - System apps that have been updated (FLAG_SYSTEM + FLAG_UPDATED_SYSTEM_APP)
         *    Common on POS terminals where payment/TMS apps are pre-loaded as system apps
         *    but updated by TMS HouseKeeping.
         */
        private fun buildInstalledAppsString(context: Context): String {
            return try {
                val pm = context.packageManager
                val all = pm.getInstalledPackages(0)
                val managed = all.filter { pkg ->
                    if (pkg.packageName == context.packageName) {
                        Log.d(TAG, "App inventory include active launcher package: ${pkg.packageName} version=${pkg.versionName ?: "?"}")
                        return@filter true
                    }
                    val flags = pkg.applicationInfo?.flags ?: return@filter false
                    // Include pure user apps OR system apps updated post-factory
                    (flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                }
                Log.d(TAG, "App inventory: ${managed.size} managed / ${all.size} total installed")
                managed
                    .joinToString(";") { pkg ->
                        val flags = pkg.applicationInfo?.flags ?: 0
                        val rawLabel = try {
                            pm.getApplicationLabel(pkg.applicationInfo!!).toString()
                        } catch (_: Exception) { pkg.packageName }
                        // Strip field/record separators so the format stays unambiguous
                        val label = rawLabel.replace("|", " ").replace(";", " ").trim()
                        Log.d(
                            TAG,
                            "App inventory include: package=${pkg.packageName} label=$label version=${pkg.versionName ?: "?"} flags=$flags"
                        )
                        "${pkg.packageName}|${label}|${pkg.versionName ?: "?"}"
                    }
                    .take(2000)
            } catch (e: Exception) {
                Log.w(TAG, "Could not enumerate installed apps: ${e.message}")
                ""
            }
        }

    }
}
