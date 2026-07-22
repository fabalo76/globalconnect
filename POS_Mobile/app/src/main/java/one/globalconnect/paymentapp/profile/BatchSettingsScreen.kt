package one.globalconnect.paymentapp.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.records.BackButton
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white

@Composable
fun BatchSettingsScreen(
    onBackPressed: () -> Unit,
    previousScreen: String,
) {

    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: SystemSettingsViewModel = viewModel(factory = viewModelFactory)

    var editEnabled by rememberSaveable { mutableStateOf(false) }
    val systemUiState by viewModel.systemUiState.collectAsState(initial = SystemUiState())
    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        fontSize = 16.sp,
                        text = stringResource(id = R.string.batching_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    BackButton(
                        onBackPressed = onBackPressed,
                        text = previousScreen
                    )
                },
                actions = {
                    if (!editEnabled) {
                        TextButton(
                            modifier = Modifier.padding(16.dp),
                            onClick = { editEnabled = true }) {
                            Text(text = stringResource(id = R.string.edit), fontSize = 16.sp)
                        }
                    } else {
                        TextButton(modifier = Modifier.padding(16.dp), onClick = {
                            viewModel.writeToDb()
                            editEnabled = false
                        }
                        ) {
                            Text(text = stringResource(id = R.string.save), fontSize = 16.sp)
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .padding(contentPadding)
                .fillMaxSize(1f)
                .background(color_secondaryThree)
        ) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxSize()
            ){
                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRowSwitch(
                        onClick = { string ->
                            viewModel.saveSystemSetting(
                                SystemSettingType.BatchPrint,
                                string
                            )
                        },
                        text = stringResource(id = R.string.auto_print_batch_report),
                        checked = systemUiState.batchPrint.text.toBoolean(),
                        enabled = editEnabled
                    )
                }

                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRowSwitch(
                        onClick = { string ->
                            viewModel.saveSystemSetting(
                                SystemSettingType.DetailedBatchReport,
                                string
                            )
                        },
                        text = stringResource(id = R.string.print_transactions_report),
                        checked = systemUiState.detailedBatchReport.text.toBoolean(),
                        enabled = editEnabled
                    )
                }
            }

        }

    }
}