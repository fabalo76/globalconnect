package one.globalconnect.paymentapp.transaction

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.records.BackButton
import one.globalconnect.paymentapp.records.DateFilterDialog
import one.globalconnect.paymentapp.records.QuickTipTransactionItem
import one.globalconnect.paymentapp.records.QuickTipViewModel
import one.globalconnect.paymentapp.records.SearchView
import one.globalconnect.paymentapp.records.getBottomLineShape
import one.globalconnect.paymentapp.records.verboseMonthDayFormatter
import one.globalconnect.paymentapp.ui.theme.color_grey90
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import java.time.LocalDate

@Composable
fun QuickTipScreen(
    onBackPress: () -> Unit
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: QuickTipViewModel = viewModel(factory = viewModelFactory)

    var screenToShow by rememberSaveable { mutableStateOf(TipScreens.HistoryScreen) }
    when (screenToShow) {
        TipScreens.HistoryScreen -> {
            QuickTipRecordsScreen(viewModel = viewModel,
                onPressTransaction = { txnId ->
                    screenToShow = TipScreens.TransactionScreen
                    viewModel.swapTransaction(txnId)
                },
                onBackPress = onBackPress)
        }

        TipScreens.TransactionScreen -> {
            QuickTipAdjustScreen(viewModel = viewModel,
                onBackPress = { screenToShow = TipScreens.HistoryScreen })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickTipRecordsScreen(
    viewModel: QuickTipViewModel,
    onPressTransaction: (Int) -> Unit,
    onBackPress: () -> Unit
) {
    var showSearchBar by rememberSaveable { mutableStateOf(false) }
    var showDateDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = {
        TopBar(
            title = {
                if (showSearchBar) {
                    SearchView(originalText = viewModel.searchTerm, onValueChange = { newValue -> viewModel.updateSearchTerm(newValue) })
                } else {
                    Text(
                        fontSize = 20.sp,
                        text = stringResource(id = R.string.untipped) ,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            navigationIcon = {
                if (showSearchBar) {
                    IconButton(modifier = Modifier
                        .padding(15.dp)
                        .size(28.dp),
                        onClick = {
                            viewModel.updateSearchTerm("")
                            showSearchBar = false
                        }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "",
                        )
                    }
                } else {
                    BackButton(
                        onBackPressed = onBackPress,
                        text = stringResource(id = R.string.back)
                    )
                }
            },
            actions = {
                if (!showSearchBar) {
                    Box(Modifier.size(100.dp)) {
                        if (!showSearchBar) {
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


            }
        )
    }) { contentPadding ->
        Column(modifier = Modifier
            .padding(contentPadding)
            .background(color_secondaryThree)) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
            ){
                if (showDateDialog) {
                    DateFilterDialog(
                        updateDateRange = { startDate: LocalDate, endDate: LocalDate ->
                            viewModel.updateDateRange(
                                startDate,
                                endDate
                            )
                            showDateDialog = false
                        },
                        initialStartDate = viewModel.startDate,
                        initialEndDate = viewModel.endDate,
                        closeDialog = { showDateDialog = false })
                }
                val transactionsByDate by viewModel.filteredFlow.collectAsState(
                    mutableMapOf<LocalDate, MutableList<Transaction>>()
                )

                if (transactionsByDate.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(1f)) {
                        Text(
                            text = stringResource(id = R.string.no_data),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
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
                                        )
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
                                QuickTipTransactionItem(
                                    transaction,
                                    onPressTransaction
                                )
                            }
                        }
                    }
                }
                Button(
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(24.dp),
                    onClick = {
                        viewModel.updateTransactions()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_secondaryThree, // Color de fondo del botón
                        contentColor = color_primaryBrand, // Color del texto
                        disabledContainerColor = color_grey90
                    ),
                    enabled = viewModel.tippedTransactions > 0
                ) {
                    Text("${stringResource(id = R.string.add_new_tips)} (${viewModel.tippedTransactions})")
                }
            }
        }
    }

}

@Composable
fun QuickTipAdjustScreen(
    viewModel: QuickTipViewModel,
    onBackPress: () -> Unit,
) {
    val transaction by viewModel.transactionFlow.collectAsState(Transaction())
    TipAdjustScreen(onBackButtonPressed = onBackPress,
        transaction = transaction,
        confirmTipAmount = { tipAmount -> viewModel.confirmTip(tipAmount) },
        nextTransaction = { currentId ->
            viewModel.swapTransaction(viewModel.getNextTransactionId(currentId))
        })
}

enum class TipScreens {
    HistoryScreen,
    TransactionScreen
}