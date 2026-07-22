package one.globalconnect.paymentapp.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.transaction.DatePickerTextField
import one.globalconnect.paymentapp.transaction.ROWHEIGHT
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.transaction.utcToLocal
import one.globalconnect.paymentapp.ui.theme.GlobalConnectPaymentTheme
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.SortedMap
import java.util.TreeMap

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionHistoryScreen(
    onPressTransaction: (String) -> Unit,
    navigateToQuickTipScreen: () -> Unit,
    viewModel: TransactionHistoryViewModel = rememberTransactionHistoryViewModel(),
) {
    val transactionsByDate by viewModel.filteredFlow.collectAsState(sortedMapOf())
    val needTipTransactions by viewModel.needTipTransactionFlow.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val uiState = TransactionHistoryUiState(
        transactionsByDate = transactionsByDate,
        needTipTransactions = needTipTransactions,
        isLoading = isLoading,
        startDate = viewModel.startDate,
        endDate = viewModel.endDate,
        searchTerm = viewModel.searchTerm,
    )

    TransactionHistoryScreenContent(
        onPressTransaction = onPressTransaction,
        navigateToQuickTipScreen = navigateToQuickTipScreen,
        uiState = uiState,
        onSearchTermChange = viewModel::updateSearchTerm,
        onUpdateDateRange = viewModel::updateDateRange,
    )
}

@Composable
private fun rememberTransactionHistoryViewModel(): TransactionHistoryViewModel {
    val context = LocalContext.current
    val factory = remember(context) { AppViewModelProvider.provideFactory(context) }
    return viewModel(factory = factory)
}

private data class TransactionHistoryUiState(
    val transactionsByDate: SortedMap<LocalDate, List<Transaction>>,
    val needTipTransactions: Int,
    val isLoading: Boolean,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val searchTerm: String,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionHistoryScreenContent(
    onPressTransaction: (String) -> Unit,
    navigateToQuickTipScreen: () -> Unit,
    uiState: TransactionHistoryUiState,
    onSearchTermChange: (String) -> Unit,
    onUpdateDateRange: (LocalDate, LocalDate) -> Unit,
) {
    var showSearchBar by rememberSaveable { mutableStateOf(false) }
    val needTipTransactions = uiState.needTipTransactions
    var showDateDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = {
        TopBar(
            title = {
                if (showSearchBar) {
                    SearchView(
                        originalText = uiState.searchTerm,
                        onValueChange = { newValue -> onSearchTermChange(newValue) })
                } else {
                    Text(
                        fontSize = 20.sp,
                        text = stringResource(R.string.transaction_tab_label),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            navigationIcon = {
                if (showSearchBar) {
                    IconButton(
                        modifier = Modifier
                            .padding(15.dp)
                            .size(28.dp),
                        onClick = {
                            onSearchTermChange("")
                            showSearchBar = false
                        }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "",
                        )
                    }
                } else {
                    AddTipIcon(
                        needTipTransactionsSize = needTipTransactions,
                        showTipScreen = navigateToQuickTipScreen
                    )
                }
            },
            actions = {
                if (!showSearchBar) {
                    Box(Modifier.size(100.dp)) {
                        IconButton(
                            onClick = {
                                showSearchBar = true
                            },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(15.dp)
                                .size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = ""
                            )
                        }
                        IconButton(
                            onClick = {
                                showDateDialog = true
                            },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(15.dp)
                                .size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.FilterAlt,
                                contentDescription = ""
                            )
                        }
                    }
                } else {
                    IconButton(
                        onClick = {
                            showDateDialog = true
                        },
                        modifier = Modifier
                            .padding(15.dp)
                            .size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.FilterAlt,
                            contentDescription = ""
                        )
                    }
                }
            },
        )
    }) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .background(color_secondaryThree)
                .fillMaxHeight()
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp, 16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                if (showDateDialog) {
                    DateFilterDialog(
                        updateDateRange = { startDate: LocalDate, endDate: LocalDate ->
                            if (!startDate.isAfter(endDate)) {
                                onUpdateDateRange(startDate, endDate)
                            }
                            showDateDialog = false
                        },
                        initialStartDate = uiState.startDate,
                        initialEndDate = uiState.endDate,
                        closeDialog = { showDateDialog = false })
                }
                val transactionsByDate = uiState.transactionsByDate

                when {
                    uiState.isLoading -> TransactionsLoadingContent()
                    transactionsByDate.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(1f)) {
                            Text(
                                text = stringResource(id = R.string.no_data),
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                        ) {
                            transactionsByDate.forEach { (localDate, transactions) ->
                                stickyHeader {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth(1f)
                                            .height(ROWHEIGHT.dp)
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outline,
                                                shape = getBottomLineShape(lineThicknessDp = 1.dp)
                                            ),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Column(Modifier.fillMaxSize(1f)) {
                                            Text(
                                                modifier = Modifier.padding(
                                                    start = 16.dp,
                                                    top = 16.dp,
                                                    bottom = 8.dp
                                                ),
                                                text = when (localDate) {
                                                    LocalDate.now() -> "${stringResource(id = R.string.filter_today)}, ${
                                                        localDate.format(
                                                            verboseMonthDayFormatter
                                                        )
                                                    }"

                                                    LocalDate.now().minusDays(1) -> "${stringResource(id = R.string.filter_yesterday)}, ${
                                                        localDate.format(
                                                            verboseMonthDayFormatter
                                                        )
                                                    }"

                                                    else -> localDate.format(verboseMonthDayFormatter)
                                                },
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                    }
                                }

                                items(transactions) { transaction ->
                                    TransactionItem(
                                        transaction,
                                        onPressTransaction
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}
@Composable
private fun TransactionsLoadingContent() {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = stringResource(id = R.string.loading_data))
        }
    }
}

