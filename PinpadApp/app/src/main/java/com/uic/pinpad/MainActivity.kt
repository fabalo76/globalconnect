package com.uic.pinpad

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.mastercard.sonic.controller.SonicController
import com.mastercard.sonic.controller.SonicEnvironment
import com.mastercard.sonic.controller.SonicType
import com.mastercard.sonic.listeners.OnCompleteListener
import com.mastercard.sonic.listeners.OnPrepareListener
import com.mastercard.sonic.model.SonicMerchant
import com.mastercard.sonic.widget.SonicView
import com.uic.pinpad.comms.PinpadSerialService
import com.uic.pinpad.config.DeviceModelConfig
import com.uic.pinpad.config.DeviceModelSpec
import com.uic.pinpad.logging.PinpadTraceLog
import com.uic.pinpad.model.PinpadTransactionDisplay
import com.uic.pinpad.protocol.PinpadKeypadKey
import com.uic.pinpad.storage.PinpadPreferences
import com.uic.pinpad.storage.PinpadSettingsPasswordStore
import com.uic.pinpad.storage.SerialSettings
import com.uic.pinpad.ui.ContactlessLedState
import com.uic.pinpad.ui.PinpadDisplayController
import com.uic.pinpad.ui.PinpadDisplayState
import com.uic.pinpad.ui.SensoryBrand
import com.uic.pinpad.ui.TextEntryEchoMode
import com.visa.CheckmarkMode
import com.visa.CheckmarkTextOption
import com.visa.SensoryBrandingView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val VISA_SENSORY_FALLBACK_MS = 5_000L
private const val MASTERCARD_SENSORY_FALLBACK_MS = 12_000L
private const val MASTERCARD_ANIMATION_WARMUP_DELAY_MS = 1_500L
private const val AUDIO_WARMUP_SAMPLE_RATE = 48_000
private const val BRAND_SENSORY_LOG_TAG = "PinpadSensory"

