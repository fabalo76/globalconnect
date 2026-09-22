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
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
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
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TmsStatusWorker"

private data class NetworkTelemetry(
    val activeTechnology: String,
    val activeSignalPercent: Int?,
    val wifiMacAddress: String?,
    val ethernetMacAddress: String?,
    val wifiSsid: String?,
    val wifiRssi: Int?,
    val wifiSignalPercent: Int?,
    val cellOperator: String?,
    val cellNetworkType: String?,
    val cellSignalDbm: Int?,
    val cellSignalPercent: Int?,
)

/**
 * WorkManager Worker that publishes a JSON alive report to the TMS MQTT broker
 * every ~20 minutes (within the 15-30 min window specified in the integration contract).
 *
 * JSON format: {"s":1,"sig":75,"bat":90,"ver":"xTMSAgent","net":"wifi"}
 *   s   : 1 = operational, 0 = degraded
 *   sig : active WiFi or cellular signal strength 0-100
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
            if (TmsMqttManager.publishStatus(payload).get(30, TimeUnit.SECONDS)) {
                Log.d(TAG, "Heartbeat sent")
                Result.success()
            } else {
                Log.w(TAG, "Heartbeat was not sent; retrying")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Status publish failed: ${e.message}")
            Result.retry()
        }
    }

    // Periodic heartbeat — lightweight, no apps/os/mdl to keep radio overhead low.
    private fun buildStatusPayload(): ByteArray {
        val json = JSONObject().apply {
            val network = readNetworkTelemetry(applicationContext)
            put("s",   1)
            put("sig", network.activeSignalPercent ?: 0)
            put("bat", readBatteryLevel(applicationContext))
            put("ver", BuildConfig.VERSION_NAME)
            // blk=1  while blocked (server records acknowledgment timestamp).
            // blk=0  exactly once after a self-unlock (clears block on server DB).
            // Omit   entirely otherwise.
            readLocalIpAddress()?.let { put("pip", it) }
            putNetworkTelemetry(this, network)
            putTrafficCounters(this)
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

        fun publishOnConnection(context: Context) {
            // The periodic worker can run before MQTT connects during boot.
            val request = OneTimeWorkRequestBuilder<TmsStatusWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}OnConnection",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

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
                val network = readNetworkTelemetry(context)
                put("s",   1)
                put("sig", network.activeSignalPercent ?: 0)
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
                putNetworkTelemetry(this, network)
                putTrafficCounters(this)
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

        /** Returns battery percentage 0-100, or -1 when the hardware has no readable battery. */
        fun readBatteryLevel(context: Context): Int {
            return try {
                if (isBatterylessModel(Build.MODEL)) return -1
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) == false) return -1
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
            } catch (e: Exception) {
                Log.w(TAG, "Battery level unavailable: ${e.message}")
                -1
            }
        }

        internal fun isBatterylessModel(model: String?): Boolean {
            val normalized = model?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return normalized == "CT20" || normalized.endsWith(" CT20")
        }

        private fun putTrafficCounters(json: JSONObject) {
            val totalRx = TrafficStats.getTotalRxBytes()
            val totalTx = TrafficStats.getTotalTxBytes()
            val mobileRx = TrafficStats.getMobileRxBytes()
            val mobileTx = TrafficStats.getMobileTxBytes()
            if (totalRx < 0 || totalTx < 0 || mobileRx < 0 || mobileTx < 0) {
                Log.w(TAG, "Device traffic counters unavailable")
                return
            }

            json.put("trx", totalRx)
            json.put("ttx", totalTx)
            json.put("mrx", mobileRx)
            json.put("mtx", mobileTx)
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
        @SuppressLint("MissingPermission", "HardwareIds")
        private fun readNetworkTelemetry(context: Context): NetworkTelemetry {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            val wifiActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val ethernetActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val cellularActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val interfaceMacAddresses = readInterfaceMacAddresses()
            var wifiMacAddress = interfaceMacAddresses.first
            var ethernetMacAddress = interfaceMacAddresses.second

            var wifiSsid: String? = null
            var wifiRssi: Int? = null
            var wifiSignalPercent: Int? = null
            try {
                @Suppress("DEPRECATION")
                val info = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                wifiMacAddress = wifiMacAddress ?: normalizeMacAddress(info.macAddress)
                if (wifiActive) {
                    wifiSsid = info.ssid
                        ?.trim('"')
                        ?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
                    wifiRssi = info.rssi.takeIf { it in -127..0 }
                    @Suppress("DEPRECATION")
                    wifiSignalPercent = wifiRssi?.let { WifiManager.calculateSignalLevel(it, 101) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "WiFi telemetry unavailable: ${e.message}")
            }

            val vendorMacAddresses = parseNexgoNetworkMacProperty(readSystemProperty("ro.xgd.wifibt.mac"))
            wifiMacAddress = wifiMacAddress ?: vendorMacAddresses.first
            ethernetMacAddress = ethernetMacAddress ?: vendorMacAddresses.second

            var cellOperator: String? = null
            var cellNetworkType: String? = null
            var cellSignalDbm: Int? = null
            var cellSignalPercent: Int? = null
            try {
                val baseManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId()
                val manager = if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    baseManager.createForSubscriptionId(subscriptionId)
                } else {
                    baseManager
                }
                cellOperator = manager.networkOperatorName?.trim()?.takeIf { it.isNotBlank() }
                cellNetworkType = cellularGeneration(manager.dataNetworkType)
                manager.signalStrength?.let { strength ->
                    cellSignalPercent = (strength.level.coerceIn(0, 4) * 25)
                    cellSignalDbm = strength.cellSignalStrengths
                        .map { it.dbm }
                        .firstOrNull { it != android.telephony.CellInfo.UNAVAILABLE }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cellular telemetry unavailable: ${e.message}")
            }

            val activeTechnology = when {
                wifiActive -> "wifi"
                ethernetActive -> "ethernet"
                cellularActive -> cellNetworkType ?: "cellular"
                else -> "none"
            }
            val activeSignal = when {
                wifiActive -> wifiSignalPercent
                cellularActive -> cellSignalPercent
                else -> null
            }
            return NetworkTelemetry(
                activeTechnology,
                activeSignal,
                wifiMacAddress,
                ethernetMacAddress,
                wifiSsid,
                wifiRssi,
                wifiSignalPercent,
                cellOperator,
                cellNetworkType,
                cellSignalDbm,
                cellSignalPercent,
            )
        }

        private fun putNetworkTelemetry(json: JSONObject, telemetry: NetworkTelemetry) {
            json.put("net", telemetry.activeTechnology)
            telemetry.wifiMacAddress?.let { json.put("wmac", it) }
            telemetry.ethernetMacAddress?.let { json.put("emac", it) }
            telemetry.wifiSsid?.let { json.put("wssid", it) }
            telemetry.wifiRssi?.let { json.put("wrssi", it) }
            telemetry.wifiSignalPercent?.let { json.put("wsig", it) }
            telemetry.cellOperator?.let { json.put("cop", it) }
            telemetry.cellNetworkType?.let { json.put("cnet", it) }
            telemetry.cellSignalDbm?.let { json.put("cdbm", it) }
            telemetry.cellSignalPercent?.let { json.put("csig", it) }
        }

        /** Returns Wi-Fi and Ethernet hardware addresses from the kernel network interfaces. */
        private fun readInterfaceMacAddresses(): Pair<String?, String?> {
            var wifiMacAddress: String? = null
            var ethernetMacAddress: String? = null
            try {
                NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { networkInterface ->
                    val name = networkInterface.name?.lowercase(Locale.ROOT).orEmpty()
                    val address = formatMacAddress(networkInterface.hardwareAddress) ?: return@forEach
                    when {
                        wifiMacAddress == null && (name.startsWith("wlan") || name.startsWith("wifi")) ->
                            wifiMacAddress = address
                        ethernetMacAddress == null && name.startsWith("eth") ->
                            ethernetMacAddress = address
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network interface MAC addresses unavailable: ${e.message}")
            }
            return wifiMacAddress to ethernetMacAddress
        }

        internal fun formatMacAddress(address: ByteArray?): String? {
            if (address == null || address.size != 6 || address.all { it == 0.toByte() }) return null
            val formatted = address.joinToString(":") { byte -> "%02X".format(Locale.ROOT, byte.toInt() and 0xFF) }
            return formatted.takeUnless { it == "02:00:00:00:00:00" }
        }

        internal fun parseNexgoNetworkMacProperty(value: String?): Pair<String?, String?> {
            val compact = value?.filter(Char::isLetterOrDigit)?.uppercase(Locale.ROOT).orEmpty()
            if (compact.length < 36 || compact.any { it !in '0'..'9' && it !in 'A'..'F' }) return null to null
            return normalizeMacAddress(compact.substring(0, 12)) to normalizeMacAddress(compact.substring(24, 36))
        }

        private fun normalizeMacAddress(value: String?): String? {
            val compact = value?.filter(Char::isLetterOrDigit)?.uppercase(Locale.ROOT).orEmpty()
            if (compact.length != 12 || compact.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
            val formatted = compact.chunked(2).joinToString(":")
            return formatted.takeUnless { it == "00:00:00:00:00:00" || it == "02:00:00:00:00:00" }
        }

        private fun readSystemProperty(name: String): String? = try {
            ProcessBuilder("/system/bin/getprop", name)
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { it.readText().trim().takeIf(String::isNotBlank) }
        } catch (e: Exception) {
            Log.w(TAG, "System property $name unavailable: ${e.message}")
            null
        }

        internal fun cellularGeneration(networkType: Int): String? = when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> null
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
