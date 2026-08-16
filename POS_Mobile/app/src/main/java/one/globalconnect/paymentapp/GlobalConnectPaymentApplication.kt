package one.globalconnect.paymentapp

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import android.widget.Toast
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.platform.Platform
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.cardreader.nexgo.EmvConfigBuilder
import one.globalconnect.paymentapp.cardreader.nexgo.CapkEnvironment
import one.globalconnect.paymentapp.cardreader.nexgo.EmvUtils
import one.globalconnect.paymentapp.cardreader.nexgo.NexgoApi
import one.globalconnect.paymentapp.dao.AppContainer
import one.globalconnect.paymentapp.dao.AppDataContainer
import one.globalconnect.paymentapp.uicpos.pos.host.AcquirerSslCache
import one.globalconnect.paymentapp.uicpos.pos.host.BundledCertificates
import one.globalconnect.paymentapp.uicpos.pos.host.loadCertificatesFromStream
import one.globalconnect.paymentapp.transaction.BrandingAnimationCache
import one.globalconnect.paymentapp.utils.SoundManager
import one.globalconnect.paymentapp.utils.getSavedLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class GlobalConnectPaymentApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _appInitialized = MutableStateFlow(false)

    /**
     * Emits `true` once all heavy startup tasks (library load, TMS parse, SSL cache) complete.
     * Until then, MainActivity shows an initializing screen so the main thread stays free.
     */
    val appInitialized: StateFlow<Boolean> = _appInitialized.asStateFlow()

    private val _paramsReadyFlow = MutableStateFlow(false)

    /**
     * Emits `true` once TMS parameters have been loaded or applied.
     * Observed by MainActivity's Compose content to switch from the waiting
     * screen to the normal app UI without requiring an Activity recreate().
     */
    val paramsReadyFlow: StateFlow<Boolean> = _paramsReadyFlow.asStateFlow()

    private val _paramsUpdateEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Emits [Unit] every time a TMS parameter update is successfully applied.
     * Unlike [paramsReadyFlow] (which stays `true` after first load), this fires
     * on every update — used by the Initialize dialog to detect a fresh download.
     */
    val paramsUpdateEvent: SharedFlow<Unit> = _paramsUpdateEvent.asSharedFlow()

    /** Lazily created instance of the Nexgo device abstraction layer. */
    val deviceEngine: DeviceEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            APIProxy.getDeviceEngine(this)
        } catch (error: Throwable) {
            Toast.makeText(
                applicationContext,
                getString(
                    R.string.nexgo_device_engine_init_failed,
                    error.message?.let { ": $it" } ?: ""
                ),
                Toast.LENGTH_LONG
            ).show()
            throw error
        }
    }

    /** Lazily created instance of the Nexgo EMV facade. */
    val nexgoApi: NexgoApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NexgoApi(this, deviceEngine)
    }

    /** Lazily created instance of the platform abstraction. */
    val platform: Platform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            deviceEngine.platform
        } catch (error: Throwable) {
            Toast.makeText(
                applicationContext,
                getString(
                    R.string.nexgo_platform_init_failed,
                    error.message?.let { ": $it" } ?: ""
                ),
                Toast.LENGTH_LONG
            ).show()
            throw error
        }
    }

    /**
     * AppContainer instance used by the rest of classes to obtain dependencies
     */
    lateinit var container: AppContainer
    lateinit var tmsDatabase: TMSDATA // ✅ Cache TMS parameters in memory
    lateinit var appVersion: String // ✅ Cache app version in memory

    override fun onCreate() {
        super.onCreate()
        _instance = this

        applySavedLocale()

        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            appVersion = packageInfo.versionName ?: "Unknown"
        } catch (error: Exception) {
            appVersion = "Unknown"
        }

        Thread { SoundManager.init(this) }.start()

        // Heavy startup work runs on a background thread so the main thread is
        // free to render the initializing screen and animate the progress indicator.
        applicationScope.launch {
            System.loadLibrary("sqlcipher")
            tmsDatabase = loadTMSDatabase()
            PendingUpdateManager.restoreFromPrefs(this@GlobalConnectPaymentApplication)
            loadBundledCaCertificates()
            AcquirerSslCache.build(tmsDatabase.IPTab, BundledCertificates.get())
            container = AppDataContainer(this@GlobalConnectPaymentApplication, tmsDatabase, appVersion)

            val capkEnv = when (tmsDatabase.Terminal.firstOrNull()?.CAPKKEyConfig) {
                "02" -> CapkEnvironment.TESTING
                "03" -> CapkEnvironment.PRODUCTION_AND_TESTING
                else -> CapkEnvironment.PRODUCTION
            }
            EmvUtils.getCapkList(this@GlobalConnectPaymentApplication, capkEnv)
                ?.let { NexgoApi.applyCapkList(it) }
                ?: Log.w("GlobalConnectPaymentApplication", "No CAPK keys loaded for env=$capkEnv")

            // Signal params ready before marking init complete so the UI skips
            // the waiting screen on subsequent launches where params are cached.
            if (tmsDatabase.Terminal.isNotEmpty()) {
                val terminalOnlinePinCap = tmsDatabase.Terminal.first().onlinePinCap
                val aidList = EmvConfigBuilder.buildAidList(
                    tmsDatabase.AIDtab,
                    tmsDatabase.PCDApps,
                    terminalOnlinePinCap,
                )
                NexgoApi.applyEmvAidList(aidList, terminalOnlinePinCap)
                NexgoApi.applyAcquirerPinTypes(tmsDatabase.Acquirer.map { it.PINType })
                _paramsReadyFlow.value = true
                launch(Dispatchers.Main) {
                    BrandingAnimationCache.prewarm(this@GlobalConnectPaymentApplication, tmsDatabase)
                }
            }
            _appInitialized.value = true

            // On first install (no cached params) ask xTMSAgent to download them.
            // TmsParamsReceiver handles the reply and sets paramsReadyFlow = true.
            if (tmsDatabase.Terminal.isEmpty()) {
                requestParamsFromXtmsAgent()
            }
        }
    }

    /**
     * Broadcasts a parameter request to the xTMSAgent application.
     *
     * xTMSAgent discovers this app via the PAY_APP intent filter, downloads the
     * parameter JSON from the TMS server over MQTT + HTTP, and replies with
     * ACTION_PARAMS_READY (received by [TmsParamsReceiver]).
     *
     * Safe to call at any time — xTMSAgent guards against concurrent downloads.
     */
    fun requestParamsFromXtmsAgent() {
        // Discover whichever flavor of xTMSAgent is installed rather than hardcoding.
        // queryBroadcastReceivers finds every package that declares a receiver for this
        // action, then we send an explicit broadcast to each one found.
        val probe     = Intent("one.globalconnect.xtmsagent.ACTION_REQUEST_PARAMS")
        val receivers = packageManager.queryBroadcastReceivers(probe, 0)
        if (receivers.isEmpty()) {
            Log.w("GlobalConnectPaymentApplication", "ACTION_REQUEST_PARAMS: no xTMSAgent app installed — skipping")
            return
        }
        for (ri in receivers) {
            val homePackage = ri.activityInfo.packageName
            Log.i("GlobalConnectPaymentApplication", "Broadcasting ACTION_REQUEST_PARAMS → $homePackage")
            sendBroadcast(Intent("one.globalconnect.xtmsagent.ACTION_REQUEST_PARAMS").apply {
                `package` = homePackage
                putExtra(EXTRA_TMS_APPLICATION_ID, TMS_APPLICATION_ID_PAYMENT_APP)
            })
        }
    }

    private fun loadBundledCaCertificates() {
        try {
            val certs = resources.openRawResource(R.raw.trusted_cas).use { loadCertificatesFromStream(it) }
            if (certs.isEmpty()) {
                Log.i("GlobalConnectPaymentApplication", "trusted_cas.crt is empty — no bundled CA certificates loaded")
            } else {
                BundledCertificates.set(certs)
                Log.i("GlobalConnectPaymentApplication", "Loaded ${certs.size} bundled CA certificate(s) from trusted_cas.crt:")
                certs.forEachIndexed { i, cert ->
                    Log.i("GlobalConnectPaymentApplication", "  [$i] subject=${cert.subjectX500Principal.name}")
                    Log.i("GlobalConnectPaymentApplication", "  [$i] issuer=${cert.issuerX500Principal.name}")
                    Log.i("GlobalConnectPaymentApplication", "  [$i] validity=${cert.notBefore} → ${cert.notAfter}")
                }
            }
        } catch (e: Exception) {
            Log.w("GlobalConnectPaymentApplication", "Could not load bundled CA certificates", e)
        }
    }

    private fun applySavedLocale() {
        val sharedPreferences = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val savedLanguage = getSavedLanguage(sharedPreferences).ifBlank { "es" }
        updateLocale(Locale.forLanguageTag(savedLanguage))
    }

    fun updateLocale(locale: Locale) {
        Locale.setDefault(locale)
        val resources = resources
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /**
     * Loads and parses the TMS configuration from internal storage.
     *
     * Parameters are downloaded from the TMS server by the xTMSAgent application and
     * delivered to this app via a FileProvider URI broadcast, then persisted to internal
     * storage by [applyTmsUpdate].
     *
     * Returns an empty [TMSDATA] when no params have been downloaded yet; the caller
     * checks [TMSDATA.Terminal] to detect this and triggers a download request.
     */
    private fun loadTMSDatabase(): TMSDATA {
        val storedFile = File(filesDir, PARAMS_FILE_NAME)
        if (!storedFile.exists()) {
            Log.i("TMSDatabase", "No stored TMS params found — waiting for download from xTMSAgent")
            return TMSDATA()
        }

        return try {
            val jsonContent = storedFile.bufferedReader().use { it.readText() }
            val db: TMSDATA = TMSDATA.parse(jsonContent)
            Log.i("TMSDatabase", "Loaded stored PAYMENT_APP params (schemaVersion=${db.schemaVersion}, " +
                "${db.Terminal.size} terminal(s))")
            logTmsCatalog(db, "startup")
            db
        } catch (error: Exception) {
            Log.e("TMSDatabase", "Failed to parse stored TMS params: ${error.localizedMessage}", error)
            TMSDATA()
        }
    }

    /**
     * Persists an updated TMS configuration and refreshes all in-memory references. Returns
     * `true` when the update is accepted and applied.
     */
    fun applyTmsUpdate(newDatabase: TMSDATA, persistToDisk: Boolean = true): Boolean {
        if (persistToDisk && !persistTmsDatabase(newDatabase)) {
            return false
        }

        tmsDatabase = newDatabase
        logTmsCatalog(newDatabase, "download")
        AcquirerSslCache.build(newDatabase.IPTab, BundledCertificates.get())
        container = AppDataContainer(this, newDatabase, appVersion)
        _paramsReadyFlow.value = true
        _paramsUpdateEvent.tryEmit(Unit)
        applicationScope.launch(Dispatchers.Main) {
            BrandingAnimationCache.prewarm(this@GlobalConnectPaymentApplication, newDatabase)
        }

        val terminalOnlinePinCap = newDatabase.Terminal.firstOrNull()?.onlinePinCap ?: true
        val aidList = EmvConfigBuilder.buildAidList(
            newDatabase.AIDtab,
            newDatabase.PCDApps,
            terminalOnlinePinCap,
        )
        NexgoApi.applyEmvAidList(aidList, terminalOnlinePinCap)
        NexgoApi.applyAcquirerPinTypes(newDatabase.Acquirer.map { it.PINType })

        return true
    }

    private fun persistTmsDatabase(database: TMSDATA): Boolean {
        return try {
            val overrideFile = File(filesDir, PARAMS_FILE_NAME)
            overrideFile.writeText(database.rawJson.ifBlank { error("PAYMENT_APP params missing raw JSON") })
            true
        } catch (error: Exception) {
            Log.e(
                "TMSDatabase",
                "Failed to persist TMS config: ${error.localizedMessage}",
                error
            )
            false
        }
    }

    private fun logTmsCatalog(database: TMSDATA, source: String) {
        val tag = "TMSCatalog"
        Log.i(
            tag,
            "PAYMENT_APP config loaded source=$source applicationId=${database.applicationId} " +
                "schemaVersion=${database.schemaVersion} terminals=${database.Terminal.size} " +
                "acquirers=${database.Acquirer.size} issuers=${database.Issuer.size} " +
                "cardRanges=${database.CardRange.size}"
        )

        database.Terminal.forEachIndexed { index, terminal ->
            Log.i(
                tag,
                "Terminal[$index] termId=${terminal.TermID} header1='${terminal.headerLine1}' " +
                    "sale=${terminal.enableSale} refund=${terminal.enableRefund} cash=${terminal.enableCash} " +
                    "tax1=${terminal.tax1Enabled}/${terminal.tax1Mandatory} tax2=${terminal.tax2Enabled}/${terminal.tax2Mandatory} " +
                    "tipMode=${terminal.tipProcessingMode} capkMode=${terminal.capkMode} " +
                    "onlinePinCap=${terminal.onlinePinCap} " +
                    "tranReporting=${terminal.tranReportingMethod} batchSize=${terminal.tranReportingBatchSize} " +
                    "intervalSeconds=${terminal.tranReportingIntervalSeconds} acquirers=${terminal.acquirer.size}"
            )
        }

        database.Acquirer.forEachIndexed { index, acquirer ->
            Log.i(
                tag,
                "Acquirer[$index] id=${acquirer.AcqID} name='${acquirer.AcquirerName}' " +
                    "merchantId=${acquirer.MerchID} terminalId=${acquirer.AcqTermID} hostProtocol=${acquirer.hostProtocol} " +
                    "hostRef=${acquirer.hostConnectionInfoRef} nii=${acquirer.NII} currency=${acquirer.CurrencyCode}/${acquirer.Currency} " +
                    "sale=${acquirer.enableSale} refund=${acquirer.enableRefund} cash=${acquirer.enableCash} " +
                    "fallback=${acquirer.AllowFallBack} issuers=${acquirer.issuer.size}"
            )
        }

        database.Issuer.forEachIndexed { index, issuer ->
            Log.i(
                tag,
                "Issuer[$index] id=${issuer.IssuID} name='${issuer.IssuerName}' acquirerId=${issuer.AcqID} " +
                    "mod10=${issuer.mod10Check} mod11=${issuer.mod11Check} cardRangeRefs=${issuer.cardRangeRefs}"
            )
        }

        database.CardRange.forEachIndexed { index, cardRange ->
            Log.i(
                tag,
                "CardRange[$index] id=${cardRange.CardRangeID} name='${cardRange.RangeName}' " +
                    "binLow=${cardRange.binLow} binHigh=${cardRange.binHigh} length=${cardRange.Length} " +
                    "exclusive=${cardRange.ExclusiveBIN} aidRefs=${cardRange.processedAidRefs}"
            )
        }
    }

    companion object {
        @Volatile
        private var _instance: GlobalConnectPaymentApplication? = null

        /** File name used to persist downloaded TMS parameters in [filesDir]. */
        internal const val PARAMS_FILE_NAME = "tms_params.json"

        private const val EXTRA_TMS_APPLICATION_ID = "applicationId"
        private const val TMS_APPLICATION_ID_PAYMENT_APP = "PAYMENT_APP"

        val instanceOrNull: GlobalConnectPaymentApplication?
            get() = _instance

        val instance: GlobalConnectPaymentApplication
            get() = _instance ?: error("GlobalConnectPaymentApplication not created yet")

        val deviceEngine: DeviceEngine
            get() = instance.deviceEngine

        /** Device model reported by the terminal. */
        val model: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            deviceEngine.deviceInfo.model
        }

        /** Device serial number reported by the terminal. */
        val serialNumber: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            deviceEngine.deviceInfo.sn
        }
    }
}
