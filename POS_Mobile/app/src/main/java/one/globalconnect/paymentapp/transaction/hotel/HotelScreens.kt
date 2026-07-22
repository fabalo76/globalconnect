package one.globalconnect.paymentapp.transaction.hotel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.records.BackButton
import one.globalconnect.paymentapp.records.SearchView
import one.globalconnect.paymentapp.transaction.AmountPromptConfig
import one.globalconnect.paymentapp.transaction.PaymentDetails
import one.globalconnect.paymentapp.transaction.ReturnUiState
import one.globalconnect.paymentapp.transaction.SaleScreen
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.transaction.toTransactionString
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.transaction.Transaction
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import one.globalconnect.paymentapp.records.applyFormattedTimes
import one.globalconnect.paymentapp.records.defaultFormattedTime
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white

@Composable
fun HotelCheckInScreen(
    onSubmit: (String, String, String, String, String) -> Unit,
) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: HotelCheckInViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    val promptConfig = rememberPromptConfig(TransactionType.CHECKIN)

    SaleScreen(
        onChargeClick = { _, base, tax1, tax2, tip, folio ->
            onSubmit(base, tax1, tax2, tip, folio)
        },
        transactionType = TransactionType.CHECKIN,
        promptConfig = promptConfig,
        topContent = {
            if (!state.autoFolio) {
                OutlinedTextField(
                    value = state.folioNumber,
                    onValueChange = viewModel::updateManualFolio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, start = 24.dp, end = 24.dp),
                    label = { Text(stringResource(id = R.string.hotel_folio_label)) },
                    placeholder = { Text(stringResource(id = R.string.hotel_folio_hint)) },
                    singleLine = true,
                    supportingText = state.errorMessage?.let { message ->
                        { Text(message, color = MaterialTheme.colorScheme.error) }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        },
        onBeforeCharge = {
            viewModel.validateFolio()
        },
        collectSupplementaryValue = {
            viewModel.folioForSubmission()
        }
    )
}

@Composable
fun HotelCheckOutScreen(
    onSubmit: (String, String, String, String, String, Int, String) -> Unit,
) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: HotelCheckOutViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()
    val promptConfig = rememberPromptConfig(TransactionType.CHECKOUT)

    SaleScreen(
        onChargeClick = { _, base, tax1, tax2, tip, _ ->
            val checkIn = state.matchedCheckIn ?: return@SaleScreen
            onSubmit(base, tax1, tax2, tip, checkIn.folioNumber, checkIn.id, checkIn.transactionId)
        },
        transactionType = TransactionType.CHECKOUT,
        promptConfig = promptConfig,
        topContent = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(id = R.string.hotel_check_out_heading),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.folioInput,
                    onValueChange = viewModel::updateFolioInput,
                    label = { Text(stringResource(id = R.string.hotel_folio_label)) },
                    placeholder = { Text(stringResource(id = R.string.hotel_folio_hint)) },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.searchForCheckIn() },
                    enabled = !state.isSearching,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(text = stringResource(id = R.string.hotel_check_out_search))
                }
                state.errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.matchedCheckIn?.let { checkIn ->
                    Spacer(modifier = Modifier.height(16.dp))
                    CheckInSummary(checkIn)
                }
            }
        },
        onBeforeCharge = {
            val ready = state.matchedCheckIn != null
            if (!ready) {
                viewModel.searchForCheckIn()
            }
            ready
        },
        collectSupplementaryValue = {
            state.matchedCheckIn?.folioNumber ?: ""
        }
    )
}

