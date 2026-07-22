package one.globalconnect.paymentapp.records

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.transaction.DatePickerTextField
import one.globalconnect.paymentapp.transaction.ROWHEIGHT
import one.globalconnect.paymentapp.transaction.utcToLocal
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.ui.preview.RecordsPreviewData
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager
import java.time.LocalDate

@Composable
fun ReportsScreen(
    @StringRes titleResId: Int? = null,
    onBack: (() -> Unit)? = null,
    showPrintDialogOnStart: Boolean = false,
    defaultPrintTransactions: Boolean = false,
//    viewModel: ReportViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {

    val context = LocalContext.current

    // Crea la fábrica con el contexto actual
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    // Obtén el InfoMgmtViewModel con la fábrica personalizada
    val viewModel: ReportViewModel = viewModel(factory = viewModelFactory)
    var showPrintDialog by rememberSaveable { mutableStateOf(showPrintDialogOnStart) }
    var showCustomDateRangePicker by rememberSaveable { mutableStateOf(false) }

    val summaryUiState by viewModel.summaryUiState.collectAsState(ReconciliationTotals())
    val customFilterItem = stringResource(id = R.string.filter_custom)
    val filterItems = listOf(
        stringResource(id = R.string.filter_today),
        stringResource(id = R.string.filter_last_week),
        stringResource(id = R.string.filter_last_month),
        customFilterItem,
    )

    ReportsScreenContent(
        title = titleResId?.let { stringResource(id = it) },
        startDate = viewModel.startDate,
        endDate = viewModel.endDate,
        filterItems = filterItems,
        summaryUiState = summaryUiState,
        //transactionMode = SysParam.getInstance().transactionMode,
        showPrintDialog = showPrintDialog,
        showCustomDateRangePicker = showCustomDateRangePicker,
        defaultPrintTransactions = defaultPrintTransactions,
        onBack = onBack?.let {
            {
                SoundManager.play(SoundEffect.KEY_DELETE)
                it()
            }
        },
        onFilterSelected = { item ->
            if (item == customFilterItem) {
                showCustomDateRangePicker = true
            } else {
                val pair = viewModel.convertDropdownItemToDate(item)
                viewModel.applyDateFilter(pair.first, pair.second)
            }
        },
        onShowPrintDialog = { showPrintDialog = true },
        onDismissPrintDialog = { showPrintDialog = false },
        onDismissCustomDateRangePicker = { showCustomDateRangePicker = false },
        onApplyCustomDateRange = { startDate, endDate ->
            if (!startDate.isAfter(endDate)) {
                viewModel.applyDateFilter(
                    startDate.format(dateFormatter),
                    endDate.format(dateFormatter),
                )
            }
            showCustomDateRangePicker = false
        },
        onPrintReport = { startDate, endDate, printTransactions ->
            if (!startDate.isAfter(endDate)) {
                viewModel.applyDateFilter(
                    startDate.format(dateFormatter),
                    endDate.format(dateFormatter),
                )
            }
            viewModel.printReport(context, printTransactions)
            showPrintDialog = false
        },
    )
}

