package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.profile.labelResource
import one.globalconnect.paymentapp.records.CardIcon
import one.globalconnect.paymentapp.records.defaultFormattedVerboseDateTime
import one.globalconnect.paymentapp.security.TerminalPasswordAction
import one.globalconnect.paymentapp.security.TerminalPasswordPolicy
import one.globalconnect.paymentapp.ui.theme.color_Green80
import one.globalconnect.paymentapp.ui.theme.color_Pink80
import one.globalconnect.paymentapp.ui.theme.color_Purple40
import one.globalconnect.paymentapp.ui.theme.color_Yellow80
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white

const val ROWHEIGHT = 56

@Composable
fun EditTransactionScreen(
    onBackButtonPressed: () -> Unit
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: TransactionDetailsViewModel = viewModel(factory = viewModelFactory)

    var screenToShow by rememberSaveable { mutableStateOf(TransactionViewScreen.MainScreen) }
    var pendingPasswordAction by rememberSaveable {
        mutableStateOf<TerminalPasswordAction?>(null)
    }
    val transaction by viewModel.transactionFlow.collectAsState()
    val terminal = GlobalConnectPaymentApplication.instance.tmsDatabase.Terminal.firstOrNull()

    when (screenToShow) {
        TransactionViewScreen.MainScreen -> TransactionMainScreen(
            onBackButtonPressed = onBackButtonPressed,
            onDetailsButtonPressed = { screenToShow = TransactionViewScreen.PaymentDetails },
            onPrintButtonPressed = { viewModel.printReceipt() },
            onTipAdjustPressed = {
                if (TerminalPasswordPolicy.requiresPassword(terminal, TerminalPasswordAction.ADJUST)) {
                    pendingPasswordAction = TerminalPasswordAction.ADJUST
                } else {
                    screenToShow = TransactionViewScreen.AdjustTip
                }
            },
            onRefundPressed = {
                if (TerminalPasswordPolicy.requiresPassword(terminal, TerminalPasswordAction.VOID)) {
                    pendingPasswordAction = TerminalPasswordAction.VOID
                } else {
                    screenToShow = TransactionViewScreen.Refund
                }
            },
            transaction = transaction
        )

        TransactionViewScreen.AdjustTip -> {
            TipAdjustScreen(onBackButtonPressed = {
                screenToShow = TransactionViewScreen.MainScreen
            },
                transaction = transaction,
                confirmTipAmount = { newTipAmount ->
                    viewModel.confirmTip(newTipAmount)
                })
        }

        TransactionViewScreen.Refund -> {
            val returnUiState by viewModel.returnUiState.collectAsState(ReturnUiState.None())
            RefundScreen(transaction = transaction, onBackButtonPressed = {
                viewModel.resetReturnUiState()
                screenToShow = TransactionViewScreen.MainScreen
            }, onRefundPressed = { viewModel.startReturn() }, returnUiState = returnUiState
            )
        }

        TransactionViewScreen.PaymentDetails -> {
            PaymentDetails(transaction = transaction,
                onBackButtonPressed = { screenToShow = TransactionViewScreen.MainScreen })

        }

    }

    pendingPasswordAction?.let { action ->
        TransactionPasswordDialog(
            action = action,
            onAuthenticated = {
                pendingPasswordAction = null
                screenToShow = when (action) {
                    TerminalPasswordAction.ADJUST -> TransactionViewScreen.AdjustTip
                    else -> TransactionViewScreen.Refund
                }
            },
            onDismiss = { pendingPasswordAction = null },
        )
    }
}

