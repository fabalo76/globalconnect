package one.globalconnect.paymentapp

// Android and Jetpack Compose imports
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

// Project-specific imports
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.AMOUNT_KEY
import one.globalconnect.paymentapp.navigation.CHECK_IN_ID_KEY
import one.globalconnect.paymentapp.navigation.FOLIO_KEY
import one.globalconnect.paymentapp.navigation.NavigationManager
import one.globalconnect.paymentapp.navigation.ORIGINAL_TRANSACTION_ID_KEY
import one.globalconnect.paymentapp.navigation.dst_Batch
import one.globalconnect.paymentapp.navigation.dst_Sale
import one.globalconnect.paymentapp.navigation.dst_CardTransaction
import one.globalconnect.paymentapp.navigation.DESTINATION_KEY
import one.globalconnect.paymentapp.navigation.dst_CheckIn
import one.globalconnect.paymentapp.navigation.dst_CheckInReport
import one.globalconnect.paymentapp.navigation.dst_CheckOut
import one.globalconnect.paymentapp.navigation.dst_EditTransaction
import one.globalconnect.paymentapp.navigation.dst_EndOfDay
import one.globalconnect.paymentapp.navigation.dst_MoreMenu
import one.globalconnect.paymentapp.navigation.ReportShortcut
import one.globalconnect.paymentapp.navigation.dst_PasswordScreen
import one.globalconnect.paymentapp.navigation.dst_QuickTip
import one.globalconnect.paymentapp.navigation.dst_Refund
import one.globalconnect.paymentapp.navigation.dst_Reports
import one.globalconnect.paymentapp.navigation.NewTransactionMenuScreen
import one.globalconnect.paymentapp.navigation.dst_NewTransaction
import one.globalconnect.paymentapp.navigation.dst_Signature
import one.globalconnect.paymentapp.navigation.dst_SystemSettings
import one.globalconnect.paymentapp.navigation.TAX1_KEY
import one.globalconnect.paymentapp.navigation.TAX2_KEY
import one.globalconnect.paymentapp.navigation.TIP_KEY
import one.globalconnect.paymentapp.navigation.TRANSACTION_ID_KEY
import one.globalconnect.paymentapp.navigation.TRANSACTION_TYPE_KEY
import one.globalconnect.paymentapp.navigation.dst_TipScreen
import one.globalconnect.paymentapp.navigation.dst_Cash
import one.globalconnect.paymentapp.navigation.dst_CardReaderTest
import one.globalconnect.paymentapp.navigation.dst_ApplicationInfo
import one.globalconnect.paymentapp.navigation.dst_EchoTest
import one.globalconnect.paymentapp.navigation.dst_TerminalCounters
import one.globalconnect.paymentapp.navigation.dst_ExtrasSale
import one.globalconnect.paymentapp.navigation.dst_LoyaltySale
import one.globalconnect.paymentapp.navigation.dst_Payment
import one.globalconnect.paymentapp.navigation.dst_QuotaSale
import one.globalconnect.paymentapp.navigation.dst_ReportAudit
import one.globalconnect.paymentapp.navigation.dst_ReportTotals
import one.globalconnect.paymentapp.navigation.dst_ReportReprintLast
import one.globalconnect.paymentapp.navigation.dst_ReportReprintSettlement
import one.globalconnect.paymentapp.navigation.dst_TransactionFinished
import one.globalconnect.paymentapp.navigation.dst_Transactions
import one.globalconnect.paymentapp.navigation.dst_AppConfig
import one.globalconnect.paymentapp.navigation.UICNavigationBar
import one.globalconnect.paymentapp.navigation.UICDestination
import one.globalconnect.paymentapp.navigation.navBarScreens
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.profile.settingNavGraph
import one.globalconnect.paymentapp.signature.SignatureScreen
import one.globalconnect.paymentapp.transaction.AmountPromptConfig
import one.globalconnect.paymentapp.transaction.ONSCREEN
import one.globalconnect.paymentapp.transaction.HostProcessingStateMachine
import one.globalconnect.paymentapp.transaction.ProcessingStatusStrings
import one.globalconnect.paymentapp.transaction.SaleScreen
import one.globalconnect.paymentapp.transaction.TipScreen
import one.globalconnect.paymentapp.transaction.BrandingAnimationScreen
import one.globalconnect.paymentapp.transaction.TransactionFinishedScreen
import one.globalconnect.paymentapp.profile.PREF_CAPTURE_SIGNATURE
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.transaction.toTransactionString
import one.globalconnect.paymentapp.ui.theme.GlobalConnectPaymentTheme
import one.globalconnect.paymentapp.settlement.SettlementCoordinator
import one.globalconnect.paymentapp.settlement.SettlementProcessingDialog
import one.globalconnect.paymentapp.settlement.SettlementResult
import one.globalconnect.paymentapp.transaction.SettlementProcessingState
import one.globalconnect.paymentapp.transaction.SettlementResultsUiState
import one.globalconnect.paymentapp.uicpos.pos.host.formatHostErrorMessage
import one.globalconnect.paymentapp.uicpos.pos.model.SignatureMode
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import one.globalconnect.paymentapp.uicpos.pos.model.TransactionMode
import one.globalconnect.paymentapp.utils.HardwareKeyManager
import one.globalconnect.paymentapp.utils.LanguageViewModel
import one.globalconnect.paymentapp.utils.LocalLanguageViewModel
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager
import one.globalconnect.paymentapp.utils.getSavedLanguage
import one.globalconnect.paymentapp.utils.setLocale
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.Locale
import kotlin.text.toBigDecimalOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.launch