class MainActivity : ComponentActivity() {
    private val pressedKeys = mutableSetOf<Int>()
    private var settingsMenuVisible by mutableStateOf(false)
    private var serialSetupVisible by mutableStateOf(false)
    private var lastEnterPressAt = 0L
    private var serialSettingsVersion by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveFullscreen()
        applyNexgoSystemBarsLockedAsync(true)
        startService(Intent(this, PinpadSerialService::class.java))
        setContent {
            PinpadRoot(
                settingsMenuVisible = settingsMenuVisible,
                serialSetupVisible = serialSetupVisible,
                serialSettingsVersion = serialSettingsVersion,
                onOpenSerialSetup = {
                    settingsMenuVisible = false
                    serialSetupVisible = true
                },
                onCloseSettingsMenu = { settingsMenuVisible = false },
                onCloseSetup = {
                    serialSetupVisible = false
                    settingsMenuVisible = true
                },
                onSaveSettings = { settings ->
                    PinpadPreferences(this).setSerialSettings(settings)
                    serialSettingsVersion += 1
                    serialSetupVisible = false
                    settingsMenuVisible = false
                    startService(
                        Intent(this, PinpadSerialService::class.java)
                            .setAction(PinpadSerialService.ACTION_RELOAD_SETTINGS),
                    )
                },
                onOpenAndroidSettings = ::openAndroidSettings,
                onOpenWifiSettings = ::openWifiSettings,
                onExitToAndroidHome = ::exitToAndroidHome,
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveFullscreen()
            applyNexgoSystemBarsLockedAsync(true)
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveFullscreen()
        applyNexgoSystemBarsLockedAsync(true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        recordKeyEvent(event)
        val settingsUiWasVisible = settingsMenuVisible || serialSetupVisible
        detectSetupShortcuts(event)
        if (isCancelLikeKey(event.keyCode)) {
            if (settingsUiWasVisible && event.action == KeyEvent.ACTION_DOWN) {
                if (serialSetupVisible) {
                    serialSetupVisible = false
                    settingsMenuVisible = true
                } else {
                    settingsMenuVisible = false
                }
            } else if (event.action == KeyEvent.ACTION_DOWN) {
                startService(
                    Intent(this, PinpadSerialService::class.java)
                        .setAction(PinpadSerialService.ACTION_CANCEL_ACTIVE_OPERATION),
                )
            }
            return true
        }
        if (!settingsMenuVisible && !serialSetupVisible && event.shouldDispatchPinpadKey()) {
            event.toPinpadKeypadKey()?.let { key ->
                startService(
                    Intent(this, PinpadSerialService::class.java)
                        .setAction(PinpadSerialService.ACTION_KEYPAD_KEY)
                        .putExtra(PinpadSerialService.EXTRA_KEYPAD_KEY, key.name),
                )
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun recordKeyEvent(event: KeyEvent) {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        val unicode = event.unicodeChar.takeIf { it > 0 }?.toChar()?.toString() ?: "-"
        Log.d(
            KEYPAD_LOG_TAG,
            "action=$action keyCode=${event.keyCode} keyName=${KeyEvent.keyCodeToString(event.keyCode)} " +
                "scanCode=${event.scanCode} unicode=${event.unicodeChar} char=$unicode " +
                "repeat=${event.repeatCount} deviceId=${event.deviceId} metaState=${event.metaState} " +
                "eventTime=${event.eventTime} downTime=${event.downTime}",
        )
    }

    private fun detectSetupShortcuts(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                pressedKeys += event.keyCode
                if (pressedKeys.hasEnterAndOne() || event.completesEnterThenOneSequence()) {
                    settingsMenuVisible = true
                    serialSetupVisible = false
                }
                if (event.repeatCount == 0 && event.isEnterKey()) {
                    lastEnterPressAt = event.eventTime
                }
            }
            KeyEvent.ACTION_UP -> pressedKeys -= event.keyCode
        }
    }

    private fun openAndroidSettings() {
        runWithNexgoSystemBarsUnlocked {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openWifiSettings() {
        runWithNexgoSystemBarsUnlocked {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun exitToAndroidHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runWithNexgoSystemBarsUnlocked {
            startActivity(homeIntent)
            finish()
        }
    }

    private fun Set<Int>.hasEnterAndOne(): Boolean {
        val hasEnter = contains(KeyEvent.KEYCODE_ENTER) || contains(KeyEvent.KEYCODE_NUMPAD_ENTER)
        val hasOne = contains(KeyEvent.KEYCODE_1) || contains(KeyEvent.KEYCODE_NUMPAD_1)
        return hasEnter && hasOne
    }

    private fun KeyEvent.completesEnterThenOneSequence(): Boolean {
        if (repeatCount != 0 || !isOneKey()) return false
        return lastEnterPressAt > 0L && eventTime - lastEnterPressAt <= ENTER_ONE_SEQUENCE_WINDOW_MS
    }

    private fun KeyEvent.isEnterKey(): Boolean {
        return keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }

    private fun KeyEvent.isOneKey(): Boolean {
        return keyCode == KeyEvent.KEYCODE_1 || keyCode == KeyEvent.KEYCODE_NUMPAD_1
    }

    private fun isCancelLikeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_CLEAR
    }

    private fun KeyEvent.toPinpadKeypadKey(): PinpadKeypadKey? {
        return when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> PinpadKeypadKey.Digit0
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> PinpadKeypadKey.Digit1
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> PinpadKeypadKey.Digit2
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> PinpadKeypadKey.Digit3
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> PinpadKeypadKey.Digit4
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> PinpadKeypadKey.Digit5
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> PinpadKeypadKey.Digit6
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> PinpadKeypadKey.Digit7
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> PinpadKeypadKey.Digit8
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> PinpadKeypadKey.Digit9
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> PinpadKeypadKey.Enter
            KeyEvent.KEYCODE_DEL -> PinpadKeypadKey.Clear
            KeyEvent.KEYCODE_DPAD_UP -> PinpadKeypadKey.Function1
            KeyEvent.KEYCODE_DPAD_DOWN -> PinpadKeypadKey.Function2
            KeyEvent.KEYCODE_MENU -> PinpadKeypadKey.Space
            KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> PinpadKeypadKey.Period
            else -> null
        }
    }

    private fun KeyEvent.shouldDispatchPinpadKey(): Boolean {
        val key = toPinpadKeypadKey() ?: return false
        return if (key == PinpadKeypadKey.Function1 || key == PinpadKeypadKey.Function2) {
            action == KeyEvent.ACTION_UP
        } else {
            action == KeyEvent.ACTION_DOWN && repeatCount == 0
        }
    }

    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun runWithNexgoSystemBarsUnlocked(action: () -> Unit) {
        applyNexgoSystemBarsLockedAsync(false)
        Handler(Looper.getMainLooper()).postDelayed(action, SYSTEM_BAR_EXIT_DELAY_MS)
    }

    private fun applyNexgoSystemBarsLockedAsync(locked: Boolean) {
        Thread {
            applyNexgoSystemBarsLocked(locked)
        }.apply {
            name = "PINPADSystemBars"
            isDaemon = true
            start()
        }
    }

    private fun applyNexgoSystemBarsLocked(locked: Boolean) {
        val platform = (applicationContext as PinpadApplication).deviceEngine.platform
        runCatching {
            if (locked) {
                platform.hideNavigationBar()
                platform.disableControlBar()
            } else {
                platform.showNavigationBar()
                platform.enableControlBar()
            }
        }.onSuccess {
            PinpadTraceLog.device("system bars locked=$locked")
        }.onFailure {
            Log.w(TAG, "Unable to update Nexgo system bars locked=$locked", it)
            PinpadTraceLog.device("system bars update failed locked=$locked error=${it.message}")
        }
    }

    companion object {
        private const val TAG = "PinpadMainActivity"
        private const val KEYPAD_LOG_TAG = "PinpadKeypad"
        private const val ENTER_ONE_SEQUENCE_WINDOW_MS = 4_000L
        private const val SYSTEM_BAR_EXIT_DELAY_MS = 250L
    }
}

@Composable
private fun PinpadRoot(
    settingsMenuVisible: Boolean,
    serialSetupVisible: Boolean,
    serialSettingsVersion: Int,
    onOpenSerialSetup: () -> Unit,
    onCloseSettingsMenu: () -> Unit,
    onCloseSetup: () -> Unit,
    onSaveSettings: (SerialSettings) -> Unit,
    onOpenAndroidSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onExitToAndroidHome: () -> Unit,
) {
    if (serialSetupVisible) {
        PinpadSetupScreen(
            onClose = onCloseSetup,
            onSaveSettings = onSaveSettings,
        )
    } else if (settingsMenuVisible) {
        PinpadSettingsMenu(
            onSerialSettings = onOpenSerialSetup,
            onAndroidSettings = onOpenAndroidSettings,
            onWifiSettings = onOpenWifiSettings,
            onExitToAndroidHome = onExitToAndroidHome,
            onClose = onCloseSettingsMenu,
        )
    } else {
        PinpadIdleScreen(serialSettingsVersion = serialSettingsVersion)
    }
}

@Composable
private fun PinpadSettingsMenu(
    onSerialSettings: () -> Unit,
    onAndroidSettings: () -> Unit,
    onWifiSettings: () -> Unit,
    onExitToAndroidHome: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val passwordStore = remember(context) { PinpadSettingsPasswordStore(context) }
    val modelName = remember(context) { (context.applicationContext as PinpadApplication).deviceInfoProvider.modelName() }
    val useHardwarePasswordEntry = remember(modelName) { modelName.isCt20pModel() }
    var protectedAction by remember { mutableStateOf<SettingsProtectedAction?>(null) }
    var passwordFailCount by remember { mutableStateOf(0) }
    var passwordLockUntilMs by remember { mutableStateOf(0L) }

    fun runProtected(action: SettingsProtectedAction) {
        when (action) {
            SettingsProtectedAction.AndroidSettings -> onAndroidSettings()
            SettingsProtectedAction.ExitToAndroidHome -> onExitToAndroidHome()
        }
    }

    fun requestProtected(action: SettingsProtectedAction) {
        if (!BuildConfig.SETTINGS_PASSWORD_PROTECTION_ENABLED) {
            runProtected(action)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < passwordLockUntilMs) {
            val remaining = ((passwordLockUntilMs - now) / 1000L).coerceAtLeast(1L)
            Toast.makeText(
                context,
                context.getString(R.string.settings_password_wait, remaining),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        protectedAction = action
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF080A0F),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_menu_title),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_menu_hint),
                    color = Color(0xFFC8CDD5),
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(2.dp))
                SettingsMenuButton(
                    text = stringResource(R.string.settings_serial_settings),
                    background = Color(0xFF125C9E),
                    onClick = onSerialSettings,
                )
                SettingsMenuButton(
                    text = stringResource(R.string.settings_android_settings),
                    background = Color(0xFF486A24),
                    onClick = { requestProtected(SettingsProtectedAction.AndroidSettings) },
                )
                SettingsMenuButton(
                    text = stringResource(R.string.settings_wifi_settings),
                    background = Color(0xFF6B4AA0),
                    onClick = onWifiSettings,
                )
                SettingsMenuButton(
                    text = stringResource(R.string.settings_exit_home),
                    background = Color(0xFF9A4636),
                    onClick = { requestProtected(SettingsProtectedAction.ExitToAndroidHome) },
                )
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    onClick = onClose,
                ) {
                    Text(stringResource(R.string.settings_close), fontSize = 18.sp)
                }
            }
        }
    }

    protectedAction?.let { action ->
        SettingsPasswordDialog(
            passwordStore = passwordStore,
            hardwareOnly = useHardwarePasswordEntry,
            onDismiss = { protectedAction = null },
            onVerified = {
                passwordFailCount = 0
                passwordLockUntilMs = 0L
                protectedAction = null
                runProtected(action)
            },
            onRejected = {
                passwordFailCount += 1
                if (passwordFailCount >= SETTINGS_PASSWORD_MAX_FAILURES) {
                    passwordFailCount = 0
                    passwordLockUntilMs = SystemClock.elapsedRealtime() + SETTINGS_PASSWORD_LOCKOUT_MS
                }
                protectedAction = null
                Toast.makeText(context, context.getString(R.string.settings_password_error), Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun SettingsMenuButton(
    text: String,
    background: Color,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = background),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = text,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun SettingsPasswordDialog(
    passwordStore: PinpadSettingsPasswordStore,
    hardwareOnly: Boolean,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    onRejected: () -> Unit,
) {
    var password1 by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var activeField by remember { mutableStateOf(1) }
    val password2FocusRequester = remember { FocusRequester() }
    val hardwareFocusRequester = remember { FocusRequester() }
    fun submitPasswords() {
        if (passwordStore.verify(password1, password2)) onVerified() else onRejected()
    }
    fun handleHardwarePasswordKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val digit = event.passwordDigit()
        return when {
            digit != null -> {
                if (activeField == 1) {
                    password1 = (password1 + digit).take(12)
                } else {
                    password2 = (password2 + digit).take(12)
                }
                true
            }
            event.isEnterKey() -> {
                if (activeField == 1) {
                    activeField = 2
                } else {
                    submitPasswords()
                }
                true
            }
            event.isBackKey() -> {
                onDismiss()
                true
            }
            event.isClearKey() -> {
                if (activeField == 1) {
                    password1 = password1.dropLast(1)
                } else {
                    password2 = password2.dropLast(1)
                }
                true
            }
            else -> false
        }
    }
    LaunchedEffect(hardwareOnly) {
        if (hardwareOnly) hardwareFocusRequester.requestFocus()
    }
    if (hardwareOnly) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                color = Color(0xFF111820),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_password_title),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HardwarePasswordFields(
                        modifier = Modifier
                            .focusRequester(hardwareFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent(::handleHardwarePasswordKey),
                        password1 = password1,
                        password2 = password2,
                        activeField = activeField,
                        onSelectField = { activeField = it },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = stringResource(R.string.settings_password_cancel),
                                color = Color(0xFFB8D9FF),
                                fontSize = 17.sp,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { submitPasswords() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F7EC1)),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_password_ok),
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.settings_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.isEnterKey()) {
                                password2FocusRequester.requestFocus()
                                true
                            } else {
                                false
                            }
                        },
                        value = password1,
                        onValueChange = { password1 = it.filter(Char::isDigit).take(12) },
                        label = { Text(stringResource(R.string.settings_password_1)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { password2FocusRequester.requestFocus() },
                        ),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .focusRequester(password2FocusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.isEnterKey()) {
                                    submitPasswords()
                                    true
                                } else {
                                    false
                                }
                            },
                        value = password2,
                        onValueChange = { password2 = it.filter(Char::isDigit).take(12) },
                        label = { Text(stringResource(R.string.settings_password_2)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submitPasswords() },
                        ),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { submitPasswords() },
                ) {
                    Text(stringResource(R.string.settings_password_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_password_cancel))
                }
            },
        )
    }
}

