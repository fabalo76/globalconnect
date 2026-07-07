package com.uic.uicpaymentapp.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.records.dateTimeFormatter
import com.uic.uicpaymentapp.records.dateTimeFormatterForUsers
import com.uic.uicpaymentapp.settlement.storage.SettlementSnapshot
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.utils.FormatterUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReprintSettlementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReprintSettlementViewModel = viewModel(
        factory = AppViewModelProvider.provideFactory(LocalContext.current)
    ),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val selectedOption = uiState.selectedOption
    val snapshot = selectedOption?.snapshot

    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.settlement_reprint_title),
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.back),
                        )
                    }
                },
                actions = null,
                modifier = Modifier
                    .height(60.dp)
                    .padding(top = 20.dp),
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(color_secondaryThree)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.options.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.settlement_reprint_no_settlements),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                ReprintSettlementDropdown(
                    options = uiState.options,
                    selectedId = uiState.selectedAcquirerId,
                    onSelect = viewModel::selectAcquirer,
                    label = stringResource(id = R.string.settlement_reprint_select_acquirer),
                )

                if (selectedOption?.pending == true) {
                    Text(
                        text = stringResource(id = R.string.settlement_reprint_pending_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (snapshot != null) {
                    ReprintSettlementDetails(
                        snapshot = snapshot,
                        currencySymbol = selectedOption.currencySymbol,
                    )
                } else {
                    Text(
                        text = stringResource(id = R.string.settlement_reprint_no_settlements),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(modifier = Modifier.weight(1f, fill = true))

                Button(
                    onClick = { viewModel.print(context) },
                    enabled = snapshot != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_primaryBrand,
                        contentColor = color_black,
                        disabledContainerColor = color_primaryBrand.copy(alpha = 0.3f),
                        disabledContentColor = color_black.copy(alpha = 0.3f),
                    ),
                ) {
                    Text(text = stringResource(id = R.string.settlement_reprint_print_button))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReprintSettlementDropdown(
    options: List<ReprintSettlementOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id == selectedId }
    val displayText = selectedOption?.name.takeIf { !it.isNullOrBlank() } ?: selectedOption?.id.orEmpty()

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
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
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
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(option.name.ifBlank { option.id }) },
                    onClick = {
                        onSelect(option.id)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun ReprintSettlementDetails(
    snapshot: SettlementSnapshot,
    currencySymbol: String,
    modifier: Modifier = Modifier,
) {
    val completedAt = remember(snapshot.completedAt) {
        runCatching { LocalDateTime.parse(snapshot.completedAt, dateTimeFormatter) }.getOrNull()
    }
    val formattedCompletedAt = completedAt?.format(dateTimeFormatterForUsers) ?: snapshot.completedAt
    val totalAmount = remember(snapshot) { calculateTotalAmount(snapshot) }
    val formattedAmount = FormatterUtils.formatAmount(currencySymbol, totalAmount)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color_white, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(id = R.string.settlement_reprint_completed_at, formattedCompletedAt),
            style = MaterialTheme.typography.bodyMedium,
        )
        snapshot.batchNumber?.takeIf { it.isNotBlank() }?.let { batch ->
            Text(
                text = stringResource(id = R.string.settlement_receipt_batch_label) + ": " + batch,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        snapshot.merchantId?.takeIf { it.isNotBlank() }?.let { merchant ->
            Text(
                text = stringResource(id = R.string.settlement_receipt_merchant_label) + ": " + merchant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        snapshot.terminalId?.takeIf { it.isNotBlank() }?.let { terminal ->
            Text(
                text = stringResource(id = R.string.settlement_receipt_terminal_label) + ": " + terminal,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        snapshot.responseCode.takeIf { it.isNotBlank() }?.let { code ->
            Text(
                text = stringResource(id = R.string.settlement_receipt_response_code, code),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(id = R.string.settlement_reprint_transactions, snapshot.transactions.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(id = R.string.settlement_reprint_total_amount, formattedAmount),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun calculateTotalAmount(snapshot: SettlementSnapshot): BigDecimal {
    return snapshot.transactions.fold(BigDecimal.ZERO) { acc, transaction ->
        val value = transaction.totalAmount.replace(",", "").trim().toBigDecimalOrNull()
            ?: BigDecimal.ZERO
        acc.add(value)
    }.setScale(2, RoundingMode.HALF_UP)
}
