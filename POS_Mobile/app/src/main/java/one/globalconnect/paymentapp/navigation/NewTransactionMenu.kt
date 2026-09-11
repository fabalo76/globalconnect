package one.globalconnect.paymentapp.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CreditScore
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.transaction.OfflinePinChangeContract
import one.globalconnect.paymentapp.ui.theme.GlobalConnectPaymentTheme
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.ui.theme.color_alert
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.menuButtonShape
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager

@Composable
fun NewTransactionMenuScreen(
    onDestinationSelected: (UICDestination) -> Unit,
    initialMenuId: String = "main",
    initialShowNotAllowed: Boolean = false,
) {
    val swDp = LocalConfiguration.current.smallestScreenWidthDp
    val isCompactScreen = swDp < 360   // N82/N6: ~314dp SW
    val isN62Screen = swDp >= 480      // N62: 480dp SW
    var currentMenuId by rememberSaveable(initialMenuId) { mutableStateOf(initialMenuId) }
    var showNotAllowed by rememberSaveable(initialShowNotAllowed) { mutableStateOf(initialShowNotAllowed) }
    val paymentApplication = GlobalConnectPaymentApplication.instance
    val terminal = paymentApplication.tmsDatabase.Terminal.firstOrNull()
    val acquirers = paymentApplication.tmsDatabase.Acquirer
    val loyaltyEnabled = terminal?.enableLoyalty == true && acquirers.any { it.enableLoyalty }

    fun showNotAllowedMessage() {
        SoundManager.play(SoundEffect.KEY_INVALID) // 🔊 Play Click Sound
        showNotAllowed = true
    }

    val menus = listOf(
        MenuConfig(
            id = "main",
            title = stringResource(id = R.string.transactions),
            buttons = listOfNotNull(
                ButtonConfig(stringResource(id = R.string.reprint), Icons.Filled.Print) { onDestinationSelected(dst_Transactions) },
                ButtonConfig(stringResource(id = R.string.trans_void), Icons.Filled.RemoveCircleOutline) { onDestinationSelected(dst_Transactions) },
                if (loyaltyEnabled) {
                    ButtonConfig(stringResource(id = R.string.miles_sale), Icons.Filled.CreditScore) {
                        onDestinationSelected(dst_LoyaltySale)
                    }
                } else null,
                if (loyaltyEnabled) {
                    ButtonConfig(stringResource(id = R.string.miles_balance), Icons.AutoMirrored.Filled.ShowChart) {
                        onDestinationSelected(dst_LoyaltyBalance)
                    }
                } else null,
                ButtonConfig(stringResource(id = R.string.trans_payment), Icons.Filled.Payments) {
                    onDestinationSelected(dst_Payment)
                },
                ButtonConfig(stringResource(id = R.string.intras_extras), Icons.Filled.AddCard) { currentMenuId = "intrasextras" },
                ButtonConfig(stringResource(id = R.string.hotel), Icons.AutoMirrored.Filled.ReceiptLong) { currentMenuId = "hotel" },
                ButtonConfig(stringResource(id = R.string.return_sale), Icons.Filled.Cached) { onDestinationSelected(dst_Refund) },
                if (OfflinePinChangeContract.isConfiguredForMenu(terminal, acquirers)) {
                    ButtonConfig(
                        stringResource(id = R.string.trans_offline_pin_change),
                        Icons.Filled.VpnKey,
                    ) {
                        onDestinationSelected(dst_OfflinePinChange)
                    }
                } else null,
                if (OfflinePinChangeContract.isPinUnblockConfiguredForMenu(terminal)) {
                    ButtonConfig(
                        stringResource(id = R.string.trans_pin_unblock),
                        Icons.Filled.VpnKey,
                    ) {
                        onDestinationSelected(dst_PinUnblock)
                    }
                } else null,
            )
        ),
        MenuConfig(
            id = "hotel",
            title = stringResource(id = R.string.hotel),
            buttons = listOf(
                ButtonConfig(stringResource(id = R.string.trans_checkin), Icons.AutoMirrored.Filled.Login) { onDestinationSelected(dst_CheckIn) },
                ButtonConfig(stringResource(id = R.string.trans_checkout), Icons.AutoMirrored.Filled.Logout) { onDestinationSelected(dst_CheckOut) },
                ButtonConfig(
                    stringResource(id = R.string.hotel_check_in_report_title),
                    Icons.AutoMirrored.Filled.FactCheck,
                ) { onDestinationSelected(dst_OpenCheckIns) },
                ButtonConfig(stringResource(id = R.string.trans_void_checkin), Icons.Filled.RemoveCircleOutline) { showNotAllowedMessage() },
                ButtonConfig(stringResource(id = R.string.back), Icons.AutoMirrored.Filled.ArrowBack) { currentMenuId = "main" }
            )
        ),
        MenuConfig(
            id = "intrasextras",
            title = stringResource(id = R.string.intras_extras),
            buttons = listOf(
                ButtonConfig(stringResource(id = R.string.installment_purchase), Icons.Filled.Payments) { onDestinationSelected(dst_QuotaSale) },
                ButtonConfig(stringResource(id = R.string.extra_purchase), Icons.AutoMirrored.Filled.FactCheck) { onDestinationSelected(dst_ExtrasSale) },
                ButtonConfig(stringResource(id = R.string.extra_balance), Icons.AutoMirrored.Filled.TrendingUp) { showNotAllowedMessage() },
                ButtonConfig(stringResource(id = R.string.back), Icons.AutoMirrored.Filled.ArrowBack) { currentMenuId = "main" }
            )
        )
    ).associateBy { it.id } // Convert to a map for easy lookup

    // Get the current menu from the map
    val menuConfig = menus[currentMenuId] ?: menus["main"]!!

    Scaffold(
        topBar = { Box(Modifier.height(if (isCompactScreen) 12.dp else 24.dp).background(color_white).fillMaxSize()) },
        bottomBar = { Box(Modifier.height(if (isCompactScreen) 32.dp else 64.dp).background(color_secondaryThree).fillMaxSize()) }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .background(color_secondaryThree)
                .fillMaxHeight()
                .fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = if (isCompactScreen) 12.dp else 24.dp)) {
                Button(
                    modifier = Modifier.fillMaxWidth().height(when { isN62Screen -> 64.dp; isCompactScreen -> 44.dp; else -> 56.dp }),
                    onClick = {
                        SoundManager.play(SoundEffect.KEY_DELETE) // 🔊 Play Click Sound
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
                            fontSize = when { isN62Screen -> 40.sp; isCompactScreen -> 28.sp; else -> 36.sp },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val availableHeight = maxHeight
                val desiredRowHeight = when {
                    isN62Screen -> 116.dp
                    isCompactScreen -> 76.dp
                    else -> 104.dp
                }
                val provisionalRowsPerPage = maxOf(
                    1,
                    (availableHeight.value / desiredRowHeight.value).toInt(),
                )
                val requiresPaging = menuConfig.buttons.size > provisionalRowsPerPage * 2
                val indicatorHeight = if (requiresPaging) 24.dp else 0.dp
                val rowsPerPage = provisionalRowsPerPage
                val rowHeight = minOf(
                    desiredRowHeight,
                    (availableHeight - indicatorHeight) / rowsPerPage,
                )
                val pages = menuConfig.buttons.chunked(rowsPerPage * 2)
                val pagerState = rememberPagerState(pageCount = { pages.size })

                LaunchedEffect(currentMenuId) {
                    pagerState.scrollToPage(0)
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) { pageIndex ->
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            pages[pageIndex].chunked(2).forEach { rowButtons ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(rowHeight),
                                ) {
                                    rowButtons.forEach { button ->
                                        Button(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .padding(2.dp)
                                                .shadow(4.dp, shape = menuButtonShape),
                                            onClick = {
                                                SoundManager.play(SoundEffect.KEY_TICK)
                                                button.action()
                                            },
                                            shape = menuButtonShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = color_white,
                                                contentColor = color_secondaryFive,
                                            ),
                                            contentPadding = PaddingValues(
                                                start = 0.dp,
                                                end = 0.dp,
                                                top = ButtonDefaults.ContentPadding.calculateTopPadding(),
                                                bottom = ButtonDefaults.ContentPadding.calculateBottomPadding(),
                                            ),
                                        ) {
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .align(Alignment.CenterVertically),
                                            ) {
                                                Icon(
                                                    imageVector = button.icon,
                                                    contentDescription = button.text,
                                                    modifier = Modifier
                                                        .padding(bottom = if (isCompactScreen) 2.dp else 4.dp)
                                                        .align(Alignment.CenterHorizontally)
                                                        .size(
                                                            when {
                                                                isN62Screen -> 28.dp
                                                                isCompactScreen -> 20.dp
                                                                else -> 24.dp
                                                            },
                                                        ),
                                                    tint = color_secondaryFive,
                                                )
                                                Text(
                                                    text = button.text,
                                                    fontSize = when {
                                                        isN62Screen -> 26.sp
                                                        isCompactScreen -> 18.sp
                                                        else -> 24.sp
                                                    },
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(indicatorHeight),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            pages.indices.forEach { index ->
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(if (pagerState.currentPage == index) 10.dp else 7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (pagerState.currentPage == index) {
                                                color_primaryBrand
                                            } else {
                                                color_white.copy(alpha = 0.5f)
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNotAllowed) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { showNotAllowed = false },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .background(color_white, shape = RoundedCornerShape(8.dp))
                    .padding(32.dp),
            ) {
                Icon(
                    Icons.Filled.Warning,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .size(90.dp)
                        .align(Alignment.CenterHorizontally),
                    contentDescription = "warning",
                    tint = color_alert
                )
                Text(
                    text = stringResource(id = R.string.function_not_allowed),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Black
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NewTransactionMenuScreenPreview() {
    GlobalConnectPaymentTheme {
        NewTransactionMenuScreen(onDestinationSelected = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun NewTransactionMenuScreenSecondaryMenuPreview() {
    GlobalConnectPaymentTheme {
        NewTransactionMenuScreen(onDestinationSelected = {}, initialMenuId = "intrasextras")
    }
}

@Preview(showBackground = true)
@Composable
private fun NewTransactionMenuScreenNotAllowedPreview() {
    GlobalConnectPaymentTheme {
        NewTransactionMenuScreen(onDestinationSelected = {}, initialShowNotAllowed = true)
    }
}
