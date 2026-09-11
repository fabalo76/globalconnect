package one.globalconnect.paymentapp.navigation

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.remember
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.PendingParamUpdateResult
import one.globalconnect.paymentapp.PendingUpdateManager
import one.globalconnect.paymentapp.admin.AdminRequestBridge
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.profile.labelResource
import one.globalconnect.paymentapp.security.TerminalPasswordAction
import one.globalconnect.paymentapp.security.TerminalPasswordPolicy
import one.globalconnect.paymentapp.transaction.DialogNumpad
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.ui.theme.menuButtonShape
import one.globalconnect.paymentapp.ui.theme.GlobalConnectPaymentTheme
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager
import one.globalconnect.paymentapp.navigation.dst_AppConfig
import kotlinx.coroutines.launch

private const val TAG = "MoreMenu"

private data class PasswordProtectedMenuAction(
    val passwordAction: TerminalPasswordAction,
    val onAuthenticated: () -> Unit,
)

private enum class TechnicalMaintenanceAction {
    ERASE_REVERSALS,
    ERASE_BATCH,
}

private data class TechnicalMaintenanceResult(
    val title: String,
    val message: String,
)

enum class ReportShortcut(val route: String) {
    Totals("Reports/Totals"),
    Audit("Reports/Audit"),
    ReprintLast("Reports/ReprintLast"),
    ReprintSettlement("Reports/ReprintSettlement"),
}

@Preview(showBackground = true)
@Composable
private fun MoreMenuScreenPreview() {
    GlobalConnectPaymentTheme {
        MoreMenuScreen(
            onDestinationSelected = {},
            onReportShortcutSelected = {}
        )
    }
}

