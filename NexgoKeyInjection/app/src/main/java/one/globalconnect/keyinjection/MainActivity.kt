package one.globalconnect.keyinjection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.nexgo.oaf.apiv3.SdkResult
import one.globalconnect.keyinjection.comm.UartManager
import one.globalconnect.keyinjection.config.DeviceModelConfig
import one.globalconnect.keyinjection.ui.ChangePasswordScreen
import one.globalconnect.keyinjection.ui.GlobalConnectButton
import one.globalconnect.keyinjection.ui.KeyInjectionScreen
import one.globalconnect.keyinjection.ui.KeyStatusScreen
import one.globalconnect.keyinjection.ui.LoginScreen
import one.globalconnect.keyinjection.ui.TopBanner
import one.globalconnect.keyinjection.ui.theme.GlobalConnectKeyInjectionTheme
import one.globalconnect.keyinjection.util.Logger
import one.globalconnect.keyinjection.util.PedProxy
import one.globalconnect.keyinjection.util.UsbPermissionHelper
import one.globalconnect.keyinjection.util.UsbSerialCableDetector
import one.globalconnect.keyinjection.util.UsbSerialCableType
import kotlinx.coroutines.launch


/**
 * Main entry point of the application. It hosts the composable screens and controls
 * navigation between login, main menu and password change flows.
 *
 * Author: Fabian Ramirez
 */