@Composable
private fun HardwarePasswordFields(
    modifier: Modifier = Modifier,
    password1: String,
    password2: String,
    activeField: Int,
    onSelectField: (Int) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HardwarePasswordRow(
            label = stringResource(R.string.settings_password_1),
            value = password1,
            active = activeField == 1,
            onClick = { onSelectField(1) },
        )
        HardwarePasswordRow(
            label = stringResource(R.string.settings_password_2),
            value = password2,
            active = activeField == 2,
            onClick = { onSelectField(2) },
        )
    }
}

@Composable
private fun HardwarePasswordRow(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) Color(0xFF8FD3FF) else Color(0xFF505965),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFF8FD3FF) else Color(0xFFC8CDD5),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = "*".repeat(value.length),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            minLines = 1,
        )
    }
}

private fun androidx.compose.ui.input.key.KeyEvent.isEnterKey(): Boolean {
    return key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter
}

private fun androidx.compose.ui.input.key.KeyEvent.isClearKey(): Boolean {
    return key == Key.Backspace || key == Key.Clear || key == Key.Escape
}

private fun androidx.compose.ui.input.key.KeyEvent.isBackKey(): Boolean {
    return key == Key.Back
}

private fun androidx.compose.ui.input.key.KeyEvent.passwordDigit(): Char? {
    return when (key) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }
}

private fun String.isCt20pModel(): Boolean {
    return uppercase().filter(Char::isLetterOrDigit).contains("CT20P")
}

private enum class SettingsProtectedAction {
    AndroidSettings,
    ExitToAndroidHome,
}

@Composable
private fun PinpadIdleScreen(serialSettingsVersion: Int) {
    val context = LocalContext.current
    val defaultMessage = stringResource(R.string.idle_default)
    val prefs = remember(context) { PinpadPreferences(context) }
    val displayState = PinpadDisplayController.state
    val contactlessLedState = PinpadDisplayController.contactlessLedState
    val idleMessage = PinpadDisplayController.idleMessage ?: prefs.idleMessage().ifBlank { defaultMessage }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF050608),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF050608)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                ) {
                    PinpadStatusBar(serialSettingsVersion = serialSettingsVersion)
                    SoftContactlessLedBar(contactlessLedState)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 58.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PinpadPromptContent(
                        state = displayState,
                        idleMessage = idleMessage,
                    )
                }
                if (BuildConfig.SENSORY_KITS_ENABLED) {
                    MastercardSensoryWarmupHost()
                }
            }
        }
    }
}

@Composable
private fun PinpadPromptContent(
    state: PinpadDisplayState,
    idleMessage: String,
) {
    when (state) {
        is PinpadDisplayState.SwipeCard -> SwipeCardPrompt(state.transaction)
        is PinpadDisplayState.InsertCard -> InsertCardPrompt(state.transaction)
        is PinpadDisplayState.TapCard -> TapCardPrompt(state.transaction)
        is PinpadDisplayState.PresentCard -> MultiInterfacePrompt(state.transaction)
        is PinpadDisplayState.Message -> PromptText(state.text)
        is PinpadDisplayState.TextEntry -> TextEntryPrompt(state)
        is PinpadDisplayState.EnterPin -> PinEntryPrompt(state.digits, state.promptLines)
        PinpadDisplayState.Processing -> PromptText(stringResource(R.string.prompt_pinpad_processing))
        PinpadDisplayState.BadRead -> PromptText(stringResource(R.string.prompt_bad_read), Color(0xFFFFD166))
        PinpadDisplayState.Declined -> DeclinedPrompt()
        PinpadDisplayState.IccFallback -> PromptText(stringResource(R.string.prompt_icc_fallback), Color(0xFFFFD166))
        is PinpadDisplayState.ApplicationSelection -> ApplicationSelectionPrompt(state)
        is PinpadDisplayState.SelectingApplication -> PromptText(
            stringResource(R.string.prompt_selecting_application, state.label),
        )
        PinpadDisplayState.OperationCancelled -> PromptText(
            stringResource(R.string.prompt_operation_cancelled),
            Color(0xFFFFD166),
        )
        is PinpadDisplayState.PinKeySchemeError -> PromptText(
            stringResource(R.string.prompt_pin_key_scheme_error, state.keyId.toString()),
            Color(0xFFFFD166),
        )
        is PinpadDisplayState.KeyMetadataMissing -> PromptText(
            stringResource(R.string.prompt_key_metadata_missing, state.keyId.toString()),
            Color(0xFFFFD166),
        )
        PinpadDisplayState.ThankYou -> PromptText(stringResource(R.string.prompt_thank_you), Color(0xFF90F0B0))
        PinpadDisplayState.Idle -> PromptText(idleMessage)
        is PinpadDisplayState.Jpeg -> JpegPrompt(state.path)
        is PinpadDisplayState.JpegSequence -> JpegSequencePrompt(state.paths)
        is PinpadDisplayState.BrandSensory -> BrandSensoryPrompt(state.brand)
    }
}

