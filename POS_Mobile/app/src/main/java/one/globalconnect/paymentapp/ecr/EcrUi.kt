package one.globalconnect.paymentapp.ecr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import one.globalconnect.paymentapp.R

@Composable fun EcrIdleScreen() {
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
    val status by EcrRuntime.status.collectAsState()
    val operating by EcrRuntime.reporting.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.Center) {
        Text(stringResource(if (operating) R.string.msg_processing else R.string.ecr_ready), style=MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Text(status, style=MaterialTheme.typography.bodyLarge)
    }
}

@Composable fun EcrConfiguration() {
    val context=LocalContext.current
    var config by remember { mutableStateOf(EcrSettings.read(context)) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val active by EcrRuntime.sale.collectAsState()
    val reporting by EcrRuntime.reporting.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("ECR", style=MaterialTheme.typography.titleLarge)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.ecr_enable),Modifier.weight(1f))
            Switch(config.enabled,{config=config.copy(enabled=it)},enabled=active==null && !reporting)
        }
        TextButton(onClick={expanded=true}) { Text(config.transport) }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            listOf("TCP/IP","USB","RS232").forEach { type ->
                DropdownMenuItem(text={Text(type)},onClick={config=config.copy(transport=type);expanded=false})
            }
        }
        if(config.transport!="TCP/IP") Text(stringResource(R.string.ecr_serial_pending))
        OutlinedTextField(port,{port=it.filter(Char::isDigit).take(5)},label={Text(stringResource(R.string.ecr_port))},singleLine=true)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.ecr_kiosk),Modifier.weight(1f))
            Switch(config.kiosk,{config=config.copy(kiosk=it)},enabled=active==null && !reporting)
        }
        Text(stringResource(R.string.ecr_unlock_help), style=MaterialTheme.typography.bodySmall)
        Button(enabled=active==null && !reporting && port.toIntOrNull() in 1024..65535 && (!config.enabled || config.transport=="TCP/IP"),onClick={
            runCatching { config.copy(port=port.toInt()).save(context) }
                .onSuccess { message=context.getString(R.string.ecr_saved) }
                .onFailure { message=context.getString(R.string.ecr_save_failed) }
        }) { Text(stringResource(R.string.ecr_save)) }
        if(message.isNotEmpty()) Text(message)
    }
}
