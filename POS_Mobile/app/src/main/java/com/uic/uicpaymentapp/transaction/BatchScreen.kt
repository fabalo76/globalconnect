package com.uic.uicpaymentapp.transaction

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.records.DateFilterDialog
import com.uic.uicpaymentapp.records.SearchView
import com.uic.uicpaymentapp.records.TransactionItem
import com.uic.uicpaymentapp.records.getBottomLineShape
import com.uic.uicpaymentapp.records.verboseMonthDayFormatter
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_grey95
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BatchScreen(
    onBackPress: () -> Unit,
    onPressTransaction: (String) -> Unit
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: BatchViewModel = viewModel(factory = viewModelFactory)

    val uiState by viewModel.uiState.collectAsState()
    when (uiState) {
        is BatchUiState.STOPPED -> {
            var showSearchBar by rememberSaveable { mutableStateOf(false) }
            var showDateDialog by rememberSaveable { mutableStateOf(false) }
            var clickedBatchButton by rememberSaveable { mutableStateOf(false) }
            Scaffold(topBar = {
                TopBar(
                    title = {
                        if (showSearchBar) {
                            SearchView(
                                originalText = viewModel.searchTerm,
                                onValueChange = { newValue -> viewModel.updateSearchTerm(newValue) })
                        } else {
                            Text(
                                fontSize = 20.sp,
                                text = stringResource(id = R.string.open_check_review),
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
                            IconButton(
                                modifier = Modifier
                                    .padding(15.dp)
                                    .size(28.dp),
                                onClick = onBackPress
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "",
                                )
                            }
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
                                                        LocalDate.now() -> stringResource(R.string.today_date_formatter,
                                                            localDate.format(
                                                                verboseMonthDayFormatter
                                                            )
                                                        )

                                                        LocalDate.now().minusDays(1) -> stringResource(
                                                            id = R.string.yesterday_date_formatter, localDate.format(
                                                                verboseMonthDayFormatter
                                                            )
                                                        )

                                                        else -> localDate.format(
                                                            verboseMonthDayFormatter
                                                        )
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
                        Button(
                            modifier = Modifier
                                .fillMaxWidth(1f)
                                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                                .height(54.dp),
                            onClick = {
                                clickedBatchButton = true
                                viewModel.startBatch()
                            },
                            enabled = transactionsByDate.isNotEmpty() && !clickedBatchButton,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = color_primaryBrand,
                                contentColor = color_black
                            )
                        ) {
                            Text(
                                textAlign = TextAlign.Center,
                                text = stringResource(id = R.string.batch_all_number, transactionsByDate.values.flatten().size),
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }

        is BatchUiState.COMPLETE -> {
            val _uiState = uiState as BatchUiState.COMPLETE
            Scaffold(topBar = {
                Box(
                    modifier = Modifier
                        .height(24.dp)
                        .background(color_white)
                        .fillMaxSize()){}
            },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .height(64.dp)
                            .background(color_secondaryThree)
                            .fillMaxSize()){}
                }){ contentPadding ->
                Column(Modifier
                    .padding(contentPadding)
                    .fillMaxSize(1f)
                    .fillMaxHeight()
                    .background(color_secondaryThree)) {
                    Column(
                        Modifier
                            .padding(8.dp,16.dp)
                            .background(color_white, shape = RoundedCornerShape(6.dp))
                    ){
                        Text(
                            text = stringResource(id = R.string.batching_complete_with_errors, _uiState.errors),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(CenterHorizontally)
                                .padding(top = 156.dp),
                            fontSize = 20.sp
                        )
                        Spacer(Modifier.weight(1f))

                        Button(
                            modifier = Modifier
                                .fillMaxWidth(1f)
                                .padding(start = 8.dp, end = 8.dp, top = 12.dp)
                                .height(54.dp),
                            onClick = { viewModel.printTransactions() },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = color_primaryBrand,
                                contentColor = color_black
                            )
                        ) {
                            Text(textAlign = TextAlign.Center, text = stringResource(id = R.string.print_batch_report), fontSize = 16.sp)
                        }

                        Button(
                            modifier = Modifier
                                .fillMaxWidth(1f)
                                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                                .height(54.dp),
                            onClick = onBackPress,
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = color_grey95,
                                contentColor = color_black
                            )
                        ) {
                            Text(textAlign = TextAlign.Center, text = stringResource(id = R.string.batching_done), fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        is BatchUiState.IN_PROGRESS -> {
            val _uiState = uiState as BatchUiState.IN_PROGRESS
            Column(Modifier.fillMaxSize(1f)) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(80.dp)
                        .align(CenterHorizontally)
                        .padding(top = 256.dp, bottom = 32.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(CenterHorizontally),
                    fontSize = 20.sp,
                    text = stringResource(id = R.string.batching_in_progress, _uiState.progressPercent)
                )
            }
        }
    }
}