@Composable
fun TransactionMainScreen(
    onBackButtonPressed: () -> Unit,
    onDetailsButtonPressed: () -> Unit,
    onPrintButtonPressed: () -> Unit,
    onTipAdjustPressed: () -> Unit,
    onRefundPressed: () -> Unit,
    transaction: Transaction
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.border(BorderStroke(5.dp, color_Green80)),
        topBar = {
            TopBar(title = {
                Text(
                    text = stringResource(id = R.string.transaction_details),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }, modifier = Modifier.border(BorderStroke(5.dp, color_Yellow80)),
                navigationIcon = {
                TextButton(
                    onClick = onBackButtonPressed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_white,
                        contentColor = color_black,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Left"
                    )
                }
            }, actions = null,
                windowInsets = WindowInsets(0),
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .padding(contentPadding)
                .background(color_secondaryThree)
                .fillMaxSize()
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .border(BorderStroke(5.dp, color_Purple40))
                    .verticalScroll(rememberScrollState())
                    .background(color_white, shape = RoundedCornerShape(6.dp))
            ) {
                val returnStatus =
                    if (transaction.returnStatus == ReturnStatus.None) "" else "(${transaction.returnStatus})"
                Text(
                    text = "$${transaction.totalAmount} ${transaction.type.toStringForUsers()} $returnStatus",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp),
                    color = Color.Black
                )

                val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
                val tipEnabled = if (isPreview) true else remember(transaction.acquirerId) {
                    GlobalConnectPaymentApplication.instance.tmsDatabase.Acquirer
                        .find { it.AcqID == transaction.acquirerId }
                        ?.TIPProcs != 0L
                }
                val buttonModifier = Modifier.weight(1f).height(47.dp)
                val buttonShape = RoundedCornerShape(24.dp)
                val buttonColors = ButtonDefaults.buttonColors(
                    containerColor = color_grey95,
                    disabledContainerColor = color_grey95,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = buttonModifier,
                        onClick = onPrintButtonPressed,
                        shape = buttonShape,
                        colors = buttonColors,
                    ) {
                        Text(
                            textAlign = TextAlign.Center,
                            text = stringResource(id = R.string.reprint),
                            fontSize = 16.sp,
                            color = color_black
                        )
                    }
                    Button(
                        modifier = buttonModifier,
                        onClick = onRefundPressed,
                        enabled = transaction.returnStatus == ReturnStatus.None,
                        shape = buttonShape,
                        colors = buttonColors,
                    ) {
                        Text(
                            textAlign = TextAlign.Center,
                            text = stringResource(id = R.string.void_short),
                            fontSize = 16.sp,
                            color = color_black
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = buttonModifier,
                        onClick = onTipAdjustPressed,
                        enabled = tipEnabled &&
                            (transaction.checkStatus == CheckStatus.NeedTip ||
                             transaction.checkStatus == CheckStatus.Open),
                        shape = buttonShape,
                        colors = buttonColors,
                    ) {
                        if (tipEnabled) {
                            Text(
                                textAlign = TextAlign.Center,
                                text = stringResource(id = R.string.trans_tip),
                                fontSize = 16.sp,
                                color = color_black
                            )
                        }
                    }
                    Button(
                        modifier = buttonModifier,
                        onClick = {},
                        enabled = false,
                        shape = buttonShape,
                        colors = buttonColors,
                    ) {}
                }

                Row(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(top = 16.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.records_title_payment),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.formattedVerboseDateTime.ifBlank {
                            defaultFormattedVerboseDateTime
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()  //fill the max height
                        .height(1.dp), color = MaterialTheme.colorScheme.outline
                )

                Row(
                    Modifier
                        .fillMaxWidth(1f)
                        .height(ROWHEIGHT.dp)
                ) {
                    CardIcon(
                        _paymentNetworkString = transaction.cardType,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(start = 16.dp, end = 16.dp)
                    )
                    Column(
                        modifier = Modifier.fillMaxHeight(1f), verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${transaction.cardType} ${transaction.masked_cardNumber.takeLast(4)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Text(
                            text = transaction.cardEntryMethod.CapitalizeFirstLowerRest(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()  //fill the max height
                        .height(1.dp), color = MaterialTheme.colorScheme.outline
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(ROWHEIGHT.dp)
                        .clickable { onDetailsButtonPressed.invoke() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = "Receipt",
                        modifier = Modifier
                            .size(48.dp)
                            .padding(start = 24.dp)
                            .align(Alignment.CenterVertically)
                    )
                    Text(
                        text = stringResource(id = R.string.payment_details),
                        textAlign = TextAlign.Start,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(start = 24.dp)
                            .align(Alignment.CenterVertically)
                    )
                    Spacer(Modifier.weight(1f))

                    if (transaction.errorOnCapture) {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = "QueryItemIcon",
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .height(25.dp)
                                .width(25.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "",
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(end = 16.dp)
                    )
                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()  //fill the max height
                        .height(1.dp), color = MaterialTheme.colorScheme.outline
                )

                Total(transaction)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionPasswordDialog(
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
                    text = "•".repeat(input.length).padEnd(4, '·'),
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        text = stringResource(R.string.incorrect_password),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DialogNumpad(
                    onSelected = { key ->
                        when (key) {
                            "C" -> { input = input.dropLast(1); showError = false }
                            else -> if (input.length < 4) input += key
                        }
                    },
                    onEnterPressed = ::checkAndSubmit,
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

@Composable
fun RefundScreen(
    transaction: Transaction,
    onBackButtonPressed: () -> Unit,
    onRefundPressed: () -> Unit,
    returnUiState: ReturnUiState
) {
    Scaffold(
        topBar = {
            TopBar(title = {
                Text(
                    text = stringResource(id = R.string.trans_void),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }, navigationIcon = {
                TextButton(
                    onClick = onBackButtonPressed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_white,
                        contentColor = color_black, // Color del texto
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Left"
                    )
                }
            }, actions = null
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize(1f)
                .padding(contentPadding)
                .background(color_secondaryThree)
        ) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxSize()
            ){
                when (returnUiState) {
                    is ReturnUiState.None -> {
                        Text(
                            stringResource(
                                id = R.string.void_transaction_amount, transaction.totalAmount
                            ),
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 128.dp),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.weight(1f))

                        Button(
                            modifier = Modifier
                                .fillMaxWidth(1f)
                                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                                .height(50.dp),
                            onClick = onRefundPressed,
                            shape = RoundedCornerShape(24.dp),
                            enabled = transaction.type != TransactionType.REFUND,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = color_primaryBrand, // Color de fondo del botón
                                contentColor = color_black, // Color del texto
                            ),
                        ) {
                            Text(
                                textAlign = TextAlign.Center,
                                text = if (transaction.type == TransactionType.REFUND) stringResource(id = R.string.no_voids_on_refunds) else stringResource(
                                    id = R.string.start_void
                                ),
                                fontSize = 16.sp
                            )
                        }
                    }

                    is ReturnUiState.Loading -> {
                        Column(Modifier.fillMaxSize(1f)) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(80.dp)
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 128.dp),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                modifier = Modifier
                                    .padding(top = 256.dp)
                                    .align(Alignment.CenterHorizontally),
                                fontSize = 20.sp,
                                text = returnUiState.message
                            )
                        }
                    }

                    is ReturnUiState.ResultReady -> {
                        val text = if (returnUiState.isSuccess) {
                            stringResource(id = R.string.transaction_id_voided_success, transaction.transactionId)
                        } else {
                            stringResource(id = R.string.void_error)
                        }
                        Text(
                            text,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 128.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 24.sp,
                        )

                        Spacer(Modifier.weight(1f))

                        Button(
                            modifier = Modifier
                                .fillMaxWidth(1f)
                                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                                .height(50.dp),
                            onClick = onBackButtonPressed,
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = color_primaryBrand, // Color de fondo del botón
                                contentColor = color_black, // Color del texto
                            ),
                        ) {
                            Text(textAlign = TextAlign.Center, text = stringResource(id = R.string.msg_done), fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun Total(
    transaction: Transaction
) {
    Row(
        Modifier
            .fillMaxWidth(1f)
            .padding(top = 24.dp, bottom = 4.dp)
    ) {
        Text(
            text = stringResource(id = R.string.payment_details_total),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()  //fill the max height
            .height(1.dp), color = MaterialTheme.colorScheme.outline
    )
    val padding = 16
    Row(Modifier.padding(start = padding.dp, end = padding.dp, top = 16.dp)) {
        Text(
            text = stringResource(id = R.string.subtotal_hyphen),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$${transaction.subTotal}",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
    Row(Modifier.padding(start = padding.dp, end = padding.dp, top = 8.dp)) {
        Text(text = stringResource(id = R.string.tip_hyphen), fontSize = 16.sp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.weight(1f))
        Text(
            text = "$${transaction.tipAmount}",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
    Row(Modifier.padding(start = padding.dp, end = padding.dp, top = 8.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(id = R.string.total_hyphen),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Text(text = "$${transaction.totalAmount}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PaymentDetails(
    transaction: Transaction, onBackButtonPressed: () -> Unit
) {
    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.payment_details),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBackButtonPressed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color_white,
                            contentColor = color_black,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Left"
                        )
                    }
                },
                actions = {}
            )
        },
    ) { contentPadding ->
        val padding = 16
        Column(
            Modifier
                .fillMaxWidth(1f)
                .padding(contentPadding)
                .background(color_secondaryThree)
                .fillMaxSize()
        ) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxSize()
            ) {
                Text(
                        text = transaction.formattedVerboseDateTime.ifBlank {
                            defaultFormattedVerboseDateTime
                        },
                    textAlign = TextAlign.Start,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = padding.dp, bottom = 4.dp, top = 8.dp)
                )
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()  //fill the max height
                        .height(1.dp)
                        .padding(bottom = 4.dp), color = MaterialTheme.colorScheme.outline
                )
                Row(Modifier.padding(start = padding.dp, end = padding.dp, top = 8.dp)) {
                    Text(
                        text = stringResource(id = R.string.transaction_id),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.transactionId,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                    Text(
                        text = stringResource(id = R.string.order),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.orderNo.toString(),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                    Text(
                        text = stringResource(id = R.string.transaction_type),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.type.toStringForUsers(),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                    Text(
                        text = stringResource(id = R.string.card_network),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.cardType,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                    Text(
                        text = stringResource(id = R.string.entry_method),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.cardEntryMethod.CapitalizeFirstLowerRest(),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                    Text(
                        text = stringResource(id = R.string.auth_code),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.authCode,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                    Text(
                        text = stringResource(id = R.string.invoice_ID),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.invoiceId,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                    Text(
                        text = stringResource(id = R.string.AID),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = transaction.AID,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                    Text(
                        text = stringResource(id = R.string.check_status),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.weight(1f))
                    val statusText = when (transaction.checkStatus) {
                        CheckStatus.Open -> stringResource(id = R.string.check_status_open)
                        CheckStatus.NeedTip -> stringResource(id = R.string.check_status_need_tip)
                        CheckStatus.Closed -> stringResource(id = R.string.check_status_closed)
                        CheckStatus.Error -> stringResource(id = R.string.trans_error)
                    }
                    Text(
                        text = statusText,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                if (transaction.errorOnCapture) {
                    Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                        Text(
                            text = stringResource(id = R.string.capture_error_title),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = transaction.errMessage,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.End
                        )
                    }
                }

                if (transaction.checkStatus == CheckStatus.Error) {
                    Row(Modifier.padding(start = padding.dp, end = padding.dp)) {
                        Text(
                            text = stringResource(id = R.string.capture_unknown_state_title),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(id = R.string.capture_unknown_state_message),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.End
                        )
                    }
                }



                Total(transaction)
            }
        }
    }
}


enum class TransactionViewScreen {
    MainScreen, PaymentDetails, AdjustTip, Refund
}

private fun previewTransaction() = Transaction(
    totalAmount = "100.00",
    type = TransactionType.SALE,
    cardType = "VISA",
    masked_cardNumber = "************6261",
    cardEntryMethod = "Emv_contactless",
    checkStatus = CheckStatus.Open,
    returnStatus = ReturnStatus.None,
).also { it.formattedVerboseDateTime = "May 2, 4:16 PM" }

/** Mimics the outer UICApp Scaffold shell (logo topBar + bottom nav stub). */
@Composable
private fun PreviewShell(content: @Composable () -> Unit) {
    val swDp = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp
    val isCompact = swDp < 360
    val isN62 = swDp >= 480
    androidx.compose.material3.Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(
                        top = if (isCompact) 6.dp else 12.dp,
                        bottom = if (isCompact) 2.dp else 5.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.logo_color),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(when { isN62 -> 56.dp; isCompact -> 36.dp; else -> 50.dp }),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        },
        bottomBar = {
            // stub height matching the real nav bar so content area matches runtime
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(MaterialTheme.colorScheme.surface)
            )
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(innerPadding)) {
            content()
        }
    }
}

@Preview(showSystemUi = true, name = "N96 (5.5\")",
    device = "spec:width=720px,height=1280px,dpi=267,isRound=false")
@Composable
private fun PreviewN96() {
    PreviewShell { TransactionMainScreen({}, {}, {}, {}, {}, previewTransaction()) }
}

@Preview(showSystemUi = true, name = "N82 (4.0\")",
    device = "spec:width=480px,height=854px,dpi=240,isRound=false")
@Composable
private fun PreviewN82() {
    PreviewShell { TransactionMainScreen({}, {}, {}, {}, {}, previewTransaction()) }
}

@Preview(showSystemUi = true, name = "N62 (3.5\")",
    device = "spec:width=320px,height=480px,dpi=180,isRound=false")
@Composable
private fun PreviewN62() {
    PreviewShell { TransactionMainScreen({}, {}, {}, {}, {}, previewTransaction()) }
}
