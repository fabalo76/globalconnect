package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import one.globalconnect.paymentapp.utils.HardwareKeyManager
import one.globalconnect.paymentapp.utils.HardwareKeyListener
import one.globalconnect.paymentapp.utils.HardwareKeyCommand
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.cardreader.nexgo.awaitContactCardRemoval
import com.nexgo.oaf.apiv3.SdkResult
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_success
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager
import kotlinx.coroutines.delay

@Composable
fun TransactionFinishedScreen(
    onNewSalePress: () -> Unit
) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: FinishedPaymentViewModel = viewModel(factory = viewModelFactory)
    val transaction by viewModel.transaction.observeAsState(Transaction())
    val receiptPreviewState by viewModel.receiptPreviewState.collectAsState(initial = null)
    val merchantPrintStatus by viewModel.merchantReceiptPrintStatus.collectAsState()
    val printerStatusCode by viewModel.printerStatusCode.collectAsState()
    val nexgoApi = remember { GlobalConnectPaymentApplication.instance.nexgoApi }

    val transactionLoaded = transaction.type != TransactionType.ERROR
    val isEcr = transactionLoaded &&
        viewModel.isEcrTransaction
    var dismissed by remember { mutableStateOf(false) }
    var cardRemovalRequired by remember(transaction.id) { mutableStateOf(false) }
    var cardRemovalCheckComplete by remember(transaction.id) { mutableStateOf(false) }
    val isPinMaintenance = OfflinePinChangeContract.isPinMaintenance(transaction.type)
    val currencySymbol = viewModel.tmsAcquirer?.Currency?.takeIf { it.isNotBlank() } ?: "$"
    val mustCheckContactCard = transactionLoaded &&
        requiresContactCardRemoval(transaction.cardEntryMethod)
    val canLeaveResult = transactionLoaded && cardRemovalCheckComplete && !cardRemovalRequired

    val finishResult: () -> Unit = {
        if (!dismissed && canLeaveResult) {
            dismissed = true
            onNewSalePress()
        }
    }
    val currentFinishResult by rememberUpdatedState(finishResult)
    // Consume Back even while waiting for card removal: completed payments must
    // never navigate backward into authorization, sensory or automatic printing.
    BackHandler { finishResult() }
    DisposableEffect(Unit) {
        val listener = HardwareKeyListener { command ->
            if (command == HardwareKeyCommand.Cancel) {
                currentFinishResult()
                true
            } else false
        }
        HardwareKeyManager.registerListener(listener)
        onDispose { HardwareKeyManager.unregisterListener(listener) }
    }

    LaunchedEffect(transaction.id, transactionLoaded, mustCheckContactCard) {
        if (!transactionLoaded) return@LaunchedEffect
        // This destination follows the sensory kit. Submit the merchant copy before
        // starting card-removal prompts or their reminder sounds.
        viewModel.submitAutomaticMerchantReceipt()
        if (mustCheckContactCard) {
            nexgoApi.awaitContactCardRemoval { required ->
                cardRemovalRequired = required
            }
        }
        cardRemovalCheckComplete = true
    }

    LaunchedEffect(canLeaveResult, dismissed, isEcr) {
        if (canLeaveResult && !dismissed) {
            delay(if (isEcr) 5_000 else 15_000)
            if (!dismissed && canLeaveResult) {
                currentFinishResult()
            }
        }
    }

    val amountText = when {
        isPinMaintenance -> null
        transaction.type == TransactionType.LOYALTY_BALANCE -> transaction.loyaltyBalancePoints
            .takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.loyalty_points_available, LoyaltyContract.formatPoints(it)) }
        else -> transaction.totalAmount.takeIf { it.isNotBlank() }?.let { "$currencySymbol$it" }
    }
    val subtitle = transaction.masked_cardNumber.takeIf { it.isNotBlank() }
        ?: transaction.cardRangeName.takeIf { it.isNotBlank() }

    Box(modifier = Modifier.fillMaxSize()) {
        TransactionStatusContainer(
            title = transaction.type.toTransactionName(),
            amountText = amountText,
            subtitle = subtitle,
            attachToTop = true,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircleOutline,
                contentDescription = null,
                modifier = Modifier.size(90.dp),
                tint = color_success,
            )
            Text(
                text = when (transaction.type) {
                    TransactionType.REFUND -> stringResource(id = R.string.refund_complete)
                    TransactionType.OFFLINE_PIN_CHANGE -> {
                        stringResource(id = R.string.offline_pin_change_complete)
                    }
                    TransactionType.PIN_UNBLOCK -> stringResource(id = R.string.pin_unblock_complete)
                    else -> stringResource(id = R.string.payment_complete)
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = when (transaction.type) {
                    TransactionType.OFFLINE_PIN_CHANGE -> {
                        stringResource(id = R.string.offline_pin_change_complete_detail)
                    }
                    TransactionType.PIN_UNBLOCK -> {
                        stringResource(id = R.string.pin_unblock_complete_detail)
                    }
                    else -> stringResource(id = R.string.thank_you_payment)
                },
                fontSize = 16.sp,
                color = color_secondaryFive.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )

            if (!isEcr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_grey95,
                        contentColor = color_black,
                    ),
                    onClick = {
                        SoundManager.play(SoundEffect.KEY_TICK)
                        viewModel.printMerchantReceipt()
                    },
                    enabled = canLeaveResult && merchantPrintStatus == MerchantReceiptPrintStatus.READY,
                ) {
                    Text(
                        textAlign = TextAlign.Center,
                        text = stringResource(id = R.string.merchant_receipt),
                        fontSize = 14.sp,
                        maxLines = 2,
                    )
                }

                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_grey95,
                        contentColor = color_black,
                    ),
                    onClick = {
                        SoundManager.play(SoundEffect.KEY_TICK)
                        viewModel.printCustomerReceipt()
                    },
                    enabled = canLeaveResult && merchantPrintStatus != MerchantReceiptPrintStatus.PRINTING,
                ) {
                    Text(
                        textAlign = TextAlign.Center,
                        text = stringResource(id = R.string.customer_receipt),
                        fontSize = 14.sp,
                        maxLines = 2,
                    )
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = canLeaveResult,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color_primaryBrand,
                    contentColor = color_black,
                ),
                onClick = finishResult,
            ) {
                val label = when (transaction.type) {
                    TransactionType.REFUND -> stringResource(id = R.string.return_sale_screen)
                    TransactionType.OFFLINE_PIN_CHANGE -> {
                        stringResource(id = R.string.offline_pin_change_finish)
                    }
                    TransactionType.PIN_UNBLOCK -> stringResource(id = R.string.offline_pin_change_finish)
                    else -> stringResource(id = R.string.new_transaction)
                }
                Text(textAlign = TextAlign.Center, text = label)
            }
            }
        }

        receiptPreviewState?.let { previewState ->
            Popup(
                popupPositionProvider = WindowTopLeftPopupPositionProvider,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = false,
                ),
            ) {
                ReceiptPrintPreview(
                    state = previewState,
                    onDismissed = { viewModel.dismissReceiptPreview() },
                )
            }
        }

        if (cardRemovalRequired) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                RemoveCardContent()
            }
        }


        printerStatusCode?.let { status ->
            val message = when (status) {
                SdkResult.Printer_PaperLack -> stringResource(R.string.printer_out_of_paper_message)
                SdkResult.Printer_Busy -> stringResource(R.string.printer_busy_message)
                SdkResult.Printer_TooHot -> stringResource(R.string.printer_too_hot_message)
                SdkResult.Printer_NoDevice -> stringResource(R.string.printer_unavailable_message)
                else -> stringResource(R.string.printer_error_message)
            }
            AlertDialog(
                onDismissRequest = viewModel::dismissPrinterStatus,
                title = { Text(stringResource(R.string.printer_error_title)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissPrinterStatus) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }
    }
}

private object WindowTopLeftPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}
