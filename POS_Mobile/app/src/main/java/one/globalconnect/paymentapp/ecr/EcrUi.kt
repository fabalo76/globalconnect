package one.globalconnect.paymentapp.ecr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import one.globalconnect.paymentapp.R

@Composable fun EcrIdleScreen() {
    val settling by EcrSettlementInteraction.active.collectAsState()
    androidx.activity.compose.BackHandler(settling) { }
    val settlementResult by EcrSettlementInteraction.result.collectAsState()
    settlementResult?.let { state ->
        androidx.activity.compose.BackHandler { }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(if (state.success) Icons.Filled.CheckCircleOutline else Icons.Filled.ErrorOutline,
                contentDescription = null, modifier = Modifier.size(72.dp),
                tint = if (state.success) one.globalconnect.paymentapp.ui.theme.color_primaryBrand
                    else one.globalconnect.paymentapp.ui.theme.color_alert)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(if (state.success) R.string.ecr_settlement_success else R.string.ecr_settlement_failed),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            one.globalconnect.paymentapp.settlement.SettlementResultsContent(
                one.globalconnect.paymentapp.transaction.SettlementResultsUiState.Summary(state.results))
            state.detail?.let { Text(it, modifier = Modifier.padding(top = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        return
    }

    val rejection by EcrVoidInteraction.rejectionMessage.collectAsState()
    rejection?.let { message ->
        androidx.activity.compose.BackHandler { EcrVoidInteraction.back() }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.void_failed_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    val voidScreen by EcrVoidInteraction.screen.collectAsState()
    voidScreen?.let { state ->
        androidx.activity.compose.BackHandler { EcrVoidInteraction.back() }
        one.globalconnect.paymentapp.transaction.RefundScreen(
            transaction = state.transaction,
            onBackButtonPressed = { EcrVoidInteraction.back() },
            onRefundPressed = { EcrVoidInteraction.decide(true) },
            returnUiState = state.progress,
            ecrMode = true,
        )
        return
    }
    val context = LocalContext.current
    val settings by EcrRuntime.settings.collectAsState()
    val addresses by produceState<List<String>>(emptyList(), settings.enabled, settings.transport) {
        if (settings.enabled && settings.transport == "TCP/IP") {
            while (true) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                            .filter { it.isUp && !it.isLoopback }
                            .flatMap { it.inetAddresses.toList() }
                            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                            .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                            .mapNotNull { it.hostAddress }
                            .distinct()
                    }.getOrDefault(emptyList())
                }
                kotlinx.coroutines.delay(5_000)
            }
        }
    }
    val status by EcrRuntime.status.collectAsState()
    val operating by EcrRuntime.reporting.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.Center) {
        Text(stringResource(if (operating) R.string.msg_processing else R.string.ecr_ready), style=MaterialTheme.typography.headlineMedium)
        if (!operating) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = one.globalconnect.paymentapp.GlobalConnectPaymentApplication.serialNumber,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        val waiting = status == context.getString(R.string.ecr_waiting, settings.port)
        val displayStatus = if (waiting && settings.transport != "TCP/IP") stringResource(R.string.ecr_serial_waiting, settings.transport, settings.serialPort, settings.baudRate) else if (waiting && addresses.isNotEmpty()) {
            stringResource(R.string.ecr_waiting_endpoint, addresses.joinToString("\n") {
                if (':' in it) "[$it]:${settings.port}" else "$it:${settings.port}"
            })
        } else if (waiting) stringResource(R.string.ecr_waiting_no_address, settings.port) else status
        Text(displayStatus, style=MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable fun EcrConfiguration() {
    val context=LocalContext.current
    var config by remember { mutableStateOf(EcrSettings.read(context)) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var baudExpanded by remember { mutableStateOf(false) }
    var serialPort by remember { mutableStateOf(config.serialPort.toString()) }
    val editable = EcrRuntime.busy.not()
    val fixedUsbPort = if (config.transport == "USB") fixedEcrUsbPort(android.os.Build.MODEL) else null
    var message by remember { mutableStateOf("") }
    val active by EcrRuntime.sale.collectAsState()
    val reporting by EcrRuntime.reporting.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("ECR", style=MaterialTheme.typography.titleLarge)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.ecr_enable),Modifier.weight(1f))
            Switch(config.enabled,{config=config.copy(enabled=it)},enabled=active==null && !reporting)
        }
        Text(stringResource(R.string.ecr_connection_type), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            listOf("USB", "RS232", "TCP/IP").forEach { type ->
                Row(
                    Modifier.weight(1f).selectable(
                        selected = config.transport == type,
                        enabled = editable,
                        role = Role.RadioButton,
                        onClick = { config = config.copy(transport = type); message = "" }
                    ).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = config.transport == type, onClick = null, enabled = editable)
                    Text(type, modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (config.transport == "TCP/IP") {
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(stringResource(R.string.ecr_port)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        } else {
            if (fixedUsbPort != null) {
                Text(stringResource(R.string.ecr_fixed_usb_port, fixedUsbPort))
            } else OutlinedTextField(
                serialPort, { serialPort = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(stringResource(R.string.ecr_serial_port)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(stringResource(R.string.ecr_baud_rate), style = MaterialTheme.typography.bodyMedium)
            Box {
                OutlinedButton(onClick = { baudExpanded = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                    Text(config.baudRate.toString(), modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = baudExpanded, onDismissRequest = { baudExpanded = false }) {
                    listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200).forEach { baud ->
                        DropdownMenuItem(text = { Text(baud.toString()) }, onClick = {
                            config = config.copy(baudRate = baud)
                            baudExpanded = false
                        })
                    }
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.ecr_kiosk),Modifier.weight(1f))
            Switch(config.kiosk,{config=config.copy(kiosk=it)},enabled=active==null && !reporting)
        }
        Text(stringResource(R.string.ecr_unlock_help), style=MaterialTheme.typography.bodySmall)
        Button(modifier = Modifier.fillMaxWidth(), enabled = active == null && !reporting &&
            (if (config.transport == "TCP/IP") port.toIntOrNull() in 1024..65535 else fixedUsbPort != null || serialPort.toIntOrNull() in 0..255), onClick = {
            runCatching {
                config = config.copy(
                    port = if (config.transport == "TCP/IP") port.toInt() else config.port,
                    serialPort = fixedUsbPort ?: if (config.transport != "TCP/IP") serialPort.toInt() else config.serialPort
                )
                config.save(context)
            }
                .onSuccess { message=context.getString(R.string.ecr_saved) }
                .onFailure { message=context.getString(R.string.ecr_save_failed) }
        }) { Text(stringResource(R.string.ecr_save)) }
        if(message.isNotEmpty()) Text(message)
    }
}