@Composable
private fun BrandSensoryPrompt(brand: SensoryBrand) {
    LaunchedEffect(brand) {
        delay(brand.fallbackMs)
        if (PinpadDisplayController.state is PinpadDisplayState.BrandSensory) {
            PinpadDisplayController.showIdle()
        }
    }
    when (brand) {
        SensoryBrand.Visa -> VisaSensoryPrompt()
        SensoryBrand.Mastercard -> MastercardSensoryPrompt()
    }
}

@Composable
private fun VisaSensoryPrompt() {
    val langCode = remember { Locale.getDefault().language.takeIf { it in setOf("en", "es") } ?: "en" }
    val backdrop = Color(0xFF1434CB)
    val backdropArgb = backdrop.toArgb()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdrop),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                SensoryBrandingView(context, null).apply {
                    soundEnabled = true
                    hapticEnabled = true
                    checkmarkMode = CheckmarkMode.CHECKMARK_WITH_TEXT
                    checkmarkText = CheckmarkTextOption.APPROVE
                    backdropColor = backdropArgb
                    languageCode = langCode
                }
            },
            update = { view ->
                view.animate { PinpadDisplayController.showIdle() }
            },
        )
    }
}

@Composable
private fun MastercardSensoryPrompt() {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        factory = { context ->
            val sonicView = SonicView(context)
            PinpadSensoryCache.playMastercard(context, sonicView)
            sonicView
        },
    )
}

@Composable
private fun MastercardSensoryWarmupHost() {
    AndroidView(
        modifier = Modifier
            .width(1.dp)
            .height(1.dp)
            .alpha(0.01f),
        factory = { context ->
            SonicView(context).also { sonicView ->
                PinpadSensoryCache.warmMastercardAnimation(context, sonicView)
            }
        },
    )
}

private val SensoryBrand.fallbackMs: Long
    get() = when (this) {
        SensoryBrand.Visa -> VISA_SENSORY_FALLBACK_MS
        SensoryBrand.Mastercard -> MASTERCARD_SENSORY_FALLBACK_MS
    }

private object PinpadSensoryCache {
    @Volatile private var sonicController: SonicController? = null
    @Volatile private var sonicReady = false
    @Volatile private var animationWarmStarted = false
    @Volatile private var animationWarmed = false
    @Volatile private var audioWarmStarted = false
    @Volatile private var audioWarmed = false
    private val merchant: SonicMerchant = SonicMerchant.Builder()
        .merchantName("Merchant")
        .merchantId("0")
        .city("")
        .countryCode("USA")
        .merchantCategoryCodes(arrayOf("5999"))
        .build()

