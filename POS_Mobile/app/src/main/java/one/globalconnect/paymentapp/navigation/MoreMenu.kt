package one.globalconnect.paymentapp.navigation

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import one.globalconnect.paymentapp.BuildConfig
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.admin.AdminRequestBridge
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.transaction.Numpad
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.ui.theme.menuButtonShape
import one.globalconnect.paymentapp.ui.theme.GlobalConnectPaymentTheme
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager
import one.globalconnect.paymentapp.navigation.dst_AppConfig


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
    var showConfigPasswordDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val terminal = GlobalConnectPaymentApplication.instance.tmsDatabase.Terminal.firstOrNull()
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
                    if (BuildConfig.DEBUG) currentMenuId = "config" else showConfigPasswordDialog = true
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
                ButtonConfig(stringResource(id = R.string.send_reversals), Icons.AutoMirrored.Filled.Send) { /* Handle transaction reversals */ },
                ButtonConfig(stringResource(id = R.string.application_info), Icons.Filled.Info) {
                    onDestinationSelected(dst_ApplicationInfo)
                },
                ButtonConfig(stringResource(id = R.string.print_test), Icons.Filled.Print) {
                    val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
                    paymentPrinter.printerTest(context)
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
            id = "config",
            title = stringResource(id = R.string.config_menu),
            buttons = listOf(
                ButtonConfig(stringResource(id = R.string.terminal_counters), Icons.Filled.Numbers) { onDestinationSelected(dst_TerminalCounters) },
                ButtonConfig(stringResource(id = R.string.setting_initialize), Icons.Filled.CloudDownload) {
                    showInitializeDialog = true
                },
                ButtonConfig(stringResource(id = R.string.print_configuration), Icons.Filled.Print) {
                    val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
                    val tmsDatabase = GlobalConnectPaymentApplication.instance.tmsDatabase
                    paymentPrinter.printConfigReport(context, tmsDatabase)
                },
                ButtonConfig(stringResource(id = R.string.print_pinpadkeys), Icons.Filled.VpnKey) { /* Handle printing PIN pad keys */ },
                ButtonConfig(stringResource(id = R.string.app_config), Icons.Filled.PhoneAndroid) { onDestinationSelected(dst_AppConfig) },
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

    if (showConfigPasswordDialog) {
        ConfigPasswordDialog(
            onAuthenticated = {
                showConfigPasswordDialog = false
                currentMenuId = "config"
            },
            onDismiss = { showConfigPasswordDialog = false }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigPasswordDialog(
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    fun checkAndSubmit() {
        if (input.isEmpty()) return
        val sysParam = SysParam.getInstance()
        if (input == sysParam.EmployeePassword || input == sysParam.AdminPassword) {
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
                    text = stringResource(R.string.enter_password),
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
                Numpad(
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
            }
        }
    }
}
