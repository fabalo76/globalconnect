package one.globalconnect.keyinjection.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.keyinjection.App
import one.globalconnect.keyinjection.R
import one.globalconnect.keyinjection.protocol.EPedKeyType
import one.globalconnect.keyinjection.util.FontSize
import one.globalconnect.keyinjection.util.Logger
import one.globalconnect.keyinjection.util.PedProxy
import one.globalconnect.keyinjection.util.Printer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Screen that displays the status of keys loaded in the PED.
 * It queries the device when first shown and lists the KCV
 * for each key or notes if the key is missing.
 *
 * @param onClose invoked when the Close button is pressed
 * @param onPrint invoked when the Print button is pressed
 */
@Composable
fun KeyStatusScreen(onClose: () -> Unit, onPrint: () -> Unit) {
    val tag = "KeyStatusScreen"
    val logs = remember { mutableStateListOf<String>() }
    val isLoading = remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        isLoading.value = true
        try {
            if (PedProxy.Init()) {
                val tlk = PedProxy.checkKey(EPedKeyType.TLK, 0)
                val first = if (tlk != null) {
                    App.instance.getString(R.string.key_status_loaded, "TLK", 0, tlk)
                } else {
                    App.instance.getString(R.string.key_status_not_loaded, "TLK", 1)
                }
                Logger.i(tag, first)
                logs.add(first)

                for (i in 1..10) {
                    val kcv = PedProxy.checkKey(EPedKeyType.TMK, i)
                    if (kcv != null) {
                        val msg = App.instance.getString(R.string.key_status_loaded, "TMK", i, kcv)
                        Logger.i(tag, msg)
                        logs.add(msg)
                        val tpkKcv = PedProxy.checkKey(EPedKeyType.TPK, i)
                        if (tpkKcv != null) {
                            val msg = "  " + App.instance.getString(R.string.key_status_loaded, "TPK", i, tpkKcv)
                            Logger.i(tag, msg)
                            logs.add(msg)
                        }
                        val takKcv = PedProxy.checkKey(EPedKeyType.TAK, i)
                        if (takKcv != null) {
                            val msg = "  " + App.instance.getString(R.string.key_status_loaded, "TAK", i, takKcv)
                            Logger.i(tag, msg)
                            logs.add(msg)
                        }
                        val tdkKcv = PedProxy.checkKey(EPedKeyType.TDK, i)
                        if (tdkKcv != null) {
                            val msg = "  " + App.instance.getString(R.string.key_status_loaded, "TDK", i, tdkKcv)
                            Logger.i(tag, msg)
                            logs.add(msg)
                        }
                    }
                }
                for (i in 1..10) {
                    val tikKcv = PedProxy.checkKey(EPedKeyType.TIK, i)
                    if (tikKcv != null) {
                        val msg = App.instance.getString(
                            R.string.key_status_loaded,
                            "TIK",
                            i,
                            tikKcv
                        )
                        Logger.i(tag, msg)
                        logs.add(msg)
                    }
                }
            } else {
                val msg = App.instance.getString(R.string.pinpad_init_failed)
                Logger.i(tag, msg)
                logs.add(msg)
            }
        } finally {
            isLoading.value = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBanner()
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .border(1.dp, Color.Black)
                    .padding(8.dp),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { entry ->
                        Text(entry)
                    }
                }

                if (isLoading.value) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.key_status_loading_message))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlobalConnectButton(
                    onClick = onClose,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    enabled = !isLoading.value
                ) {
                    Text(stringResource(R.string.close), fontSize = 20.sp)
                }
                if (!App.model.equals("IM30", ignoreCase = true)) {
                    GlobalConnectButton(
                        onClick = {
                            scope.launch {
                                    // Prep work off the main thread
                                    withContext(Dispatchers.IO) {
                                        Printer.init()

                                        val title1 = App.instance.getString(R.string.key_status_information_L1).trim()
                                        val title2 = App.instance.getString(R.string.key_status_information_L2).trim()
                                        Printer.printCentered(title1, FontSize.LARGE)
                                        if (title2.isNotEmpty()) Printer.printCentered(title2, FontSize.LARGE)

                                        val toPrint = buildList {
                                            add("${App.instance.getString(R.string.model)}: ${App.model}")
                                            add("${App.instance.getString(R.string.serial_number)}: ${App.serialNumber}")
                                            // If logs is List<LogEntry>, map to text; if it's already List<String>, just addAll(logs)
                                            addAll(logs.map { it.toString() }) // or: logs.map { it.text }
                                        }
                                        Printer.printLines(toPrint, FontSize.MEDIUM)
                                    }
                                    // Now call the suspend function from the coroutine
                                    val status = Printer.start()
                                    Logger.i(tag, "Print status: $status")
                                    // Navigate/finish after printing completes:
                                    onPrint()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                        enabled = !isLoading.value
                    ) {
                        Text(stringResource(R.string.print), fontSize = 20.sp)
                    }
                }
            }
        }
    }
}
