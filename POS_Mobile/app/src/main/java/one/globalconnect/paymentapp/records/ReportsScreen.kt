package one.globalconnect.paymentapp.records

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.ui.preview.RecordsPreviewData
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager

/**
 * Displays totals for the complete current transaction batch.
 *
 * [printTransactions] selects the receipt format only: the detailed report prints every
 * transaction before the totals, while the totals report prints the aggregate section. Both use
 * the same unfiltered batch held by [ReportViewModel].
 */
@Composable
fun ReportsScreen(
    @StringRes titleResId: Int? = null,
    onBack: (() -> Unit)? = null,
    printTransactions: Boolean = false,
) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: ReportViewModel = viewModel(factory = viewModelFactory)
    val summaryUiState by viewModel.summaryUiState.collectAsState(ReconciliationTotals())

    ReportsScreenContent(
        title = titleResId?.let { stringResource(id = it) },
        summaryUiState = summaryUiState,
        onBack = onBack?.let {
            {
                SoundManager.play(SoundEffect.KEY_DELETE)
                it()
            }
        },
        onPrint = {
            SoundManager.play(SoundEffect.KEY_TICK)
            viewModel.printReport(context, printTransactions)
        },
    )
}

@Composable
private fun ReportsScreenContent(
    title: String?,
    summaryUiState: ReconciliationTotals,
    onBack: (() -> Unit)?,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier,
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
                        { BackButton(onBackPressed = it) }
                    },
                    actions = {},
                )
            } else {
                Box(
                    modifier = Modifier
                        .height(24.dp)
                        .background(color_white)
                        .fillMaxSize(),
                )
            }
        },
    ) { contentPadding ->
        Column(
            modifier
                .padding(contentPadding)
                .fillMaxSize()
                .background(color_secondaryThree),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState())
                    .fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(id = R.string.report_current_batch),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = onPrint,
                        modifier = Modifier
                            .border(1.dp, color_primaryBrand, shape = RoundedCornerShape(8.dp))
                            .height(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Print,
                            contentDescription = null,
                            tint = color_black,
                        )
                        Text(
                            text = stringResource(id = R.string.print),
                            color = color_black,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(
                        id = R.string.summary_completed_sales_total_with_arg,
                        summaryUiState.creditSales.amount.toString(),
                    ),
                    fontSize = 28.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                )

                SettlementTotalsRow(
                    labelRes = R.string.summary_sales,
                    metric = summaryUiState.creditSales,
                    currencySymbol = "",
                )
                SettlementTotalsRow(
                    labelRes = R.string.summary_refunds,
                    metric = summaryUiState.refunds,
                    currencySymbol = "",
                )
                SettlementTotalsRow(
                    labelRes = R.string.report_totals_cash,
                    metric = summaryUiState.cash,
                    currencySymbol = "",
                )
                SettlementTotalsRow(
                    labelRes = R.string.report_totals_payments,
                    metric = summaryUiState.payments,
                    currencySymbol = "",
                )
                SettlementTotalsRow(
                    labelRes = R.string.report_totals_void_sales,
                    metric = summaryUiState.voidedSales,
                    currencySymbol = "",
                )
                SettlementTotalsRow(
                    labelRes = R.string.report_totals_void_refunds,
                    metric = summaryUiState.voidedRefunds,
                    currencySymbol = "",
                )
                SettlementTotalsRow(
                    labelRes = R.string.report_totals_void_payments,
                    metric = summaryUiState.voidedPayments,
                    currencySymbol = "",
                )
                SettlementTotalsRow(
                    labelRes = R.string.summary_open_auth_number,
                    metric = summaryUiState.authorizations,
                    currencySymbol = "",
                )
                SettlementTotalsRow(
                    labelRes = R.string.summary_tip_total,
                    metric = summaryUiState.tip,
                    currencySymbol = "",
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReportsScreenPreview() {
    ReportsScreenContent(
        title = "Totals Report",
        summaryUiState = RecordsPreviewData.summaryUiState,
        onBack = {},
        onPrint = {},
    )
}
