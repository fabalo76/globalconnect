package com.uic.uicpaymentapp.cardreader.nexgo

import android.app.KeyguardManager
import android.graphics.Rect
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import com.nexgo.oaf.apiv3.device.pinpad.PinpadLayoutEntity
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.ui.theme.UICMockAppTheme
import java.util.EnumMap
import kotlin.math.roundToInt

/**
 * Activity that mirrors the PIXIE PIN entry workflow that the legacy .NET
 * application relies on. The composable implementation maps the on-screen
 * keypad to the secure PIN pad and forwards all results back to the state held
 * by [NexgoApi].
 */
class PixiePinEntryActivity : ComponentActivity() {

    private lateinit var pinPad: PinPad
    private val pinPadListener = PinPadInputListener()

    private var keyIndex: Int = 0
    private var pan: String = ""
    private var isOnlinePin: Boolean = false

    private val maskBuilder = StringBuilder()
    private val keyBounds = EnumMap<PinKey, Rect>(PinKey::class.java)

    private var pinMaskText by mutableStateOf("")
    private var titleText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        configurePinPad()
        readExtras()
        prepareForPinEntry()

        setContent {
            UICMockAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PinEntryScreen(
                        title = titleText,
                        pinMask = pinMaskText,
                        onKeyPositioned = ::onKeyPositioned,
                    )
                }
            }
        }

        startPinEntry()
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
        val deviceEngine = UICApplication.deviceEngine
        pinPad = deviceEngine.pinPad
        pinPad.initPinPad(PinPadTypeEnum.INTERNAL)
        pinPad.setAlgorithmMode(AlgorithmModeEnum.DUKPT)
        pinPad.setPinKeyboardMode(PinKeyboardModeEnum.FIXED)

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
        keyBounds.clear()
        NexgoApi.pinData.clear()
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
                    if (isOnlinePin && data != null) {
                        NexgoApi.pinData.status = PinStatus.ENTERED
                        NexgoApi.pinData.pinBlock = ByteUtils.byteArray2HexString(data)
                        NexgoApi.pinData.ksn = currentKsn()
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
                    NexgoApi.pinData.status = PinStatus.ERROR
                    NexgoApi.emvHandler?.onSetPinInputResponse(false, false)
                }
            }
            finish()
        }
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

    private fun onKeyPositioned(key: PinKey, rect: Rect) {
        keyBounds[key] = Rect(rect)
        if (PinKey.entries.all { keyBounds.containsKey(it) }) {
            applyPinLayout()
        }
    }

    private fun applyPinLayout() {
        val layout = PinpadLayoutEntity()
        keyBounds[PinKey.KEY_1]?.let(layout::setKey1)
        keyBounds[PinKey.KEY_2]?.let(layout::setKey2)
        keyBounds[PinKey.KEY_3]?.let(layout::setKey3)
        keyBounds[PinKey.KEY_4]?.let(layout::setKey4)
        keyBounds[PinKey.KEY_5]?.let(layout::setKey5)
        keyBounds[PinKey.KEY_6]?.let(layout::setKey6)
        keyBounds[PinKey.KEY_7]?.let(layout::setKey7)
        keyBounds[PinKey.KEY_8]?.let(layout::setKey8)
        keyBounds[PinKey.KEY_9]?.let(layout::setKey9)
        keyBounds[PinKey.KEY_0]?.let(layout::setKey10)
        keyBounds[PinKey.CANCEL]?.let(layout::setKeyCancel)
        keyBounds[PinKey.CLEAR]?.let(layout::setKeyClear)
        keyBounds[PinKey.ENTER]?.let(layout::setKeyConfirm)
        pinPad.setPinpadLayout(layout)
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
    }
}

private enum class PinKey {
    KEY_1,
    KEY_2,
    KEY_3,
    KEY_4,
    KEY_5,
    KEY_6,
    KEY_7,
    KEY_8,
    KEY_9,
    KEY_0,
    CANCEL,
    CLEAR,
    ENTER,
}

@Composable
private fun PinEntryScreen(
    title: String,
    pinMask: String,
    onKeyPositioned: (PinKey, Rect) -> Unit,
) {
    val white = colorResource(id = R.color.white)
    val keypadBackground = Color(0x33000000)
    val borderColor = Color(0x55FFFFFF)
    val cancelColor = colorResource(id = R.color.input_pin_red)
    val clearColor = colorResource(id = R.color.input_pin_yellow)
    val enterColor = colorResource(id = R.color.input_pin_green)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            textAlign = TextAlign.Center,
            color = white,
            style = MaterialTheme.typography.titleLarge,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(top = 16.dp, bottom = 24.dp)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .background(keypadBackground, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = pinMask,
                color = white,
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            PinKeyCell(
                label = "1",
                key = PinKey.KEY_1,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
            PinKeyCell(
                label = "2",
                key = PinKey.KEY_2,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
            PinKeyCell(
                label = "3",
                key = PinKey.KEY_3,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            PinKeyCell(
                label = "4",
                key = PinKey.KEY_4,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
            PinKeyCell(
                label = "5",
                key = PinKey.KEY_5,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
            PinKeyCell(
                label = "6",
                key = PinKey.KEY_6,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            PinKeyCell(
                label = "7",
                key = PinKey.KEY_7,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
            PinKeyCell(
                label = "8",
                key = PinKey.KEY_8,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
            PinKeyCell(
                label = "9",
                key = PinKey.KEY_9,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            EmptyKeySlot()
            PinKeyCell(
                label = "0",
                key = PinKey.KEY_0,
                backgroundColor = keypadBackground,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
            )
            EmptyKeySlot()
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            PinKeyCell(
                label = stringResource(id = R.string.pin_entry_cancel),
                key = PinKey.CANCEL,
                backgroundColor = cancelColor,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
                textStyle = MaterialTheme.typography.titleMedium,
            )
            PinKeyCell(
                label = stringResource(id = R.string.pin_entry_clear),
                key = PinKey.CLEAR,
                backgroundColor = clearColor,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
                textStyle = MaterialTheme.typography.titleMedium,
            )
            PinKeyCell(
                label = stringResource(id = R.string.pin_entry_enter),
                key = PinKey.ENTER,
                backgroundColor = enterColor,
                textColor = white,
                borderColor = borderColor,
                onKeyPositioned = onKeyPositioned,
                textStyle = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun RowScope.EmptyKeySlot() {
    Spacer(
        modifier = Modifier
            .weight(1f)
            .height(64.dp)
            .padding(4.dp),
    )
}

@Composable
private fun RowScope.PinKeyCell(
    label: String,
    key: PinKey,
    backgroundColor: Color,
    textColor: Color,
    borderColor: Color,
    onKeyPositioned: (PinKey, Rect) -> Unit,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(64.dp)
            .padding(4.dp)
            .recordBounds(key, onKeyPositioned)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(backgroundColor, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            style = textStyle,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Modifier.recordBounds(
    key: PinKey,
    onKeyPositioned: (PinKey, Rect) -> Unit,
): Modifier = this.then(
    Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val rect = Rect(
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.right.roundToInt(),
            bounds.bottom.roundToInt(),
        )
        onKeyPositioned(key, rect)
    },
)