class MainActivity : ComponentActivity() {
    /**
     * Inflate the main composable hierarchy and initialize navigation state.
     * If password prompts are enabled and any keys are present on the device,
     * the login screen is shown first, otherwise the main menu is displayed.
     *
     * @param savedInstanceState previously saved state bundle, if any
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val passwordManager = PasswordManager(this)

        lifecycleScope.launch {
            when (UsbPermissionHelper.ensurePermission(
                this@MainActivity,
                UartManager.FTDI_USB_VENDOR_ID,
                UartManager.FTDI_USB_PRODUCT_ID,
                )) {
                UsbPermissionHelper.Result.GRANTED ->
                    Logger.i(TAG, "FTDI USB serial permission granted")

                UsbPermissionHelper.Result.DEVICE_NOT_FOUND -> {
                    Logger.w(TAG, "FTDI USB serial device not found; Searching for PL2303...")
                    when (UsbPermissionHelper.ensurePermission(
                        this@MainActivity,
                        UartManager.PL2303_USB_VENDOR_ID,
                        UartManager.PL2303_USB_PRODUCT_ID,
                    )) {
                        UsbPermissionHelper.Result.GRANTED ->
                            Logger.i(TAG, "PL2303 USB serial permission granted")

                        UsbPermissionHelper.Result.DEVICE_NOT_FOUND ->
                            Logger.w(TAG, "PL2303 USB serial device not found; will use internal COM port")

                        UsbPermissionHelper.Result.DENIED ->
                            Logger.w(TAG, "PL2303 USB serial permission denied by user")
                    }
                }
                UsbPermissionHelper.Result.DENIED ->
                    Logger.w(TAG, "FTDI USB serial permission denied by user")
            }
        }

        val initialScreen = determineInitialScreen()

        setContent {
            GlobalConnectKeyInjectionTheme {
                val screen = remember { mutableStateOf(initialScreen) }
                when (screen.value) {
                    Screen.LOGIN ->
                        LoginScreen(passwordManager) { screen.value = Screen.MAIN }
                    Screen.MAIN ->
                        MainScreen(
                            onInject = { screen.value = Screen.INJECT },
                            onKeyStatus = { screen.value = Screen.KEY_STATUS },
                            onChangePassword1 = { screen.value = Screen.CHANGE_PASS1 },
                            onChangePassword2 = { screen.value = Screen.CHANGE_PASS2 }
                        )
                    Screen.INJECT ->
                        KeyInjectionScreen { screen.value = Screen.MAIN }
                    Screen.KEY_STATUS ->
                        KeyStatusScreen(
                            onClose = { screen.value = Screen.MAIN },
                            onPrint = {}
                        )
                    Screen.CHANGE_PASS1 ->
                        ChangePasswordScreen(passwordManager, 1) { screen.value = Screen.MAIN }
                    Screen.CHANGE_PASS2 ->
                        ChangePasswordScreen(passwordManager, 2) { screen.value = Screen.MAIN }
                }
            }
        }
    }

    /**
     * Decide which screen should be shown when the activity starts based on
     * build configuration and whether the PED already contains keys.
     *
     * @return initial [Screen] to display
     */
    private fun determineInitialScreen(): Screen {
        if (BuildConfig.DISABLE_PASSWORDS) {
            Logger.i(TAG, "Password prompts disabled via BuildConfig; opening main screen")
            return Screen.MAIN
        }

        val hasExistingKeys = PedProxy.hasExistingKeys()
        Logger.i(TAG, "Existing PED keys detected: $hasExistingKeys")
        return if (hasExistingKeys) Screen.LOGIN else Screen.MAIN
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

/**
 * Enumeration of the possible screens shown by [MainActivity].
 */
private enum class Screen { LOGIN, MAIN, INJECT, KEY_STATUS, CHANGE_PASS1, CHANGE_PASS2 }

private const val MAIN_SCREEN_TAG = "MainScreen"

/**
 * Main menu displayed after successful login. It allows the user to trigger
 * key injection or navigate to password change screens.
 *
 * @param onInject callback invoked to navigate to the key injection screen
 * @param onKeyStatus callback invoked to navigate to the key status screen
 * @param onChangePassword1 callback invoked to navigate to the first password change screen
 * @param onChangePassword2 callback invoked to navigate to the second password change screen
 */
@Composable
fun MainScreen(
    onInject: () -> Unit,
    onKeyStatus: () -> Unit,
    onChangePassword1: () -> Unit,
    onChangePassword2: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TopBanner()
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val usbCdcEnabled = remember { mutableStateOf(App.deviceEngine.platform.usbCdcStatus) }
            val context = LocalContext.current

            // Check if device supports USB-CDC
            //App.deviceEngine.platform.enableControlBar();
            App.deviceEngine.platform.showNavigationBar();
            val deviceModel = App.deviceEngine.deviceInfo.getModel()
            val deviceSupportsUsbCdc = DeviceModelConfig.supportsUsbCdc(deviceModel)
            if (!deviceSupportsUsbCdc) {
                Logger.i(MAIN_SCREEN_TAG, "Device model $deviceModel does not support USB-CDC")
            }

            var connectedCable by remember {
                mutableStateOf(UsbSerialCableDetector.detectConnectedCable(context))
            }
            DisposableEffect(context) {
                val appContext = context.applicationContext
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        connectedCable = UsbSerialCableDetector.detectConnectedCable(appContext)
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(receiver, filter)
                }
                onDispose {
                    try {
                        appContext.unregisterReceiver(receiver)
                    } catch (_: IllegalArgumentException) {
                        // Receiver already unregistered.
                    }
                }
            }
            val buttonModifier = Modifier
                .fillMaxWidth(0.8f)
                .height(60.dp)
            GlobalConnectButton(onClick = onInject, modifier = buttonModifier) {
                Text(stringResource(R.string.key_inject), fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            GlobalConnectButton(onClick = onKeyStatus, modifier = buttonModifier) {
                Text(stringResource(R.string.key_status), fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            GlobalConnectButton(onClick = onChangePassword1, modifier = buttonModifier) {
                Text(stringResource(R.string.change_password1), fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            GlobalConnectButton(onClick = onChangePassword2, modifier = buttonModifier) {
                Text(stringResource(R.string.change_password2), fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            GlobalConnectButton(
                onClick = {
                    if (connectedCable != null) {
                        Logger.i(
                            MAIN_SCREEN_TAG,
                            "Ignoring USB CDC toggle while $connectedCable cable is connected"
                        )
                        return@GlobalConnectButton
                    }
                    val platform = App.deviceEngine.platform
                    val currentlyEnabled = usbCdcEnabled.value
                    val result = if (currentlyEnabled) {
                        platform.disableUsbCdc()
                    } else {
                        platform.enableUsbCdc()
                    }
                    if (result == SdkResult.Success) {
                        //Sleep one second here to give the platform time to respond
                        Thread.sleep(1000)
                        usbCdcEnabled.value = platform.usbCdcStatus
                    } else {
                        val action = if (currentlyEnabled) "disable" else "enable"
                        Logger.e(MAIN_SCREEN_TAG, "Failed to $action USB CDC: $result")
                    }
                },
                modifier = buttonModifier,
                enabled = deviceSupportsUsbCdc && connectedCable == null,
            ) {
                val label = when {
                    !deviceSupportsUsbCdc -> R.string.usb_cdc_not_supported
                    connectedCable == UsbSerialCableType.FTDI -> R.string.ftdi_cable_connected
                    connectedCable == UsbSerialCableType.PL2303 -> R.string.pl2303_cable_connected
                    usbCdcEnabled.value -> R.string.disable_usb_cdc
                    else -> R.string.enable_usb_cdc
                }
                Text(stringResource(label), fontSize = 20.sp)
            }
        }
    }
}
