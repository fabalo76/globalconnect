package com.uic.uicpaymentapp.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.records.BackButton
import com.uic.uicpaymentapp.ui.theme.color_grey95
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.uicpos.pos.model.TransactionMode
import com.uic.uicpaymentapp.uicpos.pos.model.toTransactionMode

@Composable
fun DeviceModeScreen(
    previousScreenName: String,
    onPressBackButton: () -> Unit,
    onPressTipSettings: () -> Unit,
    onPressBatchSettings: () -> Unit
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
                        text = UICApplication.instance.getString(R.string.setting_device),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    BackButton(
                        onBackPressed = onPressBackButton,
                        text = previousScreenName
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
                .verticalScroll(rememberScrollState())
                .background(color_secondaryThree)
        ) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
            ){
                SettingHeader(text = stringResource(id = R.string.setting_device_mode))

                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRowBoolean(
                        onClick = {
                            viewModel.saveSystemSetting(
                                SystemSettingType.TransactionMode,
                                TransactionMode.Retail.toString()
                            )
                        },
                        text = TransactionMode.Retail.toString(),
                        selected = systemUiState.transactionMode.text.toTransactionMode() == TransactionMode.Retail,
                        enabled = editEnabled
                    )

                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 4.dp))

                    SettingRowBoolean(
                        onClick = {
                            viewModel.saveSystemSetting(
                                SystemSettingType.TransactionMode,
                                TransactionMode.Restaurant.toString()
                            )
                        },
                        text = TransactionMode.Restaurant.toString(),
                        selected = systemUiState.transactionMode.text.toTransactionMode() == TransactionMode.Restaurant,
                        enabled = editEnabled
                    )

                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 4.dp))

                    SettingRowBoolean(
                        onClick = {
                            viewModel.saveSystemSetting(
                                SystemSettingType.TransactionMode,
                                TransactionMode.Cafe.toString()
                            )
                        },
                        text = TransactionMode.Cafe.toString(),
                        selected = systemUiState.transactionMode.text.toTransactionMode() == TransactionMode.Cafe,
                        enabled = editEnabled
                    )
                }

                SettingHeader(text = stringResource(id = R.string.setting_tipping), modifier = Modifier.padding(top = 32.dp))
                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRow(
                        label = stringResource(id = R.string.setting_tipping),
                        onClick = onPressTipSettings
                    )
                }

                SettingHeader(text = stringResource(id = R.string.setting_signature), modifier = Modifier.padding(top = 32.dp))

                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRowSwitch(
                        onClick = { string ->
                            viewModel.saveSystemSetting(
                                SystemSettingType.SignatureMode,
                                string
                            )
                        },
                        text = stringResource(id = R.string.setting_signature_required),
                        checked = systemUiState.signatureMode.text.toBoolean(),
                        enabled = editEnabled
                    )
                }

                SettingHeader(
                    text = stringResource(id = R.string.batching_title),
                    modifier = Modifier.padding(top = 32.dp)
                )

                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRow(
                        label = stringResource(id = R.string.batching_title),
                        onClick = onPressBatchSettings
                    )
                }
            }
        }
    }
}