    fun warmMastercardAnimation(context: android.content.Context, sonicView: SonicView) {
        warmAndroidAudioPath()
        if (animationWarmStarted || animationWarmed) return
        animationWarmStarted = true
        val appContext = context.applicationContext
        sonicView.postDelayed(
            {
                val controller = SonicController()
                runCatching {
                    controller.prepare(
                        sonicType = SonicType.ANIMATION_ONLY,
                        sonicCue = "checkout",
                        sonicEnvironment = SonicEnvironment.PRODUCTION,
                        merchant = merchant,
                        isHapticsEnabled = false,
                        context = appContext,
                        onPrepareListener = object : OnPrepareListener {
                            override fun onPrepared(statusCode: Int) {
                                Log.d(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic animation warm prepared status=$statusCode")
                                runCatching {
                                    controller.play(sonicView, object : OnCompleteListener {
                                        override fun onComplete(statusCode: Int) {
                                            animationWarmed = true
                                            Log.d(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic animation warmed status=$statusCode")
                                            prewarmMastercard(appContext)
                                        }
                                    })
                                }.onFailure { error ->
                                    animationWarmStarted = false
                                    Log.w(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic animation warm play failed", error)
                                    prewarmMastercard(appContext)
                                }
                            }
                        },
                    )
                }.onFailure { error ->
                    animationWarmStarted = false
                    Log.w(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic animation warm prepare failed", error)
                    prewarmMastercard(appContext)
                }
            },
            MASTERCARD_ANIMATION_WARMUP_DELAY_MS,
        )
    }

    private fun warmAndroidAudioPath() {
        if (audioWarmStarted || audioWarmed) return
        audioWarmStarted = true
        Thread(
            {
                runCatching {
                    val channelMask = AudioFormat.CHANNEL_OUT_STEREO
                    val encoding = AudioFormat.ENCODING_PCM_16BIT
                    val minBufferSize = AudioTrack.getMinBufferSize(
                        AUDIO_WARMUP_SAMPLE_RATE,
                        channelMask,
                        encoding,
                    )
                    val bufferSize = maxOf(
                        minBufferSize.takeIf { it > 0 } ?: 0,
                        AUDIO_WARMUP_SAMPLE_RATE / 20 * 4,
                    )
                    val audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(AUDIO_WARMUP_SAMPLE_RATE)
                                .setChannelMask(channelMask)
                                .build(),
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                    val silentPcm = ByteArray(bufferSize)
                    try {
                        audioTrack.setVolume(0f)
                        audioTrack.play()
                        audioTrack.write(silentPcm, 0, silentPcm.size)
                        Thread.sleep(40L)
                        audioTrack.stop()
                    } finally {
                        audioTrack.release()
                    }
                    audioWarmed = true
                    Log.d(BRAND_SENSORY_LOG_TAG, "Android audio path warmed bufferSize=$bufferSize")
                }.onFailure { error ->
                    audioWarmStarted = false
                    Log.w(BRAND_SENSORY_LOG_TAG, "Android audio path warm failed", error)
                }
            },
            "PinpadAudioWarmup",
        ).start()
    }

    fun prewarmMastercard(context: android.content.Context) {
        val appContext = context.applicationContext
        if (sonicReady) return
        val controller = SonicController()
        sonicController = controller
        runCatching {
            controller.prepare(
                sonicType = SonicType.SOUND_AND_ANIMATION,
                sonicCue = "checkout",
                sonicEnvironment = SonicEnvironment.PRODUCTION,
                merchant = merchant,
                isHapticsEnabled = true,
                context = appContext,
                onPrepareListener = object : OnPrepareListener {
                    override fun onPrepared(statusCode: Int) {
                        sonicReady = true
                        Log.d(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic prewarmed status=$statusCode")
                    }
                },
            )
        }.onFailure { error ->
            sonicController = null
            sonicReady = false
            Log.w(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic prewarm failed", error)
        }
    }

    fun playMastercard(context: android.content.Context, sonicView: SonicView) {
        val cached = takePreparedController()
        if (cached != null) {
            Log.d(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic playing from prewarmed controller")
            playPreparedController(context, sonicView, cached)
            return
        }
        Log.d(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic cache miss; preparing now")
        val controller = SonicController()
        runCatching {
            controller.prepare(
                sonicType = SonicType.SOUND_AND_ANIMATION,
                sonicCue = "checkout",
                sonicEnvironment = SonicEnvironment.PRODUCTION,
                merchant = merchant,
                isHapticsEnabled = true,
                context = context,
                onPrepareListener = object : OnPrepareListener {
                    override fun onPrepared(statusCode: Int) {
                        if (!isMastercardStillVisible()) {
                            Log.d(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic prepared after prompt ended; skipping play")
                            prewarmMastercard(context)
                            return
                        }
                        playPreparedController(context, sonicView, controller)
                    }
                },
            )
        }.onFailure { error ->
            Log.w(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic prepare failed", error)
            PinpadDisplayController.showIdle()
        }
    }

    private fun takePreparedController(): SonicController? {
        if (!sonicReady) return null
        sonicReady = false
        return sonicController.also { sonicController = null }
    }

    private fun playPreparedController(
        context: android.content.Context,
        sonicView: SonicView,
        controller: SonicController,
    ) {
        if (!isMastercardStillVisible()) return
        runCatching {
            controller.play(sonicView, object : OnCompleteListener {
                override fun onComplete(statusCode: Int) {
                    Log.d(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic complete status=$statusCode")
                    PinpadDisplayController.showIdle()
                    prewarmMastercard(context)
                }
            })
        }.onFailure { error ->
            Log.w(BRAND_SENSORY_LOG_TAG, "Mastercard Sonic play failed", error)
            PinpadDisplayController.showIdle()
            prewarmMastercard(context)
        }
    }

    private fun isMastercardStillVisible(): Boolean {
        val state = PinpadDisplayController.state
        return state is PinpadDisplayState.BrandSensory && state.brand == SensoryBrand.Mastercard
    }
}

@Composable
private fun SoftContactlessLedBar(state: ContactlessLedState) {
    if (state == ContactlessLedState.Off) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C1118))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftContactlessLedDot(state.blue, Color(0xFF3DA5FF))
        SoftContactlessLedDot(state.yellow, Color(0xFFFFD166))
        SoftContactlessLedDot(state.green, Color(0xFF3EE28A))
        SoftContactlessLedDot(state.red, Color(0xFFFF5C5C))
    }
}

@Composable
private fun SoftContactlessLedDot(
    active: Boolean,
    activeColor: Color,
) {
    val fillColor = if (active) activeColor else Color(0xFF202733)
    val borderColor = if (active) activeColor.copy(alpha = 0.85f) else Color(0xFF53606D)
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(24.dp)
            .background(fillColor, RoundedCornerShape(50))
            .border(2.dp, borderColor, RoundedCornerShape(50)),
    )
}

@Composable
private fun TextEntryPrompt(state: PinpadDisplayState.TextEntry) {
    val displayedText = when (state.echoMode) {
        TextEntryEchoMode.Masked -> "*".repeat(state.text.length)
        TextEntryEchoMode.Plain -> state.text
        TextEntryEchoMode.Hidden -> ""
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PromptText(state.prompt.ifBlank { stringResource(R.string.prompt_enter_data) })
        Box(
            modifier = Modifier
                .width(270.dp)
                .height(58.dp)
                .background(Color(0xFF101822), RoundedCornerShape(8.dp))
                .border(2.dp, Color(0xFF8FD3FF), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = displayedText,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PinEntryPrompt(
    digits: Int,
    promptLines: List<String>,
) {
    val displayLines = promptLines.ifEmpty { listOf(stringResource(R.string.prompt_enter_pin)) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            displayLines.take(2).forEach { line ->
                PromptText(line)
            }
        }
        Box(
            modifier = Modifier
                .width(230.dp)
                .height(58.dp)
                .background(Color(0xFF101822), RoundedCornerShape(8.dp))
                .border(2.dp, Color(0xFF8FD3FF), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "*".repeat(digits.coerceIn(0, 12)),
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun JpegSequencePrompt(paths: List<String>) {
    var index by remember(paths) { mutableStateOf(0) }
    LaunchedEffect(paths) {
        while (paths.isNotEmpty()) {
            delay(3_000)
            index = (index + 1) % paths.size
        }
    }
    JpegPrompt(paths.getOrNull(index).orEmpty())
}

@Composable
private fun JpegPrompt(path: String) {
    val bitmap = remember(path) {
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    } else {
        PromptText(path.substringAfterLast('/').ifBlank { "JPEG" })
    }
}

@Composable
private fun PromptText(
    text: String,
    color: Color = Color.White,
) {
    Text(
        text = text,
        color = color,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        lineHeight = 38.sp,
    )
}

@Composable
private fun TransactionPromptText(
    text: String,
    transaction: PinpadTransactionDisplay?,
    color: Color = Color.White,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TransactionSummary(transaction, centered = true)
        PromptText(text, color)
    }
}

@Composable
private fun TransactionSummary(
    transaction: PinpadTransactionDisplay?,
    centered: Boolean,
) {
    if (transaction == null) return
    val label = transactionTypeLabel(transaction.transactionTypeCode)
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFF8FD3FF),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            lineHeight = 32.sp,
        )
        Text(
            text = "${transaction.displayCurrency} ${transaction.amountText}",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            lineHeight = 34.sp,
        )
    }
}

@Composable
private fun transactionTypeLabel(typeCode: String): String {
    return when (typeCode.uppercase(Locale.US)) {
        "00" -> stringResource(R.string.transaction_type_sale)
        "01" -> stringResource(R.string.transaction_type_cash)
        "09" -> stringResource(R.string.transaction_type_cashback)
        "20" -> stringResource(R.string.transaction_type_refund)
        else -> stringResource(R.string.transaction_type_generic, typeCode)
    }
}

@Composable
private fun DeclinedPrompt() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Canvas(
            modifier = Modifier
                .width(142.dp)
                .height(142.dp),
        ) {
            val red = Color(0xFFFF4D4D)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.42f
            drawCircle(
                color = red.copy(alpha = 0.18f),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = red,
                radius = radius,
                center = center,
                style = Stroke(width = 7.dp.toPx()),
            )
            val mark = radius * 0.48f
            drawLine(
                color = red,
                start = Offset(center.x - mark, center.y - mark),
                end = Offset(center.x + mark, center.y + mark),
                strokeWidth = 9.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = red,
                start = Offset(center.x + mark, center.y - mark),
                end = Offset(center.x - mark, center.y + mark),
                strokeWidth = 9.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        PromptText(stringResource(R.string.prompt_declined), Color(0xFFFF6B6B))
    }
}

@Composable
private fun ApplicationSelectionPrompt(state: PinpadDisplayState.ApplicationSelection) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = stringResource(R.string.prompt_select_application),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            lineHeight = 28.sp,
        )
        state.labels.forEachIndexed { index, label ->
            val selected = index == state.selectedIndex
            val borderColor = if (selected) Color(0xFF8FD3FF) else Color(0xFF2D3748)
            val backgroundColor = if (selected) Color(0xFF1F3646) else Color(0xFF101820)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .border(2.dp, borderColor, RoundedCornerShape(6.dp))
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .clickable { PinpadDisplayController.selectApplication(index) }
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = (index + 1).toString(),
                    color = if (selected) Color(0xFF8FD3FF) else Color(0xFFB8C3CF),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    text = label.ifBlank { stringResource(R.string.prompt_application_option, index + 1) },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    lineHeight = 26.sp,
                )
            }
        }
    }
}

@Composable
private fun MultiInterfacePrompt(transaction: PinpadTransactionDisplay?) {
    val transition = rememberInfiniteTransition(label = "multi-interface")
    val tapProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_250),
            repeatMode = RepeatMode.Restart,
        ),
        label = "multi-interface-tap-progress",
    )
    val cardMotionProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "multi-interface-card-progress",
    )
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val cardColor = Color(0xFFE7EDF5)
            val detailColor = Color(0xFF172130)
            val accentColor = Color(0xFF8FD3FF)

            val tapCardWidth = 78.dp.toPx()
            val tapCardHeight = 50.dp.toPx()
            val tapCardLeft = (size.width - tapCardWidth) / 2f - 16.dp.toPx()
            val tapCardTop = 2.dp.toPx() - 4.dp.toPx() * tapProgress
            drawRoundRect(
                color = cardColor,
                topLeft = Offset(tapCardLeft, tapCardTop),
                size = Size(tapCardWidth, tapCardHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = detailColor,
                topLeft = Offset(tapCardLeft + 9.dp.toPx(), tapCardTop + 10.dp.toPx()),
                size = Size(24.dp.toPx(), 16.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            val waveCenter = Offset(tapCardLeft + tapCardWidth + 18.dp.toPx(), tapCardTop + tapCardHeight / 2f)
            repeat(3) { index ->
                val radius = (14 + index * 13).dp.toPx() + 2.dp.toPx() * tapProgress
                drawArc(
                    color = accentColor,
                    startAngle = -48f,
                    sweepAngle = 96f,
                    useCenter = false,
                    topLeft = Offset(waveCenter.x - radius, waveCenter.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            val swipeCardWidth = 48.dp.toPx()
            val swipeCardHeight = 76.dp.toPx()
            val swipeCardLeft = size.width - swipeCardWidth - 4.dp.toPx()
            val swipeSlotX = swipeCardLeft - 7.dp.toPx()
            val swipeSlotTop = 84.dp.toPx()
            val swipeSlotBottom = size.height - 118.dp.toPx()
            drawLine(
                color = cardColor,
                start = Offset(swipeSlotX, swipeSlotTop),
                end = Offset(swipeSlotX, swipeSlotBottom),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            val swipeCardTop = swipeSlotTop + 10.dp.toPx() +
                (swipeSlotBottom - swipeSlotTop - swipeCardHeight - 20.dp.toPx()) * cardMotionProgress
            drawRoundRect(
                color = cardColor,
                topLeft = Offset(swipeCardLeft, swipeCardTop),
                size = Size(swipeCardWidth, swipeCardHeight),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
            )
            drawRoundRect(
                color = detailColor,
                topLeft = Offset(swipeCardLeft + 6.dp.toPx(), swipeCardTop + 8.dp.toPx()),
                size = Size(9.dp.toPx(), swipeCardHeight - 16.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
            repeat(2) { index ->
                val y = swipeCardTop + swipeCardHeight + 10.dp.toPx() + index * 13.dp.toPx()
                val centerX = swipeCardLeft + swipeCardWidth / 2f
                drawLine(
                    color = accentColor,
                    start = Offset(centerX - 10.dp.toPx(), y),
                    end = Offset(centerX, y + 9.dp.toPx()),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = accentColor,
                    start = Offset(centerX + 10.dp.toPx(), y),
                    end = Offset(centerX, y + 9.dp.toPx()),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            val insertSlotY = size.height - 94.dp.toPx()
            val insertSlotWidth = 118.dp.toPx()
            val insertSlotLeft = (size.width - insertSlotWidth) / 2f
            drawLine(
                color = cardColor,
                start = Offset(insertSlotLeft, insertSlotY),
                end = Offset(insertSlotLeft + insertSlotWidth, insertSlotY),
                strokeWidth = 7.dp.toPx(),
                cap = StrokeCap.Round,
            )
            val insertCardWidth = 58.dp.toPx()
            val insertCardHeight = 96.dp.toPx()
            val insertCardLeft = (size.width - insertCardWidth) / 2f
            val insertCardTop = size.height - insertCardHeight - 2.dp.toPx() - 34.dp.toPx() * cardMotionProgress
            drawRoundRect(
                color = cardColor,
                topLeft = Offset(insertCardLeft, insertCardTop),
                size = Size(insertCardWidth, insertCardHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = detailColor,
                topLeft = Offset(insertCardLeft + 12.dp.toPx(), insertCardTop + 10.dp.toPx()),
                size = Size(34.dp.toPx(), 22.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            repeat(2) { index ->
                val y = insertCardTop - 14.dp.toPx() - index * 14.dp.toPx() - 4.dp.toPx() * cardMotionProgress
                val centerX = size.width / 2f
                drawLine(
                    color = accentColor,
                    start = Offset(centerX - 11.dp.toPx(), y),
                    end = Offset(centerX, y - 10.dp.toPx()),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = accentColor,
                    start = Offset(centerX + 11.dp.toPx(), y),
                    end = Offset(centerX, y - 10.dp.toPx()),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(175.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (transaction == null) {
                Text(
                    text = stringResource(R.string.prompt_present_card),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                    lineHeight = 26.sp,
                )
            } else {
                TransactionSummary(transaction, centered = false)
            }
        }
    }
}

@Composable
private fun InsertCardPrompt(transaction: PinpadTransactionDisplay?) {
    val transition = rememberInfiniteTransition(label = "insert-card")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "insert-progress",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-74).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TransactionSummary(transaction, centered = true)
            PromptText(stringResource(R.string.prompt_insert_card))
        }
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(230.dp)
                .height(198.dp)
                .offset(y = 18.dp),
        ) {
            val cardColor = Color(0xFFE7EDF5)
            val detailColor = Color(0xFF172130)
            val accentColor = Color(0xFF8FD3FF)
            val slotY = size.height - 54.dp.toPx()
            val slotWidth = 132.dp.toPx()
            val slotLeft = (size.width - slotWidth) / 2f
            drawLine(
                color = cardColor,
                start = Offset(slotLeft, slotY),
                end = Offset(slotLeft + slotWidth, slotY),
                strokeWidth = 7.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val cardWidth = 64.dp.toPx()
            val cardHeight = 106.dp.toPx()
            val cardLeft = (size.width - cardWidth) / 2f
            val cardTop = size.height - cardHeight - 4.dp.toPx() - 34.dp.toPx() * progress
            drawRoundRect(
                color = cardColor,
                topLeft = Offset(cardLeft, cardTop),
                size = Size(cardWidth, cardHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = detailColor,
                topLeft = Offset(cardLeft + 13.dp.toPx(), cardTop + 11.dp.toPx()),
                size = Size(38.dp.toPx(), 24.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            repeat(2) { index ->
                val y = cardTop - 16.dp.toPx() - index * 15.dp.toPx() - 4.dp.toPx() * progress
                val centerX = size.width / 2f
                drawLine(
                    color = accentColor,
                    start = Offset(centerX - 12.dp.toPx(), y),
                    end = Offset(centerX, y - 11.dp.toPx()),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = accentColor,
                    start = Offset(centerX + 12.dp.toPx(), y),
                    end = Offset(centerX, y - 11.dp.toPx()),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun TapCardPrompt(transaction: PinpadTransactionDisplay?) {
    val transition = rememberInfiniteTransition(label = "tap-card")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_150),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tap-progress",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Canvas(
            modifier = Modifier
                .width(210.dp)
                .height(128.dp),
        ) {
            val cardWidth = 92.dp.toPx()
            val cardHeight = 62.dp.toPx()
            val cardLeft = 34.dp.toPx()
            val cardTop = 34.dp.toPx() - 8.dp.toPx() * progress
            drawRoundRect(
                color = Color(0xFFE7EDF5),
                topLeft = Offset(cardLeft, cardTop),
                size = Size(cardWidth, cardHeight),
                cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xFF172130),
                topLeft = Offset(cardLeft + 10.dp.toPx(), cardTop + 12.dp.toPx()),
                size = Size(26.dp.toPx(), 18.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            drawLine(
                color = Color(0xFF172130),
                start = Offset(cardLeft + 12.dp.toPx(), cardTop + 42.dp.toPx()),
                end = Offset(cardLeft + cardWidth - 12.dp.toPx(), cardTop + 42.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val waveCenter = Offset(cardLeft + cardWidth + 24.dp.toPx(), cardTop + cardHeight / 2f)
            repeat(3) { index ->
                val radius = (18 + index * 16).dp.toPx() + 3.dp.toPx() * progress
                drawArc(
                    color = Color(0xFF8FD3FF),
                    startAngle = -48f,
                    sweepAngle = 96f,
                    useCenter = false,
                    topLeft = Offset(waveCenter.x - radius, waveCenter.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        TransactionPromptText(stringResource(R.string.prompt_tap_card), transaction)
    }
}

@Composable
private fun SwipeCardPrompt(transaction: PinpadTransactionDisplay?) {
    val transition = rememberInfiniteTransition(label = "swipe-card")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_250),
            repeatMode = RepeatMode.Restart,
        ),
        label = "swipe-progress",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Canvas(
            modifier = Modifier
                .width(210.dp)
                .height(128.dp),
        ) {
            val cardWidth = 86.dp.toPx()
            val cardHeight = 64.dp.toPx()
            val cardLeft = (size.width - cardWidth) / 2f - 10.dp.toPx()
            val cardTop = 10.dp.toPx()
            drawRoundRect(
                color = Color(0xFFE7EDF5),
                topLeft = Offset(cardLeft, cardTop),
                size = Size(cardWidth, cardHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xFF172130),
                topLeft = Offset(cardLeft + 8.dp.toPx(), cardTop + 10.dp.toPx()),
                size = Size(cardWidth - 16.dp.toPx(), 12.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )

            val slotX = cardLeft + cardWidth + 14.dp.toPx()
            drawLine(
                color = Color(0xFFE7EDF5),
                start = Offset(slotX, cardTop - 3.dp.toPx()),
                end = Offset(slotX, cardTop + cardHeight + 4.dp.toPx()),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val arrowTop = cardTop + cardHeight + 8.dp.toPx() + 12.dp.toPx() * progress
            repeat(2) { index ->
                val y = arrowTop + index * 14.dp.toPx()
                val centerX = cardLeft + cardWidth / 2f
                drawLine(
                    color = Color(0xFF8FD3FF),
                    start = Offset(centerX - 12.dp.toPx(), y),
                    end = Offset(centerX, y + 10.dp.toPx()),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color(0xFF8FD3FF),
                    start = Offset(centerX + 12.dp.toPx(), y),
                    end = Offset(centerX, y + 10.dp.toPx()),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        TransactionPromptText(stringResource(R.string.prompt_swipe_card), transaction)
    }
}

@Composable
private fun PinpadStatusBar(
    serialSettingsVersion: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val networkStatus = rememberNetworkStatus()
    val prefs = remember(context) { PinpadPreferences(context) }
    val modelName = remember(context) { (context.applicationContext as PinpadApplication).deviceInfoProvider.modelName() }
    val serialSettings = remember(serialSettingsVersion, modelName) { prefs.serialSettings(modelName) }
    var dateTime by remember { mutableStateOf(formatDateTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            dateTime = formatDateTime()
            delay(1_000L)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF172130))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateTime,
            color = Color(0xFFE7EDF5),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = serialSettings.portDisplayText(),
                color = Color(0xFF8FD3FF),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
            NetworkStatusIndicator(networkStatus)
        }
    }
}

private fun SerialSettings.portDisplayText(): String {
    return when (transportMode.uppercase()) {
        "RS232" -> "RS232:$rs232Port"
        "USB_CDC" -> "USB"
        else -> "AUTO"
    }
}

@Composable
private fun rememberNetworkStatus(): NetworkStatus {
    val context = LocalContext.current
    val connectivityManager = remember(context) {
        context.getSystemService(ConnectivityManager::class.java)
    }
    var status by remember(connectivityManager) {
        mutableStateOf(connectivityManager.currentNetworkStatus())
    }

    DisposableEffect(connectivityManager) {
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { status = connectivityManager.currentNetworkStatus() }
            }

            override fun onLost(network: Network) {
                mainHandler.post { status = connectivityManager.currentNetworkStatus() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                mainHandler.post { status = connectivityManager.currentNetworkStatus() }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    return status
}

@Composable
private fun NetworkStatusIndicator(status: NetworkStatus) {
    when (status.kind) {
        NetworkKind.Wifi -> WifiSignalIcon(level = status.wifiLevel)
        else -> Text(
            text = status.displayText(),
            color = status.displayColor(),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun WifiSignalIcon(level: Int) {
    val activeColor = Color(0xFF90F0B0)
    val inactiveColor = Color(0xFF53606D)
    Canvas(
        modifier = Modifier
            .width(34.dp)
            .height(24.dp),
    ) {
        val strokeWidth = 3.dp.toPx()
        val centerX = size.width / 2f
        val baseY = size.height - 2.dp.toPx()
        WIFI_ARC_WIDTHS.forEachIndexed { index, arcWidthDp ->
            val arcWidth = arcWidthDp.dp.toPx()
            val arcHeight = WIFI_ARC_HEIGHTS[index].dp.toPx()
            val topLeft = Offset(centerX - arcWidth / 2f, baseY - arcHeight)
            val color = if (index < level) activeColor else inactiveColor
            drawArc(
                color = color,
                startAngle = 212f,
                sweepAngle = 116f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(arcWidth, arcHeight * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        drawCircle(
            color = if (level > 0) activeColor else inactiveColor,
            radius = 2.6.dp.toPx(),
            center = Offset(centerX, baseY - 1.dp.toPx()),
        )
    }
}

private fun ConnectivityManager.currentNetworkStatus(): NetworkStatus {
    val network = activeNetwork ?: return NetworkStatus.Offline
    val capabilities = getNetworkCapabilities(network) ?: return NetworkStatus.Offline
    return when {
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkStatus.Offline
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.wifi(
            capabilities.signalStrength.toWifiLevel(),
        )
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.Cellular
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus.Ethernet
        else -> NetworkStatus.Online
    }
}

private fun Int.toWifiLevel(): Int {
    if (this == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) return WIFI_BAR_COUNT
    return when {
        this <= -85 -> 1
        this <= -70 -> 2
        this <= -55 -> 3
        else -> 4
    }
}

private fun formatDateTime(): String {
    return STATUS_DATE_FORMAT.format(Date())
}

private data class NetworkStatus(
    val kind: NetworkKind,
    val wifiLevel: Int = WIFI_BAR_COUNT,
) {
    fun displayText(): String {
        return when (kind) {
            NetworkKind.Wifi -> ""
            NetworkKind.Cellular -> "CELL"
            NetworkKind.Ethernet -> "ETH"
            NetworkKind.Online -> "ONLINE"
            NetworkKind.Offline -> "OFFLINE"
        }
    }

    fun displayColor(): Color {
        return when (kind) {
            NetworkKind.Offline -> Color(0xFFFFB4AB)
            else -> Color(0xFF90F0B0)
        }
    }

    companion object {
        fun wifi(level: Int): NetworkStatus = NetworkStatus(NetworkKind.Wifi, level)
        val Cellular = NetworkStatus(NetworkKind.Cellular)
        val Ethernet = NetworkStatus(NetworkKind.Ethernet)
        val Online = NetworkStatus(NetworkKind.Online)
        val Offline = NetworkStatus(NetworkKind.Offline)
    }
}

private enum class NetworkKind {
    Wifi,
    Cellular,
    Ethernet,
    Online,
    Offline,
}

@Composable
private fun PinpadSetupScreen(
    onClose: () -> Unit,
    onSaveSettings: (SerialSettings) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { PinpadPreferences(context) }
    val modelName = remember(context) { (context.applicationContext as PinpadApplication).deviceInfoProvider.modelName() }
    val deviceSpec = remember(modelName) { DeviceModelConfig.getDeviceSpec(modelName) }
    var settings by remember(modelName) { mutableStateOf(prefs.serialSettings(modelName)) }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF080A0F),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.setup_title),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.setup_hint),
                    color = Color(0xFFC8CDD5),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
                SetupCycleRow(
                    label = stringResource(R.string.setup_transport),
                    value = settings.transportMode,
                    values = TRANSPORT_MODES,
                    onChanged = { settings = settings.copy(transportMode = it) },
                )
                SetupCycleRow(
                    label = stringResource(R.string.setup_rs232_port),
                    value = settings.rs232Port.toString(),
                    values = deviceSpec.serialPortOptions().map(Int::toString),
                    onChanged = { settings = settings.copy(rs232Port = it.toInt()) },
                )
                SetupCycleRow(
                    label = stringResource(R.string.setup_baud_rate),
                    value = settings.baudRate.toString(),
                    values = BAUD_RATES.map(Int::toString),
                    onChanged = { settings = settings.copy(baudRate = it.toInt()) },
                )
                SetupCycleRow(
                    label = stringResource(R.string.setup_data_bits),
                    value = settings.dataBits.toString(),
                    values = DATA_BITS.map(Int::toString),
                    onChanged = { settings = settings.copy(dataBits = it.toInt()) },
                )
                SetupCycleRow(
                    label = stringResource(R.string.setup_stop_bits),
                    value = settings.stopBits.toString(),
                    values = STOP_BITS.map(Int::toString),
                    onChanged = { settings = settings.copy(stopBits = it.toInt()) },
                )
                SetupCycleRow(
                    label = stringResource(R.string.setup_parity),
                    value = settings.parity,
                    values = PARITY_VALUES,
                    onChanged = { settings = settings.copy(parity = it) },
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        onClick = {
                            onSaveSettings(settings)
                            onClose()
                        },
                    ) {
                        Text(stringResource(R.string.setup_save))
                    }
                    TextButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        onClick = onClose,
                    ) {
                        Text(stringResource(R.string.setup_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCycleRow(
    label: String,
    value: String,
    values: List<String>,
    onChanged: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF151A22))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            color = Color.White,
            fontSize = 18.sp,
        )
        TextButton(
            modifier = Modifier.height(44.dp),
            onClick = { onChanged(values.previousOf(value)) },
        ) {
            Text("-", fontSize = 20.sp)
        }
        Text(
            modifier = Modifier.weight(0.8f),
            text = value,
            color = Color(0xFF8FD3FF),
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(
            modifier = Modifier.height(44.dp),
            onClick = { onChanged(values.nextOf(value)) },
        ) {
            Text("+", fontSize = 20.sp)
        }
    }
}

private fun List<String>.nextOf(value: String): String {
    val index = indexOf(value).takeIf { it >= 0 } ?: 0
    return this[(index + 1) % size]
}

private fun List<String>.previousOf(value: String): String {
    val index = indexOf(value).takeIf { it >= 0 } ?: 0
    return this[(index - 1 + size) % size]
}

private val TRANSPORT_MODES = listOf("AUTO", "RS232", "USB_CDC")
private val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400)
private val DATA_BITS = listOf(8, 7, 6, 5)
private val STOP_BITS = listOf(1, 2)
private val PARITY_VALUES = listOf("N", "E", "O")
private val STATUS_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
private const val WIFI_BAR_COUNT = 4
private val WIFI_ARC_WIDTHS = listOf(12, 20, 28)
private val WIFI_ARC_HEIGHTS = listOf(6, 10, 14)
private const val SETTINGS_PASSWORD_MAX_FAILURES = 3
private const val SETTINGS_PASSWORD_LOCKOUT_MS = 30_000L

private fun DeviceModelSpec.serialPortOptions(): List<Int> {
    return listOfNotNull(defaultSerialPort, alternateSerialPort, 0, 1, 2, 101).distinct()
}