@Composable
private fun CheckInSummary(checkIn: Transaction) {
    val date = remember(checkIn.localDateTime) {
        checkIn.applyFormattedTimes()
        checkIn.formattedVerboseDateTime.ifEmpty { checkIn.localDateTime }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(id = R.string.hotel_check_out_summary_folio, checkIn.folioNumber),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(id = R.string.hotel_check_out_summary_amount, checkIn.totalAmount),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(id = R.string.hotel_check_out_summary_card, checkIn.masked_cardNumber),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(id = R.string.hotel_check_out_summary_date, date),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun HotelCheckInReportScreen(
    onBack: () -> Unit,
    onCheckOut: (Transaction) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: HotelCheckInReportViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSearchBar by rememberSaveable { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    var showPaymentDetails by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HotelCheckInReportEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    LaunchedEffect(selectedTransaction) {
        if (selectedTransaction == null) {
            showPaymentDetails = false
        }
    }

    if (selectedTransaction != null && showPaymentDetails) {
        PaymentDetails(
            transaction = selectedTransaction!!,
            onBackButtonPressed = { showPaymentDetails = false }
        )
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                when {
                    selectedTransaction == null -> {
                        TopBar(
                            title = {
                                if (showSearchBar) {
                                    SearchView(
                                        originalText = uiState.searchTerm,
                                        onValueChange = viewModel::updateSearchTerm,
                                    )
                                } else {
                                    Text(text = stringResource(id = R.string.hotel_check_in_report_title))
                                }
                            },
                            navigationIcon = {
                                if (showSearchBar) {
                                    IconButton(
                                        onClick = {
                                            showSearchBar = false
                                            viewModel.updateSearchTerm("")
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(id = R.string.back),
                                        )
                                    }
                                } else {
                                    BackButton(onBackPressed = onBack)
                                }
                            },
                            actions = {
                                if (!showSearchBar) {
                                    IconButton(onClick = { showSearchBar = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = stringResource(id = R.string.search_by),
                                        )
                                    }
                                }
                            }
                        )
                    }

                    else -> {
                        TopBar(
                            title = {
                                Text(text = stringResource(id = R.string.hotel_check_in_report_detail_title))
                            },
                            navigationIcon = {
                                BackButton(
                                    onBackPressed = {
                                        selectedTransaction = null
                                        viewModel.clearActionStatus()
                                    }
                                )
                            },
                            actions = {}
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .background(color_secondaryThree)
                    .fillMaxSize()
            ) {
                if (selectedTransaction == null) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 16.dp)
                            .background(color_white, shape = RoundedCornerShape(6.dp))
                            .fillMaxSize()
                    ) {
                        when {
                            uiState.isLoading -> LoadingContent()
                            uiState.entries.isEmpty() -> EmptyCheckInContent()
                            else -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(uiState.entries) { transaction ->
                                        CheckInReportItem(
                                            transaction = transaction,
                                            onClick = {
                                                selectedTransaction = transaction
                                                viewModel.clearActionStatus()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val transaction = selectedTransaction!!
                    CheckInDetailContent(
                        transaction = transaction,
                        actionStatus = uiState.actionStatus,
                        isPrinting = uiState.isPrinting,
                        onShowPaymentDetails = { showPaymentDetails = true },
                        onReprint = { viewModel.reprint(transaction) },
                        onIncrement = { viewModel.showIncrementPlaceholder() },
                        onCheckOut = { onCheckOut(transaction) },
                        onVoid = { viewModel.voidCheckIn(transaction) },
                        onDismiss = {
                            selectedTransaction = null
                            viewModel.clearActionStatus()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.loading_data),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyCheckInContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.hotel_check_in_report_empty),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CheckInReportItem(
    transaction: Transaction,
    onClick: (Transaction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(transaction) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${transaction.orderNo}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.widthIn(min = 48.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.hotel_check_in_report_detail_amount, transaction.totalAmount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(id = R.string.hotel_check_in_report_detail_folio, transaction.folioNumber),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        id = R.string.hotel_check_in_report_detail_card_last_four,
                        transaction.masked_cardNumber.takeLast(4)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = transaction.formattedTime.ifBlank { defaultFormattedTime },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = transaction.formattedVerboseDateTime.ifBlank { transaction.localDateTime },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun CheckInDetailContent(
    transaction: Transaction,
    actionStatus: ReturnUiState?,
    isPrinting: Boolean,
    onShowPaymentDetails: () -> Unit,
    onReprint: () -> Unit,
    onIncrement: () -> Unit,
    onCheckOut: () -> Unit,
    onVoid: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 16.dp)
            .background(color_white, shape = RoundedCornerShape(6.dp))
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .fillMaxWidth(),
        ) {
            Text(
                text = stringResource(id = R.string.hotel_check_in_report_detail_amount, transaction.totalAmount),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            CheckInDetailRow(
                label = stringResource(id = R.string.hotel_check_in_report_detail_folio_label),
                value = transaction.folioNumber,
            )
            CheckInDetailRow(
                label = stringResource(id = R.string.hotel_check_in_report_detail_card_label),
                value = transaction.masked_cardNumber,
            )
            CheckInDetailRow(
                label = stringResource(id = R.string.hotel_check_in_report_detail_date_label),
                value = transaction.formattedVerboseDateTime.ifBlank { transaction.localDateTime },
            )
            CheckInDetailRow(
                label = stringResource(id = R.string.hotel_check_in_report_detail_auth_label),
                value = transaction.authCode,
            )
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShowPaymentDetails)
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    text = stringResource(id = R.string.payment_details),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CheckInActionButton(
                        text = stringResource(id = R.string.reprint),
                        onClick = onReprint,
                        enabled = !isPrinting,
                        modifier = Modifier.weight(1f),
                    )
                    CheckInActionButton(
                        text = stringResource(id = R.string.hotel_check_in_report_increment),
                        onClick = onIncrement,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CheckInActionButton(
                        text = stringResource(id = R.string.trans_check_out),
                        onClick = onCheckOut,
                        modifier = Modifier.weight(1f),
                    )
                    CheckInActionButton(
                        text = stringResource(id = R.string.hotel_check_in_report_void),
                        onClick = onVoid,
                        enabled = actionStatus !is ReturnUiState.Loading,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            when (actionStatus) {
                is ReturnUiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = actionStatus.message)
                    }
                }

                is ReturnUiState.ResultReady -> {
                    Text(
                        text = actionStatus.message,
                        color = if (actionStatus.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }

                else -> {}
            }
            Spacer(modifier = Modifier.height(24.dp))
            CheckInActionButton(
                text = stringResource(id = R.string.close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CheckInDetailRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CheckInActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color_grey95,
            disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
            contentColor = color_black,
            disabledContentColor = color_black.copy(alpha = 0.6f),
        )
    ) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}

@Composable
private fun rememberPromptConfig(type: TransactionType): AmountPromptConfig {
    val db = GlobalConnectPaymentApplication.instance.tmsDatabase
    return TransactionConfigRegistry.amountPromptConfigFor(type.toTransactionString(), db.Terminal.firstOrNull(), db.Acquirer)
        ?: AmountPromptConfig(
            askAmount = true,
            askTax1 = false,
            askTax2 = false,
            askTip = false,
        )
}
