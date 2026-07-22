package one.globalconnect.paymentapp.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.transaction.Numpad
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.uicpos.pos.host.BatchNumberProvider
import one.globalconnect.paymentapp.uicpos.pos.host.InvoiceNumberProvider
import one.globalconnect.paymentapp.uicpos.pos.host.StanProvider
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager

private sealed class CounterEditTarget {
    object Stan : CounterEditTarget()
    object Invoice : CounterEditTarget()
    data class Batch(val acqId: String, val label: String) : CounterEditTarget()
}

@Composable
fun TerminalCountersScreen(onBack: () -> Unit) {
    val acquirers = remember { GlobalConnectPaymentApplication.instance.tmsDatabase.Acquirer }

    var stanDisplay by remember { mutableStateOf(StanProvider.currentStan()) }
    var invoiceDisplay by remember { mutableStateOf(InvoiceNumberProvider.currentInvoiceNumber()) }
    var batchDisplays by remember {
        mutableStateOf(acquirers.associate { it.AcqID to BatchNumberProvider.currentBatchNumber(it.AcqID, null) })
    }
    var editTarget by remember { mutableStateOf<CounterEditTarget?>(null) }

    Scaffold(
        topBar = { androidx.compose.foundation.layout.Box(Modifier.height(24.dp).background(color_white).fillMaxWidth()) },
        bottomBar = { androidx.compose.foundation.layout.Box(Modifier.height(8.dp).background(color_secondaryThree).fillMaxWidth()) }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .background(color_secondaryThree)
                .fillMaxHeight()
                .fillMaxWidth()
        ) {
            Row(Modifier.padding(8.dp, 24.dp)) {
                Button(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    onClick = {
                        SoundManager.play(SoundEffect.KEY_DELETE)
                        onBack()
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = color_primaryBrand, contentColor = color_secondaryFive)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.padding(start = 12.dp)
                        )
                        Text(
                            text = stringResource(R.string.terminal_counters),
                            fontSize = 28.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = color_white)
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        CounterRow(
                            label = stringResource(R.string.stan_label),
                            value = stanDisplay,
                            onEdit = { editTarget = CounterEditTarget.Stan }
                        )
                        HorizontalDivider()
                    }
                    item {
                        CounterRow(
                            label = stringResource(R.string.invoice_label),
                            value = invoiceDisplay,
                            onEdit = { editTarget = CounterEditTarget.Invoice }
                        )
                    }
                    if (acquirers.isNotEmpty()) {
                        item {
                            HorizontalDivider(thickness = 2.dp, color = color_secondaryThree)
                            Text(
                                text = stringResource(R.string.batch_numbers_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(acquirers) { acq ->
                            val label = acq.AcquirerName.ifBlank { acq.AcqID }
                            CounterRow(
                                label = label,
                                value = batchDisplays[acq.AcqID] ?: "000000",
                                onEdit = { editTarget = CounterEditTarget.Batch(acq.AcqID, label) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        val currentValue = when (target) {
            is CounterEditTarget.Stan -> stanDisplay
            is CounterEditTarget.Invoice -> invoiceDisplay
            is CounterEditTarget.Batch -> batchDisplays[target.acqId] ?: "000001"
        }
        val dialogTitle = when (target) {
            is CounterEditTarget.Stan -> stringResource(R.string.stan_label)
            is CounterEditTarget.Invoice -> stringResource(R.string.invoice_label)
            is CounterEditTarget.Batch -> "${target.label} — ${stringResource(R.string.batch)}"
        }
        CounterEditDialog(
            title = dialogTitle,
            initialValue = currentValue,
            onConfirm = { newValue ->
                when (target) {
                    is CounterEditTarget.Stan -> {
                        StanProvider.setStanValue(newValue)
                        stanDisplay = StanProvider.currentStan()
                    }
                    is CounterEditTarget.Invoice -> {
                        InvoiceNumberProvider.setInvoiceValue(newValue)
                        invoiceDisplay = InvoiceNumberProvider.currentInvoiceNumber()
                    }
                    is CounterEditTarget.Batch -> {
                        BatchNumberProvider.setBatchNumber(target.acqId, newValue)
                        batchDisplays = batchDisplays + (target.acqId to BatchNumberProvider.currentBatchNumber(target.acqId, null))
                    }
                }
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }
}

@Composable
private fun CounterRow(label: String, value: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Button(
            onClick = {
                SoundManager.play(SoundEffect.KEY_TICK)
                onEdit()
            },
            colors = ButtonDefaults.buttonColors(containerColor = color_primaryBrand, contentColor = color_secondaryFive),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(stringResource(R.string.edit), fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CounterEditDialog(
    title: String,
    initialValue: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var digits by remember { mutableStateOf(initialValue.trimStart('0').ifEmpty { "" }) }
    val display = digits.padStart(6, '0')
    val parsed = digits.toIntOrNull()
    val isValid = parsed != null && parsed in 1..999_999

    BasicAlertDialog(onDismissRequest = {}) {
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
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = display,
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                Numpad(
                    onSelected = { key ->
                        when (key) {
                            "C" -> digits = if (digits.length > 1) digits.dropLast(1) else ""
                            else -> if (digits.length < 6) digits += key
                        }
                    },
                    onEnterPressed = { if (isValid) onConfirm(parsed!!) },
                    onCancelPressed = onDismiss,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(stringResource(R.string.cancel), fontSize = 16.sp)
                    }
                    Button(
                        onClick = { if (isValid) onConfirm(parsed!!) },
                        enabled = isValid,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = color_primaryBrand, contentColor = color_secondaryFive),
                    ) {
                        Text(stringResource(R.string.save), fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
