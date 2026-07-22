package one.globalconnect.paymentapp.settlement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.transaction.ProcessingStatusList
import one.globalconnect.paymentapp.transaction.SettlementProcessingState
import one.globalconnect.paymentapp.transaction.SettlementResultsUiState
import one.globalconnect.paymentapp.transaction.isResultFinal
import one.globalconnect.paymentapp.ui.theme.color_alert
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.utils.FormatterUtils

@Composable
fun SettlementProcessingDialog(
    state: SettlementProcessingState,
    onDismiss: () -> Unit,
) {
    val isResultFinal = state.processingStatus.isResultFinal()
    Dialog(
        onDismissRequest = {
            if (isResultFinal) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = isResultFinal,
            dismissOnClickOutside = isResultFinal,
        ),
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 320.dp, max = 360.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isResultFinal) { onDismiss() }
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.settlement_processing_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.settlement_processing_acquirer,
                        state.acquirerName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProcessingStatusList(
                    status = state.processingStatus,
                    modifier = Modifier.fillMaxWidth(),
                    textColor = MaterialTheme.colorScheme.onSurface,
                    secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isResultFinal) {
                    state.results?.let { resultsState ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settlement_results_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SettlementResultsContent(
                            state = resultsState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = stringResource(R.string.processing_status_tap_to_continue),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettlementResultsContent(
    state: SettlementResultsUiState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is SettlementResultsUiState.Message -> {
            Text(
                text = state.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = modifier,
            )
        }
        is SettlementResultsUiState.Summary -> {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.results.forEach { result ->
                    SettlementResultRow(
                        result = result,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettlementResultRow(
    result: SettlementResult,
    modifier: Modifier = Modifier,
) {
    val displayName = result.acquirerName.ifBlank { result.acquirerId }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = when (result) {
            is SettlementResult.Success -> Icons.Filled.CheckCircleOutline to color_primaryBrand
            is SettlementResult.Failure -> Icons.Filled.ErrorOutline to color_alert
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when (result) {
                is SettlementResult.Success -> {
                    Text(
                        text = stringResource(
                            R.string.settlement_results_transactions,
                            result.transactionCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.settlement_results_total,
                            FormatterUtils.formatAmount(result.currencySymbol, result.totalAmount),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.settlement_results_response,
                            result.responseCode,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SettlementResult.Failure -> {
                    Text(
                        text = stringResource(
                            R.string.settlement_results_reason,
                            result.reason,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
