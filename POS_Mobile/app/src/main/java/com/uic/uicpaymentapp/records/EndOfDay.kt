package com.uic.uicpaymentapp.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.settlement.SettlementAcquirerOption
import com.uic.uicpaymentapp.settlement.SettlementRequest
import com.uic.uicpaymentapp.ui.preview.RecordsPreviewData
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.uicpos.pos.model.ReconciliationMetric
import com.uic.uicpaymentapp.uicpos.pos.model.ReconciliationTotalKind
import com.uic.uicpaymentapp.uicpos.pos.model.ReconciliationTotals
import com.uic.uicpaymentapp.utils.FormatterUtils

@Composable
fun EndOfDay(
    onStartSettlement: (SettlementRequest) -> Unit,
) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: EndOfDayViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()

    EndOfDayContent(
        uiState = uiState,
        onSelectAcquirer = viewModel::selectAcquirer,
        onStartSettlement = {
            viewModel.buildSettlementRequest()?.let(onStartSettlement)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndOfDayContent(
    uiState: EndOfDayUiState,
    onSelectAcquirer: (String) -> Unit,
    onStartSettlement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val acquirerLabel = stringResource(id = R.string.settlement_select_acquirer)
    val allLabel = stringResource(id = R.string.settlement_option_all_acquirers)
    val noAcquirersMessage = stringResource(id = R.string.settlement_no_acquirers)

    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.end_of_day),
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = null,
                actions = null,
                modifier = Modifier
                    .height(60.dp)
                    .padding(top = 20.dp),
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
                .background(color_secondaryThree)
                .verticalScroll(scrollState)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .background(color_white, shape = RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (uiState.acquirerOptions.isEmpty()) {
                    Text(
                        text = noAcquirersMessage,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    SettlementAcquirerDropdown(
                        options = uiState.acquirerOptions,
                        selectedId = uiState.selectedAcquirerId,
                        label = acquirerLabel,
                        allLabel = allLabel,
                        onSelect = onSelectAcquirer,
                    )

                    SettlementTotalsCard(
                        totals = uiState.totals,
                        currencySymbol = uiState.selectedCurrencySymbol,
                    )

                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        onClick = onStartSettlement,
                        enabled = uiState.hasTransactions,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color_primaryBrand,
                            contentColor = color_black,
                            disabledContainerColor = color_primaryBrand.copy(alpha = 0.3f),
                            disabledContentColor = color_black.copy(alpha = 0.3f),
                        ),
                    ) {
                        Text(text = stringResource(id = R.string.settlement_start_button))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettlementAcquirerDropdown(
    options: List<SettlementAcquirerOption>,
    selectedId: String,
    label: String,
    allLabel: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.id == selectedId }
    val displayText = when {
        selectedOption == null -> ""
        selectedOption.isAll -> allLabel
        else -> selectedOption.name
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(text = label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = OutlinedTextFieldDefaults.colors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                val optionLabel = if (option.isAll) allLabel else option.name
                DropdownMenuItem(
                    text = { Text(text = optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun SettlementTotalsCard(
    totals: ReconciliationTotals,
    currencySymbol: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(id = R.string.settlement_totals_header),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        val entriesWithTransactions = settlementTotalsEntries(totals)
            .filter { entry -> entry.metric.count > 0 }

        if (entriesWithTransactions.isEmpty()) {
            Text(
                text = stringResource(id = R.string.settlement_totals_empty),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        } else {
            entriesWithTransactions.forEach { entry ->
                SettlementTotalsRow(
                    labelRes = labelResFor(entry.kind),
                    metric = entry.metric,
                    currencySymbol = currencySymbol,
                    indentationLevel = entry.indentationLevel,
                )
            }
        }
    }
}

@Composable
fun SettlementTotalsRow(
    labelRes: Int,
    metric: ReconciliationMetric,
    currencySymbol: String,
    indentationLevel: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = labelRes),
            modifier = Modifier
                .weight(1f)
                .padding(start = (indentationLevel * 16).dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = metric.count.toString(),
            modifier = Modifier.widthIn(min = 48.dp),
            textAlign = TextAlign.End,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = FormatterUtils.formatAmount(currencySymbol, metric.amount),
            modifier = Modifier
                .widthIn(min = 112.dp)
                .padding(start = 16.dp),
            textAlign = TextAlign.End,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}

private data class SettlementTotalsEntry(
    val kind: ReconciliationTotalKind,
    val metric: ReconciliationMetric,
    val indentationLevel: Int = 0,
)

private fun settlementTotalsEntries(totals: ReconciliationTotals): List<SettlementTotalsEntry> = listOf(
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.CREDIT_SALES,
        metric = totals.creditSales,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.TAX1,
        metric = totals.tax1,
        indentationLevel = 1,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.TAX1DISCOUNT,
        metric = totals.tax1Discount,
        indentationLevel = 1,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.TAX2,
        metric = totals.tax2,
        indentationLevel = 1,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.TIP,
        metric = totals.tip,
        indentationLevel = 1,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.REFUNDS,
        metric = totals.refunds,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.CASH,
        metric = totals.cash,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.DEBIT_SALES,
        metric = totals.debitSales,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.DEBIT_REFUNDS,
        metric = totals.debitRefunds,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.AUTHORIZATIONS,
        metric = totals.authorizations,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.AUTHORIZATION_REFUNDS,
        metric = totals.authorizationRefunds,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.VOIDED_SALES,
        metric = totals.voidedSales,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.VOIDED_REFUNDS,
        metric = totals.voidedRefunds,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.PAYMENTS,
        metric = totals.payments,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.VOIDED_PAYMENTS,
        metric = totals.voidedPayments,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.LOYALTY,
        metric = totals.loyalty,
    ),
    SettlementTotalsEntry(
        kind = ReconciliationTotalKind.CASHBACK,
        metric = totals.cashback,
    ),
)

@Preview(showBackground = true)
@Composable
private fun EndOfDayPreview() {
    EndOfDayContent(
        uiState = RecordsPreviewData.settlementUiState,
        onSelectAcquirer = {},
        onStartSettlement = {},
    )
}

private fun labelResFor(kind: ReconciliationTotalKind): Int = when (kind) {
    ReconciliationTotalKind.CREDIT_SALES -> R.string.settlement_totals_credit_sales
    ReconciliationTotalKind.TAX1 -> R.string.settlement_totals_tax1
    ReconciliationTotalKind.TAX1DISCOUNT -> R.string.settlement_totals_tax1_discount
    ReconciliationTotalKind.TAX2 -> R.string.settlement_totals_tax2
    ReconciliationTotalKind.TIP -> R.string.settlement_totals_tip
    ReconciliationTotalKind.REFUNDS -> R.string.settlement_totals_refunds
    ReconciliationTotalKind.CASH -> R.string.settlement_totals_cash
    ReconciliationTotalKind.DEBIT_SALES -> R.string.settlement_totals_debit_sales
    ReconciliationTotalKind.DEBIT_REFUNDS -> R.string.settlement_totals_debit_refunds
    ReconciliationTotalKind.AUTHORIZATIONS -> R.string.settlement_totals_authorizations
    ReconciliationTotalKind.AUTHORIZATION_REFUNDS -> R.string.settlement_totals_authorization_refunds
    ReconciliationTotalKind.HYPERCOM_RESERVED_1 -> R.string.settlement_totals_reserved_one
    ReconciliationTotalKind.HYPERCOM_RESERVED_2 -> R.string.settlement_totals_reserved_two
    ReconciliationTotalKind.VOIDED_SALES -> R.string.settlement_totals_voided_sales
    ReconciliationTotalKind.VOIDED_REFUNDS -> R.string.settlement_totals_voided_refunds
    ReconciliationTotalKind.PAYMENTS -> R.string.settlement_totals_payments
    ReconciliationTotalKind.VOIDED_PAYMENTS -> R.string.settlement_totals_voided_payments
    ReconciliationTotalKind.LOYALTY -> R.string.settlement_totals_loyalty
    ReconciliationTotalKind.CASHBACK -> R.string.settlement_totals_cashback
}
