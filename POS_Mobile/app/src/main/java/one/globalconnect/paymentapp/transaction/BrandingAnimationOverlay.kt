package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.viewinterop.AndroidView
import one.globalconnect.paymentapp.R
import com.mastercard.sonic.listeners.OnCompleteListener
import com.mastercard.sonic.listeners.OnPrepareListener
import com.mastercard.sonic.controller.SonicController
import com.mastercard.sonic.controller.SonicEnvironment
import com.mastercard.sonic.controller.SonicType
import com.mastercard.sonic.model.SonicMerchant
import com.mastercard.sonic.widget.SonicView
import one.globalconnect.paymentapp.utils.AudioPlaybackDiagnostics

private const val TAG = "BrandingAnimation"

@Composable
fun BrandingAnimationOverlay(
    cardType: String,
    merchantName: String = "",
    merchantId: String = "",
    city: String = "",
    countryCode: String = "USA",
    usePreparedController: Boolean = true,
    onComplete: () -> Unit,
) {
    val type = cardType.lowercase()
    val brand = resolveSensoryBrand(type)
    Log.d(TAG, "BrandingAnimationOverlay cardType=$cardType type=$type brand=$brand merchantName=$merchantName merchantId=$merchantId city=$city countryCode=$countryCode")
    when (brand) {
        SensoryBrand.Visa -> VisaBrandingAnimation(onComplete = onComplete)
        SensoryBrand.Mastercard -> MastercardBrandingAnimation(
            merchantName = merchantName,
            merchantId = merchantId,
            city = city,
            countryCode = countryCode,
            usePreparedController = usePreparedController,
            onComplete = onComplete,
        )
        null -> LaunchedEffect(Unit) { onComplete() }
    }
}

private enum class SensoryBrand {
    Visa,
    Mastercard,
}

private fun resolveSensoryBrand(type: String): SensoryBrand? {
    val normalized = type.replace(Regex("[^a-z0-9]"), "")
    return when {
        "visa" in normalized || "a000000003" in normalized -> SensoryBrand.Visa
        "mastercard" in normalized || "master" in normalized || normalized == "mc" ||
            "a000000004" in normalized -> SensoryBrand.Mastercard
        else -> null
    }
}

@Composable
private fun VisaBrandingAnimation(onComplete: () -> Unit) {
    val context = LocalContext.current
    val flavorBackdropColor = colorResource(R.color.visa_backdrop_color)
    val backdropColorArgb = flavorBackdropColor.toArgb()
    val langCode = remember {
        val prefs = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        val allKeys = prefs.all.keys.joinToString()
        val raw = prefs.getString("selected_language", null) ?: "es"
        Log.d(TAG, "Visa langCode=$raw  prefs keys=[$allKeys]")
        raw
    }
    val backdropHex = String.format("#%08X", backdropColorArgb)
    Log.d(TAG, "Visa params: soundEnabled=true hapticEnabled=true checkmarkMode=CHECKMARK_WITH_TEXT checkmarkText=APPROVE backdropColor=$backdropHex languageCode=$langCode")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(flavorBackdropColor),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            factory = { ctx ->
                AudioPlaybackDiagnostics.log(ctx, "Visa sensory start")
                BrandingAnimationCache.buildVisaView(ctx, backdropColorArgb, langCode)
            },
            update = { vsb ->
                // SDK returns a non-fatal error if already playing; first call wins.
                vsb.animate { onComplete() }
            },
        )
    }
}

@Composable
private fun MastercardBrandingAnimation(
    merchantName: String,
    merchantId: String,
    city: String,
    countryCode: String,
    usePreparedController: Boolean,
    onComplete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                AudioPlaybackDiagnostics.log(ctx, "Mastercard sensory start")
                val sonicView = SonicView(ctx)
                val mcMerchantName = merchantName.ifBlank { "Merchant" }
                val mcMerchantId = merchantId.ifBlank { "0" }
                val mcCity = city.ifBlank { "" }
                val mcCountry = countryCode.ifBlank { "USA" }
                Log.d(TAG, "MC Sonic params: sonicType=SOUND_AND_ANIMATION sonicCue=checkout env=PRODUCTION haptics=true merchantName=$mcMerchantName merchantId=$mcMerchantId city=$mcCity countryCode=$mcCountry mcc=5999")

                val preparedController = if (usePreparedController) BrandingAnimationCache.takeSonicController() else null
                if (preparedController != null) {
                    Log.d(TAG, "MC Sonic playing from pre-prepared controller")
                    preparedController.play(sonicView, object : OnCompleteListener {
                        override fun onComplete(statusCode: Int) {
                            Log.d(TAG, "MC Sonic onComplete statusCode=$statusCode")
                            AudioPlaybackDiagnostics.log(ctx, "Mastercard sensory complete status=$statusCode")
                            BrandingAnimationCache.onSonicPlayComplete(ctx, preparedController, statusCode)
                            onComplete()
                        }
                    })
                } else {
                    Log.d(TAG, "MC Sonic cache miss — preparing now")
                    val merchant = SonicMerchant.Builder()
                        .merchantName(mcMerchantName)
                        .merchantId(mcMerchantId)
                        .city(mcCity)
                        .countryCode(mcCountry)
                        .merchantCategoryCodes(arrayOf("5999"))
                        .build()
                    val sonicController = SonicController()
                    sonicController.prepare(
                        sonicType = SonicType.SOUND_AND_ANIMATION,
                        sonicCue = "checkout",
                        sonicEnvironment = SonicEnvironment.PRODUCTION,
                        merchant = merchant,
                        isHapticsEnabled = true,
                        context = ctx.applicationContext,
                        onPrepareListener = object : OnPrepareListener {
                            override fun onPrepared(statusCode: Int) {
                                Log.d(TAG, "MC Sonic onPrepared statusCode=$statusCode")
                                if (!isSonicSuccess(statusCode)) {
                                    Log.w(TAG, "MC Sonic preparation failed; skipping playback")
                                    onComplete()
                                    return
                                }
                                sonicController.play(sonicView, object : OnCompleteListener {
                                    override fun onComplete(statusCode: Int) {
                                        Log.d(TAG, "MC Sonic onComplete statusCode=$statusCode")
                                        AudioPlaybackDiagnostics.log(ctx, "Mastercard sensory complete status=$statusCode")
                                        BrandingAnimationCache.onSonicPlayComplete(ctx, sonicController, statusCode)
                                        onComplete()
                                    }
                                })
                            }
                        },
                    )
                }
                sonicView
            },
        )
    }
}