@Composable
private fun ReportsScreenContent(
    modifier: Modifier = Modifier,
    title: String?,
    startDate: LocalDate,
    endDate: LocalDate,
    filterItems: List<String>,
    summaryUiState: ReconciliationTotals,
//    transactionMode: TransactionMode,
    showPrintDialog: Boolean,
    showCustomDateRangePicker: Boolean,
    defaultPrintTransactions: Boolean,
    onBack: (() -> Unit)?,
    onFilterSelected: (String) -> Unit,
    onShowPrintDialog: () -> Unit,
    onDismissPrintDialog: () -> Unit,
    onDismissCustomDateRangePicker: () -> Unit,
    onApplyCustomDateRange: (LocalDate, LocalDate) -> Unit,
    onPrintReport: (LocalDate, LocalDate, Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            if (title != null) {
                TopBar(
                    title = {
                        Text(
                            text = title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = onBack?.let {
                        {
                            BackButton(
                                onBackPressed = it,
                            )
                        }
                    },
                    actions = {},
                )
            } else {
                Box(
                    modifier = Modifier
                        .height(24.dp)
                        .background(color_white)
                        .fillMaxSize(),
                ) {}
            }
        },
    ) { contentPadding ->
        Column(
            modifier
                .padding(contentPadding)
                .fillMaxWidth(1f)
                .background(color_secondaryThree)
                .fillMaxSize(),
        ) {
            Column(
                Modifier
                    .padding(8.dp, 16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxSize(),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp)) {
                    Text(
                        fontSize = 18.sp,
                        text = if (startDate.isEqual(endDate)) {
                            stringResource(
                                id = R.string.report_window_one_day,
                                startDate.format(monthDayFormatter),
                            )
                        } else {
                            stringResource(
                                id = R.string.report_window_range,
                                startDate.format(monthDayFormatter),
                                endDate.format(monthDayFormatter),
                            )
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )

                    var expanded by remember { mutableStateOf(false) }

                    IconButton(
                        onClick = { expanded = !expanded },
                    ) {
                        Icon(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            imageVector = if (!expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Filter",
                        )
                    }

                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        //val customString = stringResource(id = R.string.filter_custom)
                        filterItems.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = {
                                expanded = false
                                onFilterSelected(item)
                            })
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = onShowPrintDialog,
                        Modifier
                            .border(1.dp, color_secondaryThree, shape = RoundedCornerShape(8.dp))
                            .height(35.dp)
                            .align(Alignment.CenterVertically),
                    ) {
                        Text(text = stringResource(id = R.string.report_export), color = color_black)
                    }
                }

                if (showCustomDateRangePicker) {
                    ReportFilterDialog(
                        updatePrintOptions = { startDateValue: LocalDate, endDateValue: LocalDate, _: Boolean ->
                            if (!startDateValue.isAfter(endDateValue)) {
                                onApplyCustomDateRange(startDateValue, endDateValue)
                            }
                            onDismissCustomDateRangePicker()
                        },
                        startDate = startDate,
                        endDate = endDate,
                        closeDialog = onDismissCustomDateRangePicker,
                        isPrintDialog = false,
                    )
                }

                if (showPrintDialog) {
                    ReportFilterDialog(
                        updatePrintOptions = { startDateValue: LocalDate, endDateValue: LocalDate, printTransactions: Boolean ->
                            if (!startDateValue.isAfter(endDateValue)) {
                                onPrintReport(startDateValue, endDateValue, printTransactions)
                            } else {
                                onDismissPrintDialog()
                            }
                        },
                        startDate = startDate,
                        endDate = endDate,
                        closeDialog = onDismissPrintDialog,
                        isPrintDialog = true,
                        initialPrintTransactions = defaultPrintTransactions,
                    )
                }

                Text(
                    text = stringResource(
                        id = R.string.summary_completed_sales_total_with_arg,
                        summaryUiState.creditSales.amount.toString(),
                    ),
                    fontSize = 28.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )

                Spacer(Modifier.height(16.dp))

                SettlementTotalsRow(
                    labelRes = R.string.summary_sales,
                    metric = summaryUiState.creditSales,
                    currencySymbol = ""
                )
                SettlementTotalsRow(
                    labelRes = R.string.summary_refunds,
                    metric = summaryUiState.refunds,
                    currencySymbol = ""
                )
                SettlementTotalsRow(
                    labelRes = R.string.summary_void,
                    metric = summaryUiState.voidedSales,
                    currencySymbol = ""
                )
                SettlementTotalsRow(
                    labelRes = R.string.summary_open_auth_number,
                    metric = summaryUiState.authorizations,
                    currencySymbol = ""
                )
                SettlementTotalsRow(
                    labelRes = R.string.summary_tip_total,
                    metric = summaryUiState.tip,
                    currencySymbol = ""
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReportsScreenRestaurantPreview() {
    ReportsScreenContent(
        title = "Reports",
        startDate = LocalDate.now().minusDays(1),
        endDate = LocalDate.now(),
        filterItems = listOf("Today", "Last Week", "Last Month", "Custom"),
        summaryUiState = RecordsPreviewData.summaryUiState,
        //transactionMode = TransactionMode.Restaurant,
        showPrintDialog = false,
        showCustomDateRangePicker = false,
        defaultPrintTransactions = true,
        onBack = {},
        onFilterSelected = {},
        onShowPrintDialog = {},
        onDismissPrintDialog = {},
        onDismissCustomDateRangePicker = {},
        onApplyCustomDateRange = { _, _ -> },
        onPrintReport = { _, _, _ -> },
    )
}

@Composable
fun ReportFilterDialog(
    updatePrintOptions: (LocalDate, LocalDate, Boolean) -> Unit,
    startDate: LocalDate,
    endDate: LocalDate,
    closeDialog: () -> Unit,
    isPrintDialog: Boolean,
    modifier: Modifier = Modifier,
    initialPrintTransactions: Boolean = false,
) {
    var startDateFilter by remember { mutableStateOf(startDate) }
    var endDateFilter by remember { mutableStateOf(endDate) }

    val options = mapOf(
        stringResource(id = R.string.filter_today) to Pair<LocalDate, LocalDate>(LocalDate.now(), LocalDate.now()),
        stringResource(id = R.string.filter_last_week) to Pair<LocalDate, LocalDate>(LocalDate.now().minusDays(7), LocalDate.now()),
        stringResource(id = R.string.filter_last_month) to Pair<LocalDate, LocalDate>(LocalDate.now().minusDays(30), LocalDate.now()),
        stringResource(id = R.string.filter_last_three_month) to Pair<LocalDate, LocalDate>(
            LocalDate.now().minusMonths(3),
            LocalDate.now()
        ),
    )

    val optionsReversed = mapOf(
        Pair<LocalDate, LocalDate>(LocalDate.now(), LocalDate.now()) to stringResource(id = R.string.filter_today),
        Pair<LocalDate, LocalDate>(LocalDate.now().minusDays(7), LocalDate.now()) to stringResource(id = R.string.filter_last_week),
        Pair<LocalDate, LocalDate>(LocalDate.now().minusDays(30), LocalDate.now()) to stringResource(id = R.string.filter_last_month),
        Pair<LocalDate, LocalDate>(LocalDate.now().minusMonths(3), LocalDate.now()) to stringResource(id = R.string.filter_last_three_month),
    )


    var selectedOption by remember {
        mutableStateOf(
            optionsReversed[Pair(
                startDateFilter,
                endDateFilter
            )]
        )
    }

    var printTransactions by rememberSaveable { mutableStateOf(initialPrintTransactions) }

    Dialog(
        onDismissRequest = closeDialog,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 20.dp)
        ) {
            Column(Modifier.fillMaxSize(1f)) {
                ReportDateDialogHeader(
                    applyFilter = { updatePrintOptions(startDateFilter, endDateFilter, printTransactions) },
                    closeDialog = closeDialog,
                    isPrintDialog = isPrintDialog
                )

                Text(
                    text = stringResource(id = R.string.report_window_options),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                DatePickerTextField(
                    date = startDateFilter,
                    label = stringResource(id = R.string.start_date),
                    setDate = { milliseconds: Long ->
                        startDateFilter = LocalDate.parse(utcToLocal(milliseconds), dateFormatter)
                        selectedOption = null
                    },
                    modifier = Modifier
                        .width(325.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                )

                DatePickerTextField(
                    date = endDateFilter,
                    label = stringResource(id = R.string.end_date),
                    setDate = { milliseconds: Long ->
                        endDateFilter = LocalDate.parse(utcToLocal(milliseconds), dateFormatter)
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
                                startDateFilter = options[key]?.first ?: LocalDate.now()
                                endDateFilter = options[key]?.second ?: LocalDate.now()
                                selectedOption = key
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor,
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

                if (isPrintDialog) {
                    Text(
                        text = stringResource(id = R.string.report_window_options_transactions),
                        modifier = Modifier.padding(start = 24.dp, top = 32.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier
                            .fillMaxWidth(1f)
                            .height(ROWHEIGHT.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.report_window_options_print_transactions),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(start = 16.dp)
                        )

                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = printTransactions,
                            onCheckedChange = {
                                printTransactions = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = color_primaryBrand,
                                uncheckedTrackColor = color_primaryBrand.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .align(Alignment.CenterVertically),
                            thumbContent = if (printTransactions) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReportFilterDialogPreview() {
    ReportFilterDialog(
        updatePrintOptions = { _, _, _ -> },
        startDate = LocalDate.now().minusDays(7),
        endDate = LocalDate.now(),
        closeDialog = {},
        isPrintDialog = true,
        initialPrintTransactions = true,
    )
}

@Composable
fun ReportDateDialogHeader(
    applyFilter: () -> Unit,
    closeDialog: () -> Unit,
    isPrintDialog: Boolean
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
            text = if (isPrintDialog) stringResource(id = R.string.report_window_options_print_options) else stringResource(
                id = R.string.filter_options
            ),
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
                text = if (isPrintDialog) stringResource(id = R.string.report_window_start_print) else stringResource(
                    id = R.string.apply_filters
                ),
                fontWeight = FontWeight.SemiBold,
                color = color_black
            )
        }
    }
}
