package one.globalconnect.paymentapp.cardreader.nexgo

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexgo.common.ByteUtils
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.pinpad.AlgorithmModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinAlgorithmModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinPad
import com.nexgo.oaf.apiv3.device.pinpad.PinPadKeyCode
import com.nexgo.oaf.apiv3.device.pinpad.PinPadTypeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinKeyboardModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinKeyboardViewModeEnum
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.ui.theme.GlobalConnectPaymentTheme

/**
 * Activity that mirrors the PIXIE PIN entry workflow that the legacy .NET
 * application relies on. The app renders the PIN status while the NEXGO service
 * owns the native secure keypad and forwards results back to [NexgoApi].
 */
class PixiePinEntryActivity : ComponentActivity() {

    private lateinit var pinPad: PinPad
    private val pinPadListener = PinPadInputListener()

    private var keyIndex: Int = 0
    private var pan: String = ""
    private var isOnlinePin: Boolean = false

    private val maskBuilder = StringBuilder()

    private var pinMaskText by mutableStateOf("")
    private var titleText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        readExtras()
        prepareForPinEntry()
        try {
            configurePinPad()
        } catch (error: Throwable) {
            failPinEntry(error)
            return
        }

        setContent {
            GlobalConnectPaymentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PIN_SCREEN_BACKGROUND,
                ) {
                    PinEntryScreen(
                        title = titleText,
                        pinMask = pinMaskText,
                    )
                }
            }
        }

        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                try {
                    startPinEntry()
                } catch (error: Throwable) {
                    failPinEntry(error)
                }
            }
        }
    }

    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val keyguardManager: KeyguardManager? = getSystemService()
        if (keyguardManager?.isKeyguardLocked == true) {
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NexgoApi.pinEntryDone = true
    }

    private fun configurePinPad() {
        val deviceEngine = GlobalConnectPaymentApplication.deviceEngine
        pinPad = deviceEngine.pinPad
        pinPad.initPinPad(PinPadTypeEnum.INTERNAL)
        pinPad.setAlgorithmMode(AlgorithmModeEnum.DUKPT)
        pinPad.setPinKeyboardMode(PinKeyboardModeEnum.FIXED)
        pinPad.setPinKeyboardViewMode(PinKeyboardViewModeEnum.DEFAULT)

        pinPadListener.onInputResult = ::handleInputResult
        pinPadListener.onSendKey = ::handleSendKey
    }

    private fun readExtras() {
        intent?.extras?.let { bundle ->
            keyIndex = bundle.getInt(EXTRA_KEY_INDEX, 0)
            pan = bundle.getString(EXTRA_PAN, "")
            isOnlinePin = bundle.getBoolean(EXTRA_IS_ONLINE_PIN, false)
        }
        titleText = if (isOnlinePin) {
            getString(R.string.pin_entry_online)
        } else {
            getString(R.string.pin_entry_offline)
        }
    }

    private fun prepareForPinEntry() {
        maskBuilder.clear()
        pinMaskText = ""
        NexgoApi.pinData.clear()
        NexgoApi.pinData.onlinePinRequested = isOnlinePin
        NexgoApi.pinEntryDone = false
    }

    private fun startPinEntry() {
        val supportLength = intArrayOf(4, 5, 6, 7, 8, 9, 10, 11, 12)
        val timeout = DEFAULT_TIMEOUT_SECONDS
        val ret = if (isOnlinePin) {
            val numericPan = pan.filter(Char::isDigit)
            val panBytes = ByteUtils.string2ASCIIByteArray(numericPan)
            pinPad.inputOnlinePin(
                supportLength,
                timeout,
                panBytes,
                keyIndex,
                PinAlgorithmModeEnum.ISO9564FMT0,
                pinPadListener,
            )
        } else {
            pinPad.inputOfflinePin(supportLength, timeout, pinPadListener)
        }

        if (ret != SdkResult.Success) {
            handleInputResult(ret, null)
        }
    }

    private fun handleInputResult(retCode: Int, data: ByteArray?) {
        runOnUiThread {
            when (retCode) {
                SdkResult.Success -> {
                    if (isOnlinePin) {
                        val pinBlock = data?.let { ByteUtils.byteArray2HexString(it) }.orEmpty()
                        val ksn = currentKsn()
                        if (pinBlock.isBlank() || ksn.isBlank()) {
                            failPinEntry(retCode = retCode)
                            return@runOnUiThread
                        }
                        NexgoApi.pinData.status = PinStatus.ENTERED
                        NexgoApi.pinData.pinBlock = pinBlock
                        NexgoApi.pinData.ksn = ksn
                        increaseKsn()
                    } else {
                        NexgoApi.pinData.status = PinStatus.ENTERED
                    }
                    NexgoApi.emvHandler?.onSetPinInputResponse(true, false)
                }
                SdkResult.PinPad_No_Pin_Input -> {
                    NexgoApi.pinData.status = PinStatus.BYPASSED
                    NexgoApi.pinData.pinBlock = data?.let { ByteUtils.byteArray2HexString(it) } ?: ""
                    NexgoApi.emvHandler?.onSetPinInputResponse(true, true)
                }
                SdkResult.PinPad_Input_Cancel, SdkResult.PinPad_Input_Timeout -> {
                    NexgoApi.pinData.status = PinStatus.CANCELED
                    NexgoApi.emvHandler?.onSetPinInputResponse(false, false)
                }
                else -> {
                    failPinEntry(retCode = retCode)
                    return@runOnUiThread
                }
            }
            finish()
        }
    }

    private fun failPinEntry(error: Throwable? = null, retCode: Int? = null) {
        val message = if (isOnlinePin) {
            getString(R.string.online_pin_error_internal_pinpad)
        } else {
            getString(R.string.offline_pin_error_internal_pinpad)
        }
        android.util.Log.e(
            TAG,
            "$message${retCode?.let { " (code=$it)" }.orEmpty()}",
            error,
        )
        NexgoApi.pinData.status = PinStatus.ERROR
        NexgoApi.pinData.errorMessage = message
        NexgoApi.pinEntryDone = true
        runCatching { NexgoApi.emvHandler?.onSetPinInputResponse(false, false) }
        finish()
    }

    private fun handleSendKey(keyCode: Byte) {
        when (keyCode) {
            PinPadKeyCode.KEYCODE_CLEAR, PinPadKeyCode.KEYCODE_BACKSPACE -> {
                if (maskBuilder.isNotEmpty()) {
                    maskBuilder.delete(maskBuilder.length - MASK_TOKEN_LENGTH, maskBuilder.length)
                }
            }
            PinPadKeyCode.KEYCODE_CANCEL -> {
                pinPad.cancelInput()
                maskBuilder.clear()
            }
            PinPadKeyCode.KEYCODE_CONFIRM -> {
                // Handled by secure hardware when Enter is pressed.
            }
            else -> {
                maskBuilder.append(MASK_TOKEN)
            }
        }
        updateMaskDisplay()
    }

    private fun updateMaskDisplay() {
        val text = maskBuilder.toString()
        runOnUiThread { pinMaskText = text }
    }

    private fun increaseKsn() {
        runCatching { pinPad.dukptKsnIncrease(keyIndex) }
    }

    private fun currentKsn(): String = try {
        val ksnBytes = pinPad.dukptCurrentKsn(keyIndex)
        if (ksnBytes != null) ByteUtils.byteArray2HexString(ksnBytes) else ""
    } catch (_: Throwable) {
        ""
    }

    companion object {
        const val EXTRA_PAN = "PAN"
        const val EXTRA_IS_ONLINE_PIN = "IsOnlinePIN"
        const val EXTRA_KEY_INDEX = "KeyIndex"

        private const val DEFAULT_TIMEOUT_SECONDS = 60
        private const val MASK_TOKEN = "* "
        private const val MASK_TOKEN_LENGTH = 2
        private const val TAG = "PixiePinEntry"
        private val PIN_SCREEN_BACKGROUND = Color(0xFF102119)
    }
}

@Composable
private fun PinEntryScreen(
    title: String,
    pinMask: String,
) {
    val white = colorResource(id = R.color.white)
    val fieldBackground = Color(0x22FFFFFF)
    val borderColor = Color(0x55FFFFFF)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp),
            textAlign = TextAlign.Center,
            color = white,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .background(fieldBackground, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = pinMask.ifEmpty { "• • • •" },
                color = white,
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(id = R.string.pin_entry_secure_keypad_hint),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = white.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}