@Composable
fun MoreMenuScreen(
    onDestinationSelected: (UICDestination) -> Unit,
    onReportShortcutSelected: (ReportShortcut) -> Unit,
) {
    var currentMenuId by rememberSaveable { mutableStateOf("main") }
    var showInitializeDialog by rememberSaveable { mutableStateOf(false) }
    var technicalMenuUnlocked by remember { mutableStateOf(false) }
    var pendingPasswordAction by remember { mutableStateOf<PasswordProtectedMenuAction?>(null) }
    var pendingMaintenanceAction by remember { mutableStateOf<TechnicalMaintenanceAction?>(null) }
    var maintenanceResult by remember { mutableStateOf<TechnicalMaintenanceResult?>(null) }
    var maintenanceInProgress by remember { mutableStateOf(false) }
    var reversalSendInProgress by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val terminal = GlobalConnectPaymentApplication.instance.tmsDatabase.Terminal.firstOrNull()

    /** Runs a menu action immediately or requests its configured terminal password first. */
    fun runWithPassword(passwordAction: TerminalPasswordAction, action: () -> Unit) {
        if (TerminalPasswordPolicy.requiresPassword(terminal, passwordAction)) {
            pendingPasswordAction = PasswordProtectedMenuAction(passwordAction, action)
        } else {
            action()
        }
    }

    LaunchedEffect(currentMenuId, technicalMenuUnlocked) {
        if (currentMenuId == "technical" && !technicalMenuUnlocked) {
            currentMenuId = "functions"
        }
    }
    val adminRequestButtons = if (terminal?.adminMessagesEnabled == true) {
        listOfNotNull(
            if (terminal.adminMessagesRequestPaper) {
                val label = stringResource(id = R.string.admin_request_paper)
                ButtonConfig(label, Icons.Filled.Print) {
                    AdminRequestBridge.report(context, "paper", label)
                }
            } else null,
            if (terminal.adminMessagesRequestTechnicianVisit) {
                val label = stringResource(id = R.string.admin_request_technician_visit)
                ButtonConfig(label, Icons.Filled.Build) {
                    AdminRequestBridge.report(context, "technician_visit", label)
                }
            } else null,
            if (terminal.adminMessagesRequestExecutiveCall) {
                val label = stringResource(id = R.string.admin_request_executive_call)
                ButtonConfig(label, Icons.Filled.Phone) {
                    AdminRequestBridge.report(context, "executive_call", label)
                }
            } else null,
            if (terminal.adminMessagesRequestTraining) {
                val label = stringResource(id = R.string.admin_request_training)
                ButtonConfig(label, Icons.Filled.School) {
                    AdminRequestBridge.report(context, "training", label)
                }
            } else null,
        )
    } else {
        emptyList()
    }
    val menus = listOf(
        MenuConfig(
            id = "main",
            title = stringResource(id = R.string.other_functions),
            buttons = listOf(
                ButtonConfig(stringResource(id = R.string.batch), Icons.Filled.History) { onDestinationSelected(dst_Transactions) },
                ButtonConfig(stringResource(id = R.string.reports), Icons.Filled.Receipt) {  currentMenuId = "reports" },
                ButtonConfig(stringResource(id = R.string.trans_settlement), Icons.Filled.MonetizationOn) { onDestinationSelected(dst_EndOfDay) },
                ButtonConfig(stringResource(id = R.string.function_menu), Icons.Filled.AdminPanelSettings) { currentMenuId = "functions" },
                ButtonConfig(stringResource(id = R.string.config_menu), Icons.Filled.Tune) {
                    runWithPassword(TerminalPasswordAction.CONFIGURATION) {
                        currentMenuId = "config"
                    }
                },
            )
        ),
        MenuConfig(
            id = "reports",
            title = stringResource(id = R.string.reports),
            buttons = listOf(
                ButtonConfig(stringResource(id = R.string.totals_report), Icons.Filled.BarChart) { onReportShortcutSelected(ReportShortcut.Totals) },
                ButtonConfig(stringResource(id = R.string.audit_report), Icons.AutoMirrored.Filled.FactCheck) { onReportShortcutSelected(ReportShortcut.Audit) },
                ButtonConfig(stringResource(id = R.string.reprint_last), Icons.AutoMirrored.Filled.ReceiptLong) { onReportShortcutSelected(ReportShortcut.ReprintLast) },
                ButtonConfig(stringResource(id = R.string.reprint_settlement), Icons.Filled.RequestQuote) { onReportShortcutSelected(ReportShortcut.ReprintSettlement) },
                ButtonConfig(stringResource(id = R.string.hotel_check_in_report_title),
                    Icons.AutoMirrored.Filled.List
                ) { onDestinationSelected(dst_CheckInReport) },
                ButtonConfig(stringResource(id = R.string.back), Icons.AutoMirrored.Filled.ArrowBack) { currentMenuId = "main" }
            )
        ),
        MenuConfig(
            id = "functions",
            title = stringResource(id = R.string.functions),
            buttons = listOfNotNull(
                ButtonConfig(stringResource(id = R.string.tran_echotest), Icons.Filled.Sync) {
                    onDestinationSelected(dst_EchoTest)
                },
                if (adminRequestButtons.isNotEmpty()) {
                    ButtonConfig(stringResource(id = R.string.admin_requests), Icons.Filled.SupportAgent) {
                        currentMenuId = "admin_requests"
                    }
                } else null,
                ButtonConfig(stringResource(id = R.string.gprs_information), Icons.Filled.SignalCellularAlt) { /* Handle GPRS information */ },
                ButtonConfig(stringResource(id = R.string.send_reversals), Icons.AutoMirrored.Filled.Send) {
                    if (!reversalSendInProgress) {
                        if (PendingUpdateManager.isOperationInProgress) {
                            maintenanceResult = TechnicalMaintenanceResult(
                                title = context.getString(R.string.send_reversals),
                                message = context.getString(R.string.setting_initialize_err_operation_in_progress),
                            )
                        } else {
                            reversalSendInProgress = true
                            coroutineScope.launch {
                                try {
                                    val result = TerminalMaintenanceOperations.sendPendingReversals()
                                    val message = if (result.found == 0) {
                                        context.getString(R.string.send_reversals_none)
                                    } else {
                                        context.getString(
                                            R.string.send_reversals_result,
                                            result.found,
                                            result.sent,
                                            result.remaining,
                                        )
                                    }
                                    maintenanceResult = TechnicalMaintenanceResult(
                                        title = context.getString(R.string.send_reversals),
                                        message = message,
                                    )
                                } catch (error: Exception) {
                                    Log.e(TAG, "Unable to send pending reversals", error)
                                    maintenanceResult = TechnicalMaintenanceResult(
                                        title = context.getString(R.string.send_reversals),
                                        message = context.getString(R.string.send_reversals_failed),
                                    )
                                } finally {
                                    reversalSendInProgress = false
                                }
                            }
                        }
                    }
                },
                ButtonConfig(stringResource(id = R.string.application_info), Icons.Filled.Info) {
                    onDestinationSelected(dst_ApplicationInfo)
                },
                ButtonConfig(stringResource(id = R.string.print_test), Icons.Filled.Print) {
                    val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
                    paymentPrinter.printerTest(context)
                },
                ButtonConfig(stringResource(id = R.string.technical_menu), Icons.Filled.Engineering) {
                    runWithPassword(TerminalPasswordAction.BANK) {
                        technicalMenuUnlocked = true
                        currentMenuId = "technical"
                    }
                },
                ButtonConfig(stringResource(id = R.string.back), Icons.AutoMirrored.Filled.ArrowBack) { currentMenuId = "main" }
            )
        ),
        MenuConfig(
            id = "admin_requests",
            title = stringResource(id = R.string.admin_requests),
            buttons = adminRequestButtons +
                ButtonConfig(stringResource(id = R.string.back), Icons.AutoMirrored.Filled.ArrowBack) { currentMenuId = "functions" }
        ),
        MenuConfig(
            id = "technical",
            title = stringResource(id = R.string.technical_menu),
            buttons = listOf(
                ButtonConfig(stringResource(id = R.string.erase_reversals), Icons.Filled.DeleteSweep) {
                    pendingMaintenanceAction = TechnicalMaintenanceAction.ERASE_REVERSALS
                },
                ButtonConfig(stringResource(id = R.string.erase_batch), Icons.Filled.DeleteForever) {
                    runWithPassword(TerminalPasswordAction.CLEAR) {
                        pendingMaintenanceAction = TechnicalMaintenanceAction.ERASE_BATCH
                    }
                },
                ButtonConfig(stringResource(id = R.string.terminal_counters), Icons.Filled.Numbers) {
                    onDestinationSelected(dst_TerminalCounters)
                },
                ButtonConfig(stringResource(id = R.string.audio_test), Icons.Filled.Settings) {
                    onDestinationSelected(dst_AudioTest)
                },
                ButtonConfig(stringResource(id = R.string.back), Icons.AutoMirrored.Filled.ArrowBack) {
                    technicalMenuUnlocked = false
                    currentMenuId = "functions"
                },
            ),
        ),
        MenuConfig(
            id = "config",
            title = stringResource(id = R.string.config_menu),
            buttons = listOf(
                ButtonConfig(stringResource(id = R.string.setting_initialize), Icons.Filled.CloudDownload) {
                    showInitializeDialog = true
                },
                ButtonConfig(stringResource(id = R.string.print_configuration), Icons.Filled.Print) {
                    runWithPassword(TerminalPasswordAction.BANK) {
                        val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
                        val tmsDatabase = GlobalConnectPaymentApplication.instance.tmsDatabase
                        paymentPrinter.printConfigReport(context, tmsDatabase)
                    }
                },
                ButtonConfig(stringResource(id = R.string.print_pinpadkeys), Icons.Filled.VpnKey) {
                    runWithPassword(TerminalPasswordAction.BANK) {
                        val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
                        val tmsDatabase = GlobalConnectPaymentApplication.instance.tmsDatabase
                        paymentPrinter.printPinPadKeysReport(context, tmsDatabase)
                    }
                },
                ButtonConfig(stringResource(id = R.string.app_config), Icons.Filled.PhoneAndroid) {
                    runWithPassword(TerminalPasswordAction.BANK) { onDestinationSelected(dst_AppConfig) }
                },
                ButtonConfig(stringResource(id = R.string.back), Icons.AutoMirrored.Filled.ArrowBack) { currentMenuId = "main" }
            )
        )
    ).associateBy { it.id }

    val menuConfig = menus[currentMenuId] ?: menus["main"]!!

    Scaffold(
        topBar = { Box(Modifier.height(24.dp).background(color_white).fillMaxSize()) },
        bottomBar = { Box(Modifier.height(8.dp).background(color_secondaryThree).fillMaxSize()) }
    ) { contentPadding ->
        Column(
            modifier = Modifier.padding(contentPadding).background(color_secondaryThree).fillMaxHeight().fillMaxWidth()
        ) {
            Row(Modifier.padding(8.dp, 24.dp)) {
                Button(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    onClick = {
                        SoundManager.play(SoundEffect.KEY_DELETE)
                        technicalMenuUnlocked = false
                        currentMenuId = "main"
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = color_primaryBrand, contentColor = color_secondaryFive)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentMenuId != "main") {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(id = R.string.back),
                                modifier = Modifier.padding(start = 12.dp).size(36.dp)
                            )
                        }
                        Text(
                            text = menuConfig.title,
                            fontSize = menuTitleFontSize(menuConfig.title),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val availableHeight = maxHeight
                val rowsPerPage = maxOf(1, (availableHeight.value / 104f).toInt())
                val itemsPerPage = rowsPerPage * 2
                val pages = menuConfig.buttons.chunked(itemsPerPage)
                val pagerState = rememberPagerState(pageCount = { pages.size })
                val dotsHeightDp = if (pages.size > 1) 24.dp else 0.dp

                LaunchedEffect(currentMenuId) {
                    pagerState.scrollToPage(0)
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) { pageIndex ->
                        val pageRows = pages[pageIndex].chunked(2)
                        val numRows = pageRows.size
                        val availableForButtons = availableHeight - dotsHeightDp
                        val rowHeight = minOf(availableForButtons / numRows, 130.dp)

                        Column(
                            modifier = Modifier.fillMaxSize().padding(4.dp, 0.dp),
                            verticalArrangement = Arrangement.Top
                        ) {
                            pageRows.forEach { rowButtons ->
                                Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                                    rowButtons.forEach { button ->
                                        Button(
                                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp).shadow(4.dp, shape = menuButtonShape),
                                            onClick = {
                                                SoundManager.play(SoundEffect.KEY_TICK)
                                                button.action()
                                            },
                                            shape = menuButtonShape,
                                            colors = ButtonDefaults.buttonColors(containerColor = color_white, contentColor = color_secondaryFive),
                                            contentPadding = PaddingValues(
                                                start = 0.dp,
                                                end = 0.dp,
                                                top = ButtonDefaults.ContentPadding.calculateTopPadding(),
                                                bottom = ButtonDefaults.ContentPadding.calculateBottomPadding()
                                            )
                                        ) {
                                            Column(Modifier.fillMaxWidth().align(Alignment.CenterVertically)) {
                                                Icon(
                                                    imageVector = button.icon,
                                                    contentDescription = button.text,
                                                    modifier = Modifier.padding(bottom = 4.dp).align(Alignment.CenterHorizontally).size(24.dp),
                                                    tint = color_secondaryFive
                                                )
                                                Text(button.text, fontSize = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                            }
                                        }
                                    }
                                    if (rowButtons.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    if (pages.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pages.indices.forEach { index ->
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(if (pagerState.currentPage == index) 10.dp else 7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (pagerState.currentPage == index) color_primaryBrand
                                            else color_white.copy(alpha = 0.5f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInitializeDialog) {
        TmsInitializeDialog(onDismiss = { showInitializeDialog = false })
    }

    if (reversalSendInProgress) {
        MenuOperationProgressDialog(message = stringResource(R.string.send_reversals_in_progress))
    }

    pendingPasswordAction?.let { protectedAction ->
        ConfigPasswordDialog(
            action = protectedAction.passwordAction,
            onAuthenticated = {
                pendingPasswordAction = null
                protectedAction.onAuthenticated()
            },
            onDismiss = { pendingPasswordAction = null },
        )
    }

    pendingMaintenanceAction?.let { action ->
        TechnicalMaintenanceConfirmationDialog(
            action = action,
            enabled = !maintenanceInProgress,
            onConfirm = {
                pendingMaintenanceAction = null
                maintenanceInProgress = true
                coroutineScope.launch {
                    try {
                        maintenanceResult = when (action) {
                            TechnicalMaintenanceAction.ERASE_REVERSALS -> {
                                val erased = TerminalMaintenanceOperations.erasePendingReversals()
                                TechnicalMaintenanceResult(
                                    title = context.getString(R.string.erase_reversals),
                                    message = context.getString(R.string.erase_reversals_success, erased),
                                )
                            }

                            TechnicalMaintenanceAction.ERASE_BATCH -> {
                                val result = TerminalMaintenanceOperations.eraseActiveBatch(context)
                                val pendingMessages = buildList {
                                    if (result.pendingOperations.parameterResult == PendingParamUpdateResult.Applied) {
                                        add(context.getString(R.string.pending_parameter_update_applied))
                                    } else if (result.pendingOperations.parameterResult == PendingParamUpdateResult.Failed) {
                                        add(context.getString(R.string.pending_parameter_update_failed))
                                    }
                                    if (result.pendingOperations.appUpdateReleased) {
                                        add(context.getString(R.string.pending_application_update_released))
                                    }
                                }
                                val baseMessage = context.getString(
                                    R.string.erase_batch_success,
                                    result.erasedTransactions,
                                )
                                TechnicalMaintenanceResult(
                                    title = context.getString(R.string.erase_batch),
                                    message = (listOf(baseMessage) + pendingMessages).joinToString("\n\n"),
                                )
                            }
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Technical maintenance operation failed: $action", error)
                        maintenanceResult = TechnicalMaintenanceResult(
                            title = context.getString(R.string.technical_menu),
                            message = context.getString(R.string.technical_maintenance_failed),
                        )
                    } finally {
                        maintenanceInProgress = false
                    }
                }
            },
            onDismiss = { pendingMaintenanceAction = null },
        )
    }

    maintenanceResult?.let { result ->
        TechnicalMaintenanceResultDialog(
            result = result,
            onDismiss = { maintenanceResult = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuOperationProgressDialog(message: String) {
    BasicAlertDialog(onDismissRequest = {}) {
        Card(
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun menuTitleFontSize(title: String): TextUnit {
    val length = title.length
    return when {
        length >= 24 -> 24.sp
        length >= 18 -> 28.sp
        length >= 14 -> 32.sp
        else -> 36.sp
    }
}

/**
 * Confirms a destructive Technical Menu operation before any terminal data is removed.
 *
 * @param action maintenance operation awaiting confirmation.
 * @param enabled whether the confirmation controls accept input.
 * @param onConfirm invoked when the operator confirms the destructive operation.
 * @param onDismiss invoked when the operator cancels the operation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechnicalMaintenanceConfirmationDialog(
    action: TechnicalMaintenanceAction,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = when (action) {
        TechnicalMaintenanceAction.ERASE_REVERSALS -> {
            stringResource(R.string.erase_reversals_confirmation)
        }

        TechnicalMaintenanceAction.ERASE_BATCH -> {
            stringResource(R.string.erase_batch_confirmation)
        }
    }
    BasicAlertDialog(onDismissRequest = { if (enabled) onDismiss() }) {
        Card(
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.confirm_technical_maintenance),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, color_primaryBrand),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(stringResource(R.string.erase))
                    }
                }
            }
        }
    }
}

/**
 * Displays the final status of a destructive Technical Menu operation.
 *
 * @param result localized title and message to display.
 * @param onDismiss invoked when the operator acknowledges the result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechnicalMaintenanceResultDialog(
    result: TechnicalMaintenanceResult,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}

/**
 * Requests and validates the password associated with a terminal action.
 *
 * @param action terminal action whose configured password must be entered.
 * @param onAuthenticated invoked after successful password validation.
 * @param onDismiss invoked when the operator cancels password entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigPasswordDialog(
    action: TerminalPasswordAction,
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val expectedPassword = remember(action) {
        val terminal = GlobalConnectPaymentApplication.instance.tmsDatabase.Terminal.firstOrNull()
        TerminalPasswordPolicy.passwordFor(terminal, action)
    }

    fun checkAndSubmit() {
        if (input.isEmpty()) return
        if (input == expectedPassword) {
            onAuthenticated()
        } else {
            input = ""
            showError = true
        }
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.enter_action_password,
                        stringResource(action.labelResource()),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (input.isEmpty()) "" else "*".repeat(input.length - 1) + input.last(),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        text = stringResource(R.string.incorrect_password),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                DialogNumpad(
                    onSelected = { key ->
                        showError = false
                        when (key) {
                            "C" -> if (input.isNotEmpty()) input = input.dropLast(1)
                            else -> input += key
                        }
                    },
                    onEnterPressed = { checkAndSubmit() },
                    onCancelPressed = onDismiss,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, color_primaryBrand),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = color_primaryBrand,
                        ),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = ::checkAndSubmit,
                        enabled = input.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color_primaryBrand,
                            contentColor = color_secondaryFive,
                        ),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}
