package one.globalconnect.paymentapp.navigation

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.transaction.BrandingAnimationOverlay
import one.globalconnect.paymentapp.utils.AudioPlaybackDiagnostics
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager

/** Isolates audio playback from card reading and payment processing. */
@Composable
fun AudioTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var branding by remember { mutableStateOf<Boolean?>(null) }
    BackHandler(enabled = busy) { }
    val terminal = GlobalConnectPaymentApplication.instance.tmsDatabase.Terminal.firstOrNull()
    val acquirer = GlobalConnectPaymentApplication.instance.tmsDatabase.Acquirer.firstOrNull()
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.audio_test), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.audio_test_description))
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                AudioPlaybackDiagnostics.log(context, "Audio test: keypad")
                SoundManager.play(SoundEffect.KEY_STANDARD)
            }) { Text(stringResource(R.string.audio_test_keypad)) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    try {
                        AudioPlaybackDiagnostics.log(context, "Audio test: reader beep without transaction")
                        withContext(Dispatchers.IO) {
                            GlobalConnectPaymentApplication.deviceEngine.beeper.beep(1_000)
                        }
                        delay(1_200)
                    } catch (error: Exception) {
                        Log.e("PaymentAudio", "Reader beep test failed", error)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.audio_test_reader)) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                branding = true
                Log.d("PaymentAudio", "Audio test: Mastercard prepared controller")
            }) { Text(stringResource(R.string.audio_test_mastercard)) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                branding = false
                Log.d("PaymentAudio", "Audio test: Mastercard fresh controller")
            }) { Text(stringResource(R.string.audio_test_mastercard_reload)) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = onBack) {
                Text(stringResource(R.string.back))
            }
        }
        branding?.let { usePrepared ->
            BrandingAnimationOverlay(
                cardType = "Mastercard",
                merchantName = terminal?.MerchantTitle1.orEmpty(),
                merchantId = acquirer?.MerchID.orEmpty(),
                city = terminal?.MerchantTitle2.orEmpty(),
                countryCode = acquirer?.CountryCode?.toString() ?: "340",
                usePreparedController = usePrepared,
                onComplete = { branding = null; busy = false },
            )
        }
    }
}
