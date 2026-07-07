package com.uic.uicpaymentapp.profile

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uic.uicpaymentapp.BuildConfig
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.records.BackButton
import com.uic.uicpaymentapp.transaction.ProcessTransactionUiState
import com.uic.uicpaymentapp.transaction.ROWHEIGHT
import com.uic.uicpaymentapp.transaction.TransactionErrorDialog
import com.uic.uicpaymentapp.ui.theme.color_grey95
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.uicpos.pos.model.InfoMgmt
import com.uic.uicpaymentapp.utils.SoundEffect
import com.uic.uicpaymentapp.utils.SoundManager

@Composable
fun AboutScreen(
    previousScreenName: String,
    onPressBackButton: () -> Unit
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: InfoMgmtViewModel = viewModel(factory = viewModelFactory)

    val uiState by viewModel.uiState.collectAsState(ProcessTransactionUiState.AUTHORIZED(""))

    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        fontSize = 16.sp,
                        text = stringResource(id = R.string.device_info),
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
                    when (uiState) {
                        is ProcessTransactionUiState.AUTHORIZED -> {
                            IconButton(enabled = !viewModel.fetchInfoOngoing, onClick = {
                                SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                                viewModel.getInfoMgmt()
                            }) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            }
                        }

                        is ProcessTransactionUiState.FAILED -> {
                            IconButton(enabled = !viewModel.fetchInfoOngoing, onClick = {
                                SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                                viewModel.getInfoMgmt()
                            }) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            }
                        }

                        is ProcessTransactionUiState.RETRY -> {
                            IconButton(enabled = !viewModel.fetchInfoOngoing, onClick = {
                                SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                                viewModel.getInfoMgmt()
                            }) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            }
                        }

                        is ProcessTransactionUiState.CONNECTIONFAILED -> {
                            IconButton(enabled = !viewModel.fetchInfoOngoing, onClick = {
                                SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                                viewModel.getInfoMgmt()
                            }) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            }
                        }

                        else -> {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(25.dp)
                                    .padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onBackground
                            )
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
            ) {
                if (viewModel.showFailedDialog) {
                    TransactionErrorDialog(
                        onDismiss = { viewModel.showFailedDialog = false },
                        text = stringResource(id = R.string.err_connection_failed_check_terminal)
                    )
                }

                SettingHeader(stringResource(id = R.string.application_info))
                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    InfoRow(
                        stringResource(id = R.string.setting_application_version_no),
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    )
                }

                SettingHeader(stringResource(id = R.string.credentials), modifier = Modifier.padding(top = 36.dp))

                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    val infoMgmt by viewModel.infoMgmt.collectAsState(InfoMgmt())
                    val capkLabel = when (
                        UICApplication.instanceOrNull?.tmsDatabase?.Terminal?.firstOrNull()?.CAPKKEyConfig
                    ) {
                        "02" -> stringResource(R.string.credential_capk_env_test)
                        "03" -> stringResource(R.string.credential_capk_env_live_and_test)
                        else -> stringResource(R.string.credential_capk_env_live)
                    }
                    val infoItems = listOf(
                        R.string.credential_acquirer_name to infoMgmt.PaymentProcessorName,
                        R.string.credential_acquirer_id to infoMgmt.PaymentProcessorId,
                        R.string.credential_acquirer_nii to infoMgmt.AcquirerNii,
                        R.string.credential_merchant_name to infoMgmt.MerchantName,
                        R.string.credential_merchant_id to infoMgmt.MerchantId,
                        R.string.credential_terminal_id to infoMgmt.TerminalId,
                        R.string.credential_terminal_serial to infoMgmt.SerialNo,
                        R.string.credential_terminal_address to infoMgmt.MerchantAddress,
                        R.string.credential_device_manufacturer to infoMgmt.DeviceType,
                        R.string.credential_device_model to infoMgmt.DeviceName,
                        R.string.credential_device_version to infoMgmt.DeviceVer,
                        R.string.credential_capk_env to capkLabel,
                    )
                    infoItems.forEach { (labelRes, value) ->
                        if (!value.isNullOrBlank()) {
                            InfoRow(stringResource(id = labelRes), value)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth(1f)
            .height(ROWHEIGHT.dp)
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 16.dp),
            fontSize = 14.sp
        )

        Spacer(Modifier.weight(1f))
        Text(
            text = value ?: "",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = 16.dp),
            fontSize = 14.sp
        )
    }
}
