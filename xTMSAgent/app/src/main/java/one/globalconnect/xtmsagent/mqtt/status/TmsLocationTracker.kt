package one.globalconnect.xtmsagent.mqtt.status

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "TmsLocationTracker"

// Request updates every 3 minutes OR every 50 m, whichever comes first.
// Network provider typically fires within seconds of starting — GPS when outdoors.
private const val MIN_TIME_MS  = 3 * 60 * 1_000L   // 3 min
private const val MIN_DIST_M   = 50f                // 50 m

/**
 * Singleton that continuously tracks device location from the moment
 * [TmsMqttService] starts, so that [TmsStatusWorker.buildFullPayload] can read
 * a fresh coordinate instantly instead of blocking for a GPS fix on demand.
 *
 * Both GPS and Network providers are registered simultaneously:
 *  - Network (cell + WiFi) responds in seconds and works indoors.
 *  - GPS provides higher accuracy when the device is outdoors.
 *
 * The best fix is kept using a simple accuracy/age policy:
 *  - Always accept if no prior fix exists.
 *  - Accept if the new fix is more accurate.
 *  - Accept if the new fix is at least 2 minutes newer (keeps location fresh
 *    even when it is slightly less accurate than a stale GPS fix).
 */
object TmsLocationTracker {

    /** Last known location, or null if no fix has been received yet. */
    @Volatile private var _lastLocation: Location? = null

    val lastLocation: Pair<Double, Double>?
        get() = _lastLocation?.let { Pair(it.latitude, it.longitude) }

    private var handlerThread: HandlerThread? = null
    private val registered   = mutableListOf<LocationListener>()
    private var lm: LocationManager? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts continuous location tracking.
     * Must be called from [one.globalconnect.xtmsagent.mqtt.TmsMqttService.onCreate].
     * Safe to call multiple times — no-ops if already running.
     */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (handlerThread != null) return   // already running

        val hasFine   = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permission not granted — tracker not started. " +
                    "Grant ACCESS_FINE_LOCATION in Settings → Apps → xTMSAgent → Permissions.")
            return
        }

        val mgr = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = mgr

        val ht = HandlerThread("TmsLocationTracker").also { it.start() }
        handlerThread = ht

        // All three providers — PASSIVE piggybacks on any app's fix (e.g. Geobridge)
        // with zero battery overhead; GPS/Network do active acquisition.
        val allProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val enabledProviders = allProviders
            .filter { runCatching { mgr.isProviderEnabled(it) }.getOrDefault(false) }

        Log.i(TAG, "Available providers: enabled=$enabledProviders, all=$allProviders")

        if (enabledProviders.isEmpty()) {
            Log.w(TAG, "No location providers are enabled on this device.")
            ht.quit(); handlerThread = null
            return
        }

        // Seed immediately from system cache across all providers (non-blocking)
        val cached = allProviders
            .mapNotNull { runCatching { mgr.getLastKnownLocation(it) }.getOrNull() }
            .filter { it.accuracy > 0f }
            .minByOrNull { it.accuracy }
        if (cached != null) {
            _lastLocation = cached
            Log.i(TAG, "Seeded from cache: lat=${cached.latitude}, lng=${cached.longitude}, " +
                    "acc=${cached.accuracy}m, provider=${cached.provider}")
        } else {
            Log.i(TAG, "Cache empty — waiting for first provider fix.")
        }

        // Register continuous listeners on every enabled provider.
        // PASSIVE_PROVIDER uses minTime=0/minDist=0 (it only receives, never requests).
        for (provider in enabledProviders) {
            val listener = makeListener(provider)
            val minT = if (provider == LocationManager.PASSIVE_PROVIDER) 0L else MIN_TIME_MS
            val minD = if (provider == LocationManager.PASSIVE_PROVIDER) 0f else MIN_DIST_M
            try {
                mgr.requestLocationUpdates(provider, minT, minD, listener, ht.looper)
                registered += listener
                Log.i(TAG, "Tracking started on $provider " +
                        "(minTime=${minT / 1000}s, minDist=${minD}m)")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot track on $provider: ${e.message}")
            }
        }
    }

    /**
     * Stops location tracking.
     * Must be called from [one.globalconnect.xtmsagent.mqtt.TmsMqttService.onDestroy].
     */
    fun stop() {
        val mgr = lm ?: return
        registered.forEach { runCatching { mgr.removeUpdates(it) } }
        registered.clear()
        handlerThread?.quit()
        handlerThread = null
        lm = null
        Log.i(TAG, "Tracking stopped.")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun makeListener(providerName: String) = object : LocationListener {

        override fun onLocationChanged(loc: Location) {
            val prev = _lastLocation
            if (isBetter(loc, prev)) {
                _lastLocation = loc
                Log.d(TAG, "Location [$providerName]: " +
                        "lat=${loc.latitude}, lng=${loc.longitude}, acc=${loc.accuracy}m")
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "Provider disabled: $provider")
        }
    }

    /**
     * Returns true if [candidate] is better than [current]:
     *  - Always accept if there is no current fix.
     *  - Accept if candidate is more accurate.
     *  - Accept if current fix is older than 2 minutes (keeps data fresh).
     */
    private fun isBetter(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        if (candidate.accuracy < current.accuracy) return true
        val ageMs = candidate.time - current.time
        if (ageMs > 2 * 60 * 1_000L) return true   // current is >2 min stale
        return false
    }
}