/**
 * MainActivity is the entry point of the application.
 * It sets up the app theme, locale, and system UI, and displays the main composable content.
 */
class MainActivity : ComponentActivity() {

    /**
     * Drives the waiting screen state machine (Requesting → TimedOut / Failed).
     * Lazily created only when the terminal has no parameters; never created on
     * normal launches where parameters are already stored.
     */
    private val tmsParamsViewModel: TmsParamsViewModel by viewModels()

    /**
     * Dynamic receiver for TMS parameter failure events.
     *
     * Success is handled reactively: [GlobalConnectPaymentApplication.paramsReadyFlow] emits `true`
     * when [GlobalConnectPaymentApplication.applyTmsUpdate] succeeds, and Compose recomposes in place
     * without requiring an Activity recreate().
     *
     * ACTION_PARAMS_DOWNLOAD_FAILED — forwards the error to the ViewModel so the
     *                                 waiting screen can display it and offer a retry.
     */
    private val paramsAppliedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PARAMS_DOWNLOAD_FAILED) {
                val err = intent.getStringExtra("error_message") ?: "Unknown error"
                tmsParamsViewModel.reportFailure(err)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val sharedPreferences =
            newBase.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        val savedLanguage = getSavedLanguage(sharedPreferences)
        val locale = Locale.forLanguageTag(savedLanguage.ifBlank { "es" })
        val localizedContext = newBase.setLocale(locale)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen must be called before super.onCreate so the OS can hand off
        // the splash window. setKeepOnScreenCondition holds the branded splash until the
        // background init coroutine in GlobalConnectPaymentApplication completes, replacing the white screen.
        val splashScreen = installSplashScreen()

        Log.d("MainActivity", "Creating")
        // Enable edge-to-edge display with status bar matching the app background
        val statusBarColor = 0xFFFDFCFF.toInt() // color_White40 – matches app background
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                statusBarColor, statusBarColor
            ),
            navigationBarStyle = SystemBarStyle.light(
                Color.BLACK, Color.TRANSPARENT
            )
        )

        super.onCreate(savedInstanceState)

        // Keep OS splash until background init completes (~1s), then video overlay takes over.
        splashScreen.setKeepOnScreenCondition {
            !GlobalConnectPaymentApplication.instance.appInitialized.value
        }

        // Register dynamic receiver for TMS params failure events.
        // Success is handled via GlobalConnectPaymentApplication.paramsReadyFlow (no broadcast needed).
        val paramsFilter = IntentFilter(ACTION_PARAMS_DOWNLOAD_FAILED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(paramsAppliedReceiver, paramsFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(paramsAppliedReceiver, paramsFilter)
        }

        // Set the content view using Jetpack Compose
        setContent {
            val languageViewModel: LanguageViewModel = viewModel()
            GlobalConnectPaymentTheme {
                val appInitialized by GlobalConnectPaymentApplication.instance.appInitialized.collectAsState()
                val paramsAvailable by GlobalConnectPaymentApplication.instance.paramsReadyFlow.collectAsState()

                when {
                    !appInitialized -> AppInitializingScreen()
                    !paramsAvailable -> {
                        // Heavy init done but no TMS params yet — show the request status screen.
                        val requestState by tmsParamsViewModel.state.collectAsState()
                        TmsParamsWaitingScreen(
                            state   = requestState,
                            onRetry = { tmsParamsViewModel.retry() }
                        )
                    }
                    else -> {
                        var settlementState by remember {
                            mutableStateOf<SettlementProcessingState?>(null)
                        }

                        CompositionLocalProvider(
                            LocalLanguageViewModel provides languageViewModel
                        )
                        {
                            val appContent: @Composable () -> Unit = {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background
                                ) {
                                    UICApp(
                                        settlementProcessingState = settlementState,
                                        onSettlementProcessingStateChanged = {
                                            settlementState = it
                                        },
                                    )
                                }
                            }

                            appContent()
                        }

                        settlementState?.let { state ->
                            SettlementProcessingDialog(
                                state = state,
                                onDismiss = { settlementState = null },
                            )
                        }

                        // Pending param-update reminder — shown when TMS pushed new params while
                        // there were unsettled transactions. Re-shown every hour while still pending.
                        val paramUpdatePending by PendingUpdateManager.paramUpdatePending.collectAsState()
                        if (paramUpdatePending) {
                            var showParamReminder by remember { mutableStateOf(true) }
                            var lastReminderMs by remember { mutableLongStateOf(0L) }

                            LaunchedEffect(paramUpdatePending) {
                                while (paramUpdatePending) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastReminderMs >= 60 * 60 * 1000L &&
                                        !PendingUpdateManager.isOperationInProgress
                                    ) {
                                        showParamReminder = true
                                        lastReminderMs = now
                                    }
                                    kotlinx.coroutines.delay(60_000L)
                                }
                            }
                            if (showParamReminder && !PendingUpdateManager.isOperationInProgress) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showParamReminder = false },
                                    title = { androidx.compose.material3.Text(getString(R.string.pending_param_update_title)) },
                                    text = { androidx.compose.material3.Text(getString(R.string.pending_param_update_msg)) },
                                    confirmButton = {
                                        androidx.compose.material3.TextButton(onClick = {
                                            showParamReminder = false
                                        }) {
                                            androidx.compose.material3.Text(getString(R.string.ok))
                                        }
                                    },
                                    shape = androidx.compose.ui.graphics.RectangleShape
                                )
                            }
                        }
                    }
                } // when
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(paramsAppliedReceiver)
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideNavigationBar()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (HardwareKeyManager.handleKeyEvent(event)) {
            return true
        }

        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BREAK)
        ) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun hideNavigationBar() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }
}

