package one.globalconnect.paymentapp.transaction

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.mastercard.sonic.controller.SonicController
import com.mastercard.sonic.controller.SonicEnvironment
import com.mastercard.sonic.controller.SonicType
import com.mastercard.sonic.listeners.OnPrepareListener
import com.mastercard.sonic.model.SonicMerchant
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.R
import com.visa.CheckmarkMode
import com.visa.CheckmarkTextOption
import com.visa.SensoryBrandingView

/**
 * Singleton cache for branding animation SDK instances.
 *
 * Visa:  SensoryBrandingView construction loads language assets into the SDK's internal cache —
 *        trigger this once at startup; create a fresh View per transaction (reuse crashes onMeasure).
 *
 * MC:    SonicController.prepare() is async and loads audio/animation assets — pre-prepare
 *        after TMS params arrive so play() can be called immediately on approval. After
 *        each successful play, reuse the prepared assets for the next transaction.
 *
 * Call [prewarm] once after TMS params are ready (and again after each param update).
 */
internal object BrandingAnimationCache {
    private const val TAG = "BrandingAnimCache"

    // ── Mastercard ────────────────────────────────────────────────────────────

    @Volatile private var sonicController: SonicController? = null
    @Volatile private var sonicReady = false
    @Volatile private var lastMerchant: SonicMerchant? = null
    private var sonicPreparing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public API ────────────────────────────────────────────────────────────

    fun prewarm(context: Context, tmsDatabase: TMSDATA) {
        val appCtx = context.applicationContext
        prewarmVisa(appCtx)
        val acquirer = tmsDatabase.Acquirer.firstOrNull() ?: return
        val terminal = tmsDatabase.Terminal.firstOrNull() ?: return
        val merchant = SonicMerchant.Builder()
            .merchantName(terminal.MerchantTitle1.ifBlank { "Merchant" })
            .merchantId(acquirer.MerchID.ifBlank { "0" })
            .city(terminal.MerchantTitle2.ifBlank { "" })
            .countryCode(acquirer.CountryCode.takeIf { it > 0 }?.toString() ?: "840")
            .merchantCategoryCodes(arrayOf("5999"))
            .build()
        prewarmSonic(appCtx, merchant)
    }

    /**
     * Returns a fresh [SensoryBrandingView] per transaction. The SDK caches its language
     * assets after the first construction (triggered by [prewarm]), so subsequent builds
     * are fast. A fresh instance avoids AndroidView measurement failures caused by reusing
     * a View that was never attached/measured.
     */
    fun buildVisaView(context: Context, backdropColorArgb: Int, langCode: String) =
        SensoryBrandingView(context, null).apply {
            soundEnabled = true
            hapticEnabled = true
            checkmarkMode = CheckmarkMode.CHECKMARK_WITH_TEXT
            checkmarkText = CheckmarkTextOption.APPROVE
            this.backdropColor = backdropColorArgb
            languageCode = langCode
        }

    /**
     * Returns the pre-prepared [SonicController] and marks it consumed (not ready).
     * Returns null if prepare hasn't completed yet (caller must fall back to prepare+play).
     */
    @Synchronized
    fun takeSonicController(): SonicController? {
        if (!sonicReady) {
            Log.d(TAG, "SonicController cache miss — prepare not complete yet")
            return null
        }
        sonicReady = false
        Log.d(TAG, "SonicController cache hit")
        return sonicController
    }

    /** The SDK resets its playing flags after invoking its completion callback. */
    fun onSonicPlayComplete(context: Context, controller: SonicController, statusCode: Int) {
        mainHandler.post {
            synchronized(this) {
                if (sonicController == null && isSonicSuccess(statusCode)) {
                    sonicController = controller
                }
                if (sonicController !== controller) return@synchronized
                if (isSonicSuccess(statusCode)) {
                    // Preparing a new controller allocates a new SoundPool with no public
                    // release API. The SDK supports replaying its already loaded assets.
                    sonicReady = true
                    Log.d(TAG, "SonicController retained for next playback")
                } else {
                    sonicController = null
                    sonicReady = false
                    lastMerchant?.let { prewarmSonic(context.applicationContext, it) }
                }
            }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun prewarmVisa(context: Context) {
        val backdropColor = ContextCompat.getColor(context, R.color.visa_backdrop_color)
        val langCode = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
            .getString("selected_language", null) ?: "es"
        // Construct and discard — triggers SDK to load language assets into its internal cache
        // so the first transaction's View construction is cheap.
        buildVisaView(context, backdropColor, langCode)
        Log.d(TAG, "Pre-loaded Visa SDK assets langCode=$langCode")
    }

    @Synchronized
    internal fun prewarmSonic(context: Context, merchant: SonicMerchant) {
        lastMerchant = merchant
        if (sonicController != null || sonicPreparing) return
        sonicPreparing = true
        val controller = SonicController()
        sonicController = controller
        controller.prepare(
            sonicType = SonicType.SOUND_AND_ANIMATION,
            sonicCue = "checkout",
            sonicEnvironment = SonicEnvironment.PRODUCTION,
            merchant = merchant,
            isHapticsEnabled = true,
            context = context,
            onPrepareListener = object : OnPrepareListener {
                override fun onPrepared(statusCode: Int) {
                    synchronized(this@BrandingAnimationCache) {
                        if (sonicController !== controller) return
                        sonicPreparing = false
                        sonicReady = isSonicSuccess(statusCode)
                        if (!sonicReady) sonicController = null
                    }
                    Log.d(TAG, "SonicController pre-prepared statusCode=$statusCode")
                }
            },
        )
    }
}

internal fun isSonicSuccess(statusCode: Int): Boolean = statusCode == 800 || statusCode == 801