@Composable
fun AddTipIcon(
    needTipTransactionsSize: Int,
    modifier: Modifier = Modifier,
    showTipScreen: () -> Unit = {},
) {
    val color = color_primaryBrand
    val text = if (needTipTransactionsSize < 100) {
        "$needTipTransactionsSize"
    } else {
        "99+"
    }
    val fontSize = if (needTipTransactionsSize < 10) {
        14.sp
    } else if (needTipTransactionsSize < 100) {
        12.sp
    } else {
        10.sp
    }

    Box(
        modifier
            .size(height = 40.dp, width = 56.dp)
            .clickable { showTipScreen.invoke() }
    ) {
        Icon(
            Icons.Default.CreditCard,
            contentDescription = "AddTip",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 2.dp)
                .size(28.dp)
        )
        Text(
            text = text,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(height = 20.dp, width = 20.dp)
                .drawBehind {
                    drawCircle(
                        color = color,
                        radius = 24f,
                        center = this.center.plus(Offset(1f, 0.5f))
                    )
                },
            color = color_black,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun DateFilterDialog(
    updateDateRange: (LocalDate, LocalDate) -> Unit,
    initialStartDate: LocalDate,
    initialEndDate: LocalDate,
    closeDialog: () -> Unit,
) {
    var startDate by remember { mutableStateOf(initialStartDate) }
    var endDate by remember { mutableStateOf(initialEndDate) }

    val options = mapOf(
        stringResource(id = R.string.filter_today) to Pair<LocalDate, LocalDate>(
            LocalDate.now(),
            LocalDate.now()
        ),
        stringResource(id = R.string.filter_last_week) to Pair<LocalDate, LocalDate>(
            LocalDate.now().minusDays(7), LocalDate.now()
        ),
        stringResource(id = R.string.filter_last_month) to Pair<LocalDate, LocalDate>(
            LocalDate.now().minusDays(30), LocalDate.now()
        ),
        stringResource(id = R.string.filter_last_three_month) to Pair<LocalDate, LocalDate>(
            LocalDate.now().minusMonths(3),
            LocalDate.now()
        ),
    )

    val optionsReversed = mapOf(
        Pair<LocalDate, LocalDate>(
            LocalDate.now(),
            LocalDate.now()
        ) to stringResource(id = R.string.filter_today),
        Pair<LocalDate, LocalDate>(
            LocalDate.now().minusDays(7),
            LocalDate.now()
        ) to stringResource(id = R.string.filter_last_week),
        Pair<LocalDate, LocalDate>(
            LocalDate.now().minusDays(30),
            LocalDate.now()
        ) to stringResource(id = R.string.filter_last_month),
        Pair<LocalDate, LocalDate>(
            LocalDate.now().minusMonths(3),
            LocalDate.now()
        ) to stringResource(id = R.string.filter_last_three_month),
    )

    var selectedOption by remember {
        mutableStateOf(
            optionsReversed[Pair(
                startDate,
                endDate
            )]
        )
    }

    Dialog(
        onDismissRequest = closeDialog,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp)
        ) {
            Column(Modifier.fillMaxSize(1f)) {
                DateFilterDialogHeader(
                    applyFilter = { updateDateRange(startDate, endDate) },
                    closeDialog = closeDialog
                )

                DatePickerTextField(
                    date = startDate,
                    label = stringResource(id = R.string.start_date),
                    setDate = { milliseconds: Long ->
                        startDate = LocalDate.parse(utcToLocal(milliseconds), dateFormatter)
                        selectedOption = null
                    },
                    modifier = Modifier
                        .width(325.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                )

                DatePickerTextField(
                    date = endDate,
                    label = stringResource(id = R.string.end_date),
                    setDate = { milliseconds: Long ->
                        endDate = LocalDate.parse(utcToLocal(milliseconds), dateFormatter)
                        selectedOption = null
                    },
                    modifier = Modifier
                        .width(325.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                )

                Text(
                    text = stringResource(id = R.string.filter_quick_select),
                    modifier = Modifier.padding(start = 24.dp, top = 32.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp)
                ) {
                    items(options.keys.toList()) { key ->
                        val buttonColor =
                            if (key == selectedOption) color_primaryBrand else color_grey95
                        Button(
                            onClick = {
                                startDate = options[key]?.first ?: LocalDate.now()
                                endDate = options[key]?.second ?: LocalDate.now()
                                selectedOption = key
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor
                            ),
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(
                                text = key,
                                color = color_black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DateFilterDialogHeader(
    applyFilter: () -> Unit,
    closeDialog: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth(1f)
            .height(ROWHEIGHT.dp)
    ) {
        IconButton(
            onClick = closeDialog,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(15.dp)
                .size(32.dp),
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = ""
            )
        }

        Text(
            modifier = Modifier.align(Alignment.CenterVertically),
            text = stringResource(id = R.string.date_filters),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.weight(1f))

        TextButton(
            onClick = applyFilter,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = 16.dp)
                .border(1.dp, color_primaryBrand, shape = RoundedCornerShape(8.dp))
                .height(40.dp),
        ) {
            Text(
                text = stringResource(id = R.string.apply),
                fontWeight = FontWeight.SemiBold,
                color = color_black
            )
        }
    }
}

@Composable
fun getBottomLineShape(lineThicknessDp: Dp): Shape {
    val lineThicknessPx = with(LocalDensity.current) { lineThicknessDp.toPx() }
    return GenericShape { size, _ ->
        // 1) Bottom-left corner
        moveTo(0f, size.height)
        // 2) Bottom-right corner
        lineTo(size.width, size.height)
        // 3) Top-right corner
        lineTo(size.width, size.height - lineThicknessPx)
        // 4) Top-left corner
        lineTo(0f, size.height - lineThicknessPx)
    }
}

@Preview(showBackground = true)
@Composable
private fun TransactionHistoryScreenPreview() {
    GlobalConnectPaymentTheme {
        TransactionHistoryScreenContent(
            onPressTransaction = {},
            navigateToQuickTipScreen = {},
            uiState = previewTransactionHistoryUiState(),
            onSearchTermChange = {},
            onUpdateDateRange = { _, _ -> }
        )
    }
}

private fun previewTransactionHistoryUiState(): TransactionHistoryUiState {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val comparator = compareByDescending<LocalDate> { it }
    val transactionsByDate = TreeMap<LocalDate, List<Transaction>>(comparator)
    transactionsByDate[today] = listOf(
        previewTransaction(
            id = 1,
            transactionId = "123456",
            maskedPan = "************1111",
            amount = "50.00",
            localDateTime = today.atTime(14, 30)
        ),
        previewTransaction(
            id = 2,
            transactionId = "789012",
            maskedPan = "************2222",
            amount = "82.45",
            localDateTime = today.atTime(9, 45)
        ),
    )
    transactionsByDate[yesterday] = listOf(
        previewTransaction(
            id = 3,
            transactionId = "345678",
            maskedPan = "************3333",
            amount = "18.75",
            localDateTime = yesterday.atTime(16, 10)
        )
    )
    return TransactionHistoryUiState(
        transactionsByDate = transactionsByDate,
        needTipTransactions = 3,
        isLoading = false,
        startDate = yesterday,
        endDate = today,
        searchTerm = ""
    )
}

private fun previewTransaction(
    id: Int,
    transactionId: String,
    maskedPan: String,
    amount: String,
    localDateTime: LocalDateTime,
): Transaction {
    return Transaction(
        id = id,
        transactionId = transactionId,
        totalAmount = amount,
        baseAmount = amount,
        masked_cardNumber = maskedPan,
        localDateTime = localDateTime.format(dateTimeFormatter),
        type = TransactionType.SALE,
        tipAmount = "5.00"
    ).applyFormattedTimes(localDateTime)
}