/**
 * Full-screen status display shown when the terminal has no TMS parameters.
 *
 * Shown on first install, or when stored PAYMENT_APP params cannot be parsed,
 * while xTMSAgent downloads the configuration from the TMS server.
 *
 * State machine (driven by [TmsParamsViewModel]):
 *   [ParamsRequestState.Requesting]  — spinner + "Contacting server…"
 *   [ParamsRequestState.TimedOut]    — timeout message + retry button
 *   [ParamsRequestState.Failed]      — failure reason  + retry button
 *
 * @param state   Current download state from [TmsParamsViewModel.state].
 * @param onRetry Called when the operator taps the retry button.
 */
@Composable
private fun AppInitializingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun TmsParamsWaitingScreen(
    state: ParamsRequestState,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.tms_no_parameters),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.tms_no_parameters_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // ── Status card ───────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (state) {
                        is ParamsRequestState.Requesting -> MaterialTheme.colorScheme.secondaryContainer
                        else                             -> MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (state) {
                        is ParamsRequestState.Requesting -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = stringResource(R.string.setting_initialize_requesting),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        is ParamsRequestState.TimedOut -> {
                            Text(
                                text = stringResource(R.string.setting_initialize_timeout_title),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.setting_initialize_timeout_body),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        is ParamsRequestState.Failed -> {
                            Text(
                                text = stringResource(R.string.setting_initialize_error_title),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = state.reason,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // ── Retry button (not shown while a request is in flight) ─────────
            if (state !is ParamsRequestState.Requesting) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.tms_request_again))
                }
            }
        }
    }
}

private val VARIANT_LOGO_RESOURCE_NAMES = listOf("logo_color", "logo_white")

@Composable
private fun rememberVariantDrawablePainter(
    resourceNames: List<String>,
    @DrawableRes fallbackResId: Int,
): Painter {
    val context = LocalContext.current
    val resources = context.resources
    val packageName = context.packageName

    val resolvedResId = remember(resourceNames, fallbackResId, packageName) {
        resourceNames.firstNotNullOfOrNull { name ->
            resources.getIdentifier(name, "drawable", packageName)
                .takeIf { it != 0 }
        } ?: fallbackResId
    }

    val vectorPainter = runCatching {
        val imageVector = ImageVector.vectorResource(id = resolvedResId)
        rememberVectorPainter(image = imageVector)
    }.getOrNull()

    return vectorPainter ?: painterResource(id = resolvedResId)
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "Main Activity – Sale Flow",
)
@Composable
private fun MainActivitySaleFlowPreview() {
    val languageViewModel = remember { LanguageViewModel() }

    CompositionLocalProvider(LocalLanguageViewModel provides languageViewModel) {
        GlobalConnectPaymentTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                var previewCurrentTab by remember { mutableStateOf<UICDestination>(dst_Sale) }
                val logoResourceNames = remember { VARIANT_LOGO_RESOURCE_NAMES }

                Scaffold(
                    topBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(top = 12.dp, bottom = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(
                                painter = rememberVariantDrawablePainter(
                                    resourceNames = logoResourceNames,
                                    fallbackResId = R.drawable.logo_color,
                                ),
                                contentDescription = "Logo header",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    },
                    bottomBar = {
                        UICNavigationBar(
                            allScreens = navBarScreens,
                            onTabSelected = { previewCurrentTab = it },
                            currentTab = previewCurrentTab,
                            visible = true,
                        )
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                    ) {
                        SaleScreen(
                            onChargeClick = { _, _, _, _, _, _ -> },
                            transactionType = TransactionType.SALE,
                            promptConfig = AmountPromptConfig(
                                askAmount = true,
                                askTax1 = true,
                                askTax2 = false,
                                askTip = true,
                            ),
                            initialBaseAmount = "125.00",
                        )
                    }
                }
            }
        }
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "N82 – Sale Flow",
    device = "spec:width=480px,height=852px,dpi=244",
)
@Composable
private fun N82SaleFlowPreview() {
    MainActivityDevicePreview()
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "N96 – Sale Flow",
    device = "spec:width=720px,height=1600px,dpi=320",
)
@Composable
private fun N96SaleFlowPreview() {
    MainActivityDevicePreview()
}

// ── Device-specific previews – New Transaction Menu ──

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "N82 – New Transaction",
    device = "spec:width=480px,height=854px,dpi=244",
)
@Composable
private fun N82NewTransactionPreview() {
    MainActivityNewTransactionPreview()
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "N96 – New Transaction",
    device = "spec:width=720px,height=1600px,dpi=320",
)
@Composable
private fun N96NewTransactionPreview() {
    MainActivityNewTransactionPreview()
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "N62 – Sale Flow",
    device = "spec:width=480px,height=480px,dpi=160",
)
@Composable
private fun N62SaleFlowPreview() {
    MainActivityDevicePreview()
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "N62 – New Transaction",
    device = "spec:width=480px,height=480px,dpi=160",
)
@Composable
private fun N62NewTransactionPreview() {
    MainActivityNewTransactionPreview()
}

@Composable
private fun MainActivityNewTransactionPreview() {
    val languageViewModel = remember { LanguageViewModel() }

    CompositionLocalProvider(LocalLanguageViewModel provides languageViewModel) {
        GlobalConnectPaymentTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                var previewCurrentTab by remember { mutableStateOf<UICDestination>(dst_NewTransaction) }
                val logoResourceNames = remember { VARIANT_LOGO_RESOURCE_NAMES }
                val swDp = LocalConfiguration.current.smallestScreenWidthDp
                val isCompactScreen = swDp < 360
                val isN62Screen = swDp >= 480

                Scaffold(
                    topBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(
                                    top = if (isCompactScreen) 6.dp else 12.dp,
                                    bottom = if (isCompactScreen) 2.dp else 5.dp
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(
                                painter = rememberVariantDrawablePainter(
                                    resourceNames = logoResourceNames,
                                    fallbackResId = R.drawable.logo_color,
                                ),
                                contentDescription = "Logo header",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(when { isN62Screen -> 56.dp; isCompactScreen -> 36.dp; else -> 50.dp }),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    },
                    bottomBar = {
                        UICNavigationBar(
                            allScreens = navBarScreens,
                            onTabSelected = { previewCurrentTab = it },
                            currentTab = previewCurrentTab,
                            visible = true,
                        )
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                    ) {
                        NewTransactionMenuScreen(
                            onDestinationSelected = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainActivityDevicePreview() {
    val languageViewModel = remember { LanguageViewModel() }

    CompositionLocalProvider(LocalLanguageViewModel provides languageViewModel) {
        GlobalConnectPaymentTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                var previewCurrentTab by remember { mutableStateOf<UICDestination>(dst_Sale) }
                val logoResourceNames = remember { VARIANT_LOGO_RESOURCE_NAMES }
                val swDp = LocalConfiguration.current.smallestScreenWidthDp
                val isCompactScreen = swDp < 360
                val isN62Screen = swDp >= 480

                Scaffold(
                    topBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(
                                    top = if (isCompactScreen) 6.dp else 12.dp,
                                    bottom = if (isCompactScreen) 2.dp else 5.dp
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(
                                painter = rememberVariantDrawablePainter(
                                    resourceNames = logoResourceNames,
                                    fallbackResId = R.drawable.logo_color,
                                ),
                                contentDescription = "Logo header",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(when { isN62Screen -> 56.dp; isCompactScreen -> 36.dp; else -> 50.dp }),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    },
                    bottomBar = {
                        UICNavigationBar(
                            allScreens = navBarScreens,
                            onTabSelected = { previewCurrentTab = it },
                            currentTab = previewCurrentTab,
                            visible = true,
                        )
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                    ) {
                        SaleScreen(
                            onChargeClick = { _, _, _, _, _, _ -> },
                            transactionType = TransactionType.SALE,
                            promptConfig = AmountPromptConfig(
                                askAmount = true,
                                askTax1 = true,
                                askTax2 = false,
                                askTip = true,
                            ),
                            initialBaseAmount = "125.00",
                        )
                    }
                }
            }
        }
    }
}

/**
 * UICApp is the main composable function that sets up navigation and UI components.
 * The navigation drawer has been removed in this implementation.
 */
@Composable
fun UICApp(
    settlementProcessingState: SettlementProcessingState?,
    onSettlementProcessingStateChanged: (SettlementProcessingState?) -> Unit,
) {
    // Get the current context
    val context = LocalContext.current
    val TAG = "MainActivity"

    // Set up navigation controller and observe back stack changes
    val navController = rememberNavController()
    val navigationManager = remember(navController) {
        NavigationManager(navController)
    }
    val coroutineScope = rememberCoroutineScope()
    val settlementCoordinator = remember { SettlementCoordinator() }
    val paymentPrinter: PaymentPrinter = remember { NexGoPaymentPrinter }
    val tmsDatabase = remember { GlobalConnectPaymentApplication.instance.tmsDatabase }
    var settlementProcessingMachine by remember { mutableStateOf<HostProcessingStateMachine?>(null) }
    var localSettlementState by remember { mutableStateOf(settlementProcessingState) }
    LaunchedEffect(settlementProcessingState) {
        localSettlementState = settlementProcessingState
    }
    val selectedTab = navigationManager.currentTab
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    val isBottomBarVisible = navigationManager.shouldShowBottomBar(navBackStackEntry?.destination)

    // Removed the ModalNavigationDrawer wrapper.
    // Using Scaffold directly for the app layout.
    Scaffold(
        topBar = {
            // Top image banner with consistent padding so the logo is never cropped
            val swDp = LocalConfiguration.current.smallestScreenWidthDp
            val isCompactScreen = swDp < 360
            val isN62Screen = swDp >= 480
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(
                        top = if (isCompactScreen) 6.dp else 12.dp,
                        bottom = if (isCompactScreen) 2.dp else 5.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val logoResourceNames = remember { VARIANT_LOGO_RESOURCE_NAMES }
                Image(
                    painter = rememberVariantDrawablePainter(
                        resourceNames = logoResourceNames,
                        fallbackResId = R.drawable.logo_color
                    ),
                    contentDescription = "Logo header",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(when { isN62Screen -> 56.dp; isCompactScreen -> 36.dp; else -> 50.dp }),
                    contentScale = ContentScale.Fit,
                )
            }
        },
        bottomBar = {
            // Bottom navigation bar
            UICNavigationBar(
                allScreens = navBarScreens,
                onTabSelected = navigationManager::onDestinationSelected,
                currentTab = selectedTab,
                visible = isBottomBarVisible,
            )
        }
    ) { innerPadding ->
        // Navigation host handling all composable routes
        NavHost(
            navController = navController,
            startDestination = dst_Sale.route,
            modifier = Modifier
                .padding(innerPadding)
                .then(
                    if (isBottomBarVisible) {
                        Modifier
                    } else {
                        Modifier.padding(bottom = 17.dp)
                    }
                )
        ) {
                composable(route = dst_Sale.route) {
                    dst_Sale.screen { transactionType, baseAmount, tax1Amount, tax2Amount, tipAmount, _ ->
                        if (SysParam.getInstance().transactionMode == TransactionMode.Cafe) {
                            navController.navigate("TipScreen?$TRANSACTION_TYPE_KEY=$transactionType&" +
                                    "$AMOUNT_KEY=$baseAmount")
                        } else {
                            navController.navigate("CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=$tax1Amount&$TAX2_KEY=$tax2Amount&$TIP_KEY=$tipAmount&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY=")
                        }
                    }
                }
                composable(route = dst_LoyaltySale.route) {
                    dst_LoyaltySale.screen { transactionType, baseAmount, tax1Amount, tax2Amount, tipAmount, _ ->
                        navController.navigate("CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=$tax1Amount&$TAX2_KEY=$tax2Amount&$TIP_KEY=$tipAmount&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY=")
                    }
                }
                composable(route = dst_QuotaSale.route) {
                    dst_QuotaSale.screen { transactionType, baseAmount, tax1Amount, tax2Amount, tipAmount, _ ->
                        navController.navigate("CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=$tax1Amount&$TAX2_KEY=$tax2Amount&$TIP_KEY=$tipAmount&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY=")
                    }
                }
                composable(route = dst_ExtrasSale.route) {
                    dst_ExtrasSale.screen { transactionType, baseAmount, tax1Amount, tax2Amount, tipAmount, _ ->
                        navController.navigate("CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=$tax1Amount&$TAX2_KEY=$tax2Amount&$TIP_KEY=$tipAmount&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY=")
                    }
                }
                composable(route = dst_Payment.route) {
                    dst_Payment.screen { transactionType, baseAmount, _, _, _, _ ->
                        navController.navigate(
                            "CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=0.00&$TAX2_KEY=0.00&$TIP_KEY=0.00&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY="
                        )
                    }
                }
                composable(route = dst_Cash.route) {
                    dst_Cash.screen { transactionType, baseAmount, _, _, _, _ ->
                        navController.navigate("CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=0.00&$TAX2_KEY=0.00&$TIP_KEY=0.00&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY=")
                    }
                }
                composable(route = dst_NewTransaction.route) {
                    dst_NewTransaction.screen(navigationManager::onDestinationSelected)
                }
                composable(route = dst_CheckIn.route) {
                    dst_CheckIn.screen { transactionType, baseAmount, tax1Amount, tax2Amount, tipAmount, folio ->
                        navController.navigate(
                            "CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=$tax1Amount&$TAX2_KEY=$tax2Amount&$TIP_KEY=$tipAmount&$FOLIO_KEY=$folio&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY="
                        )
                    }
                }
                composable(route = dst_CheckOut.route) {
                    dst_CheckOut.screen { transactionType, baseAmount, tax1Amount, tax2Amount, tipAmount, folio, checkInId, originalTransactionId ->
                        navController.navigate(
                            "CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=$tax1Amount&$TAX2_KEY=$tax2Amount&$TIP_KEY=$tipAmount&$FOLIO_KEY=$folio&$CHECK_IN_ID_KEY=$checkInId&$ORIGINAL_TRANSACTION_ID_KEY=$originalTransactionId"
                        )
                    }
                }
                composable(route = dst_CardReaderTest.route) {
                    dst_CardReaderTest.screen()
                }
                composable(route = dst_EchoTest.route) {
                    dst_EchoTest.screen()
                }
                composable(route = dst_TerminalCounters.route) {
                    dst_TerminalCounters.screen {
                        navController.popBackStack()
                    }
                }
                composable(route = dst_ApplicationInfo.route) {
                    dst_ApplicationInfo.screen {
                        navController.popBackStack()
                    }
                }
                composable(route = dst_MoreMenu.route) {
                    dst_MoreMenu.screen(
                        navigationManager::onDestinationSelected,
                        { shortcut ->
                            Log.i(TAG, "Navigating to report shortcut ${shortcut.route}")
                            when (shortcut) {
                                ReportShortcut.Totals -> navigationManager.onDestinationSelected(dst_ReportTotals)
                                ReportShortcut.Audit -> navigationManager.onDestinationSelected(dst_ReportAudit)
                                ReportShortcut.ReprintLast -> navigationManager.onDestinationSelected(
                                    dst_ReportReprintLast
                                )
                                ReportShortcut.ReprintSettlement -> navigationManager.onDestinationSelected(
                                    dst_ReportReprintSettlement
                                )
                            }
                        }
                    )
                }
                composable(route = dst_Transactions.route) {
                    dst_Transactions.screen(
                        { transactionId: String ->
                            navController.navigate("Edit/$transactionId")
                        },
                        { navController.navigate(dst_QuickTip.route) }
                    )
                }
                composable(route = dst_QuickTip.route) {
                    dst_QuickTip.screen {
                        navController.popBackStack()
                    }
                }
                composable(route = dst_Reports.route) {
                    dst_Reports.screen { navController.navigate(dst_Transactions.route) }
                }
                composable(route = dst_CheckInReport.route) {
                    dst_CheckInReport.screen(
                        { navController.popBackStack() },
                        { navigationManager.onDestinationSelected(dst_CheckOut) }
                    )
                }
                composable(route = dst_ReportTotals.route) {
                    dst_ReportTotals.screen {
                        navController.popBackStack()
                    }
                }
                composable(route = dst_ReportAudit.route) {
                    dst_ReportAudit.screen {
                        navController.popBackStack()
                    }
                }
                composable(route = dst_ReportReprintLast.route) {
                    dst_ReportReprintLast.screen {
                        navController.popBackStack()
                    }
                }
                composable(route = dst_ReportReprintSettlement.route) {
                    dst_ReportReprintSettlement.screen {
                        navController.popBackStack()
                    }
                }
                composable(route = dst_Batch.route) {
                    dst_Batch.screen(
                        { transactionId: String ->
                            navController.navigate("Edit/$transactionId")
                        },
                        { navController.popBackStack() }
                    )
                }
                // Include settings navigation graph
                settingNavGraph(navController)
                composable(
                    route = dst_PasswordScreen.route,
                    arguments = listOf(navArgument(DESTINATION_KEY) {
                        type = NavType.StringType
                    })
                ) {
                    when (val destination = it.arguments?.getString(DESTINATION_KEY) ?: "") {
                        dst_Reports.route -> dst_PasswordScreen.screen {
                            Log.i(TAG, "Navigating to ${dst_Reports.route} after password validation")
                            navController.navigate(dst_Reports.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        dst_ReportTotals.route -> dst_PasswordScreen.screen {
                            navController.navigate(dst_ReportTotals.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        dst_ReportAudit.route -> dst_PasswordScreen.screen {
                            navController.navigate(dst_ReportAudit.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        dst_ReportReprintLast.route -> dst_PasswordScreen.screen {
                            navController.navigate(dst_ReportReprintLast.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        dst_ReportReprintSettlement.route -> dst_PasswordScreen.screen {
                            navController.navigate(dst_ReportReprintSettlement.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        dst_EndOfDay.route -> dst_PasswordScreen.screen {
                            navController.navigate(dst_EndOfDay.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        dst_SystemSettings.route -> dst_PasswordScreen.screen {
                            navController.navigate(dst_SystemSettings.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        dst_Transactions.route -> dst_PasswordScreen.screen {
                            navController.navigate(dst_Transactions.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        dst_Refund.route -> dst_PasswordScreen.screen {
                            navController.navigate(dst_Refund.route) {
                                popUpTo(dst_PasswordScreen.route) { inclusive = true }
                            }
                        }
                        else -> {
                            Log.d("PasswordNav", "Unable to navigate to destination of name: $destination")
                        }
                    }
                }
                composable(route = dst_EndOfDay.route) {
                    dst_EndOfDay.screen { request ->
                        coroutineScope.launch {
                            try {
                                val results = settlementCoordinator.execute(
                                    request,
                                    onTargetStart = { target ->
                                        val displayName = target.option.name.ifBlank { target.option.id }
                                        coroutineScope.launch {
                                            val strings = ProcessingStatusStrings(
                                                connecting = context.getString(R.string.processing_status_connecting),
                                                sending = context.getString(R.string.processing_status_sending),
                                                waitingForResponse = context.getString(R.string.processing_status_waiting),
                                                processingResponse = context.getString(R.string.processing_status_processing_response),
                                                result = context.getString(R.string.processing_status_result),
                                                pendingResult = context.getString(R.string.processing_status_result_pending),
                                            )
                                            val machine = HostProcessingStateMachine(strings)
                                            settlementProcessingMachine = machine
                                            val state = SettlementProcessingState(
                                                acquirerName = displayName,
                                                processingStatus = machine.restart(),
                                            )
                                            localSettlementState = state
                                            onSettlementProcessingStateChanged(state)
                                        }
                                    },
                                    onHostEvent = { event ->
                                        coroutineScope.launch {
                                            val machine = settlementProcessingMachine ?: return@launch
                                            val current = localSettlementState ?: run {
                                                Log.w(TAG, "Host event $event ignored because settlement state is null")
                                                return@launch
                                            }
                                            val updatedState = current.copy(
                                                processingStatus = machine.onEvent(event),
                                            )
                                            localSettlementState = updatedState
                                            onSettlementProcessingStateChanged(updatedState)
                                        }
                                    },
                                    onTargetResult = { message, success ->
                                        coroutineScope.launch {
                                            val machine = settlementProcessingMachine ?: return@launch
                                            val updated = if (success) {
                                                machine.onSuccess(message)
                                            } else {
                                                machine.onFailure(message)
                                            }
                                            val current = localSettlementState ?: run {
                                                Log.w(
                                                    TAG,
                                                    "Host result ignored because settlement state is null (success=$success)"
                                                )
                                                return@launch
                                            }
                                            val updatedState = current.copy(processingStatus = updated)
                                            localSettlementState = updatedState
                                            onSettlementProcessingStateChanged(updatedState)
                                        }
                                    },
                                    onTargetError = { message ->
                                        coroutineScope.launch {
                                            val machine = settlementProcessingMachine ?: return@launch
                                            val current = localSettlementState ?: run {
                                                Log.w(TAG, "Host error ignored because settlement state is null: $message")
                                                return@launch
                                            }
                                            val updatedState = current.copy(
                                                processingStatus = machine.onFailure(message),
                                                results = SettlementResultsUiState.Message(message),
                                            )
                                            localSettlementState = updatedState
                                            onSettlementProcessingStateChanged(updatedState)
                                        }
                                    },
                                )
                                results.filterIsInstance<SettlementResult.Success>()
                                    .mapNotNull(SettlementResult.Success::snapshot)
                                    .forEach { snapshot ->
                                        paymentPrinter.printSettlementReceipt(
                                            context = context,
                                            snapshot = snapshot,
                                            tmsDatabase = tmsDatabase,
                                        )
                                    }

                                // After settlement, apply any deferred updates that were
                                // waiting for unsettled transactions to clear.
                                if (results.any { it is SettlementResult.Success }) {
                                    if (PendingUpdateManager.hasPendingParamUpdate(context)) {
                                        PendingUpdateManager.applyPendingParamUpdate(context)
                                    }
                                    if (PendingUpdateManager.hasPendingAppUpdate(context)) {
                                        PendingUpdateManager.notifyXtmsAgentCanProceed(context)
                                        PendingUpdateManager.clearPendingAppUpdate(context)
                                    }
                                }

                                val summaryState = if (results.isEmpty()) {
                                    SettlementResultsUiState.Message(
                                        context.getString(R.string.settlement_no_acquirers)
                                    )
                                } else {
                                    SettlementResultsUiState.Summary(results)
                                }
                                localSettlementState?.let { current ->
                                    val updatedState = current.copy(results = summaryState)
                                    localSettlementState = updatedState
                                    onSettlementProcessingStateChanged(updatedState)
                                }
                            } catch (error: Exception) {
                                val failureDetail = formatHostErrorMessage(error)
                                localSettlementState?.let { current ->
                                    val updatedStatus = settlementProcessingMachine?.onFailure(failureDetail)
                                        ?: current.processingStatus
                                    val updatedState = current.copy(
                                        processingStatus = updatedStatus,
                                        results = SettlementResultsUiState.Message(
                                            context.getString(
                                                R.string.settlement_unexpected_error,
                                                error.localizedMessage ?: error::class.java.simpleName
                                            )
                                        ),
                                    )
                                    localSettlementState = updatedState
                                    onSettlementProcessingStateChanged(updatedState)
                                }
                                Log.e(TAG, "Settlement execution failed", error)
                            } finally {
                                settlementProcessingMachine = null
                            }
                        }
                    }
                }
                composable(route = dst_Refund.route) {
                    dst_Refund.screen { transactionType, baseAmount, tax1Amount, tax2Amount, tipAmount, _ ->
                        navController.navigate("CardTransaction/$transactionType/$baseAmount/?$TAX1_KEY=$tax1Amount&$TAX2_KEY=$tax2Amount&$TIP_KEY=$tipAmount&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY=")
                    }
                }
                composable(
                    route = dst_CardTransaction.route,
                    arguments = listOf(
                        navArgument(AMOUNT_KEY) { type = NavType.StringType },
                        navArgument(TRANSACTION_TYPE_KEY) { type = NavType.StringType },
                        navArgument(TAX1_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(TAX2_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(TIP_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(FOLIO_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(CHECK_IN_ID_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(ORIGINAL_TRANSACTION_ID_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    )
                ) {
                    dst_CardTransaction.screen(
                        { transactionId, isRefund ->
                            val postPaymentRoute =
                                if (isRefund) {
                                    "TransactionFinished/$transactionId"
                                } else if (SysParam.getInstance().transactionMode == TransactionMode.Restaurant && SysParam.getInstance().TipMethod == ONSCREEN) {
                                    "TipScreen?$TRANSACTION_ID_KEY=$transactionId"
                                } else {
                                    "BrandingAnimation/$transactionId"
                                }
                            navController.navigate(postPaymentRoute) {
                                if (postPaymentRoute.contains("TransactionFinished")) {
                                    popUpTo(dst_Sale.route) { inclusive = false }
                                } else {
                                    popUpTo(dst_CardTransaction.route) { inclusive = true }
                                }
                            }
                        }
                    ) {
                        navController.popBackStack()
                    }
                }
                composable(
                    route = dst_TransactionFinished.route,
                    arguments = listOf(navArgument(TRANSACTION_ID_KEY) { type = NavType.StringType })
                ) {
                    TransactionFinishedScreen({
                        SoundManager.play(SoundEffect.KEY_TICK) // Play click sound
                        navigationManager.onDestinationSelected(dst_Sale)
                    })
                }
                composable(
                    route = dst_Signature.route, arguments = listOf(
                        navArgument(TRANSACTION_ID_KEY) { type = NavType.StringType },
                    )
                ) {
                    SignatureScreen(onConfirmPressed = { transactionId ->
                        navController.navigate("TransactionFinished/$transactionId") {
                            popUpTo(dst_Sale.route) { inclusive = false }
                        }
                    })
                }
                composable(
                    route = dst_TipScreen.route,
                    arguments = listOf(
                        navArgument(TRANSACTION_ID_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(TRANSACTION_TYPE_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(AMOUNT_KEY) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) {
                    TipScreen(
                        onConfirmPressed = { transactionId ->
                            navController.navigate("BrandingAnimation/$transactionId") {
                                popUpTo(dst_Sale.route) { inclusive = false }
                            }
                        },
                        onStartPaymentPressed = { amount, tip, transactionType ->
                            if (transactionType == TransactionType.SALE.toTransactionString()) {
                                val tipDecimal = tip.toBigDecimalOrNull() ?: BigDecimal.ZERO
                                val baseDecimal = amount.toBigDecimalOrNull()?.minus(tipDecimal)
                                val normalizedBase = baseDecimal?.let { value ->
                                    if (value < BigDecimal.ZERO) BigDecimal.ZERO else value
                                }?.setScale(2, RoundingMode.HALF_UP)
                                val base = normalizedBase?.toPlainString() ?: amount
                                navController.navigate("CardTransaction/$transactionType/$base/?$TIP_KEY=$tip&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY=")
                            } else {
                                navController.navigate("CardTransaction/$transactionType/$amount/?$TAX1_KEY=0.00&$TAX2_KEY=0.00&$TIP_KEY=$tip&$FOLIO_KEY=&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY=")
                            }
                        },
                        onBackButtonPressed = {
                            SoundManager.play(SoundEffect.KEY_DELETE) // Play delete sound
                            navController.popBackStack()
                        }
                    )
                }
                composable(
                    route = dst_EditTransaction.route, arguments = listOf(navArgument(TRANSACTION_ID_KEY) { type = NavType.StringType })
                ) {
                    dst_EditTransaction.screen { navController.popBackStack() }
                }
                composable(route = dst_AppConfig.route) {
                    dst_AppConfig.screen { navController.popBackStack() }
                }
                composable(
                    route = "BrandingAnimation/{$TRANSACTION_ID_KEY}",
                    arguments = listOf(navArgument(TRANSACTION_ID_KEY) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val transactionId = backStackEntry.arguments?.getString(TRANSACTION_ID_KEY) ?: ""
                    val ctx = LocalContext.current
                    val prefs = remember(ctx) {
                        ctx.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
                    }
                    BrandingAnimationScreen(
                        onComplete = {
                            val captureSignature = prefs.getBoolean(PREF_CAPTURE_SIGNATURE, false)
                            if (captureSignature) {
                                navController.navigate("Signature/$transactionId") {
                                    popUpTo("BrandingAnimation/$transactionId") { inclusive = true }
                                }
                            } else {
                                navController.navigate("TransactionFinished/$transactionId") {
                                    popUpTo(dst_Sale.route) { inclusive = false }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

