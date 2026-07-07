package com.uic.uicpaymentapp.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.records.BackButton
import com.uic.uicpaymentapp.ui.theme.color_grey95
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white

@Composable
fun TipSettingsScreen(
    onBackPressed: () -> Unit,
    previousScreen: String,
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: SystemSettingsViewModel = viewModel(factory = viewModelFactory)

    var editEnabled by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val systemUiState by viewModel.systemUiState.collectAsState(initial = SystemUiState())

    var errorOnInputFirst by rememberSaveable { mutableStateOf(false) }
    var errorOnInputSecond by rememberSaveable { mutableStateOf(false) }
    var errorOnInputThird by rememberSaveable { mutableStateOf(false) }
    var errorOnInputFourth by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopBar(title = {
                Text(
                    fontSize = 16.sp,
                    text = stringResource(id = R.string.tip_settings_title),
                    fontWeight = FontWeight.SemiBold
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
                            editEnabled = false
                            errorOnInputFirst = !validateInput(
                                SystemSettingType.TipSetting1,
                                systemUiState.tipOption1.text
                            )
                            errorOnInputSecond = !validateInput(
                                SystemSettingType.TipSetting2,
                                systemUiState.tipOption2.text
                            )
                            errorOnInputThird = !validateInput(
                                SystemSettingType.TipSetting3,
                                systemUiState.tipOption3.text
                            )
                            errorOnInputFourth = !validateInput(
                                SystemSettingType.TipSetting4,
                                systemUiState.tipOption4.text
                            )
                            viewModel.writeToDb()
                        }
                        ) {
                            Text(text = stringResource(id = R.string.save), fontSize = 16.sp)
                        }
                    }
                },
                navigationIcon = {
                    BackButton(
                        onBackPressed = onBackPressed,
                        text = previousScreen
                    )
                })
        }
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize(1f)
                .padding(contentPadding)
                .background(color_secondaryThree)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
            ){
                LaunchedEffect(editEnabled) {
                    if (editEnabled) {
                        focusRequester.requestFocus()
                    } else {
                        focusRequester.freeFocus()
                    }
                }

                SettingRowEditText(
                    text = systemUiState.tipOption1.text,
                    systemSettingType = SystemSettingType.TipSetting1,
                    onSaveSetting = { type, s -> viewModel.saveSystemSetting(type, s) },
                    enabled = editEnabled,
                    error = errorOnInputFirst,
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                        .height(80.dp)
                        .focusRequester(focusRequester)
                )

                SettingRowEditText(
                    text = systemUiState.tipOption2.text,
                    systemSettingType = SystemSettingType.TipSetting2,
                    onSaveSetting = { type, s -> viewModel.saveSystemSetting(type, s) },
                    enabled = editEnabled,
                    error = errorOnInputSecond,
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp)
                        .height(80.dp)
                )

                SettingRowEditText(
                    text = systemUiState.tipOption3.text,
                    systemSettingType = SystemSettingType.TipSetting3,
                    onSaveSetting = { type, s -> viewModel.saveSystemSetting(type, s) },
                    enabled = editEnabled,
                    error = errorOnInputThird,
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp)
                        .height(80.dp)
                )

                SettingRowEditText(
                    text = systemUiState.tipOption4.text,
                    systemSettingType = SystemSettingType.TipSetting4,
                    onSaveSetting = { type, s -> viewModel.saveSystemSetting(type, s) },
                    enabled = editEnabled,
                    error = errorOnInputFourth,
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp)
                        .height(80.dp)
                )

                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRowSwitch(
                        onClick = { s ->
                            viewModel.saveSystemSetting(SystemSettingType.OptionsEnabled, s)
                        },
                        text = stringResource(id = R.string.tip_options_enabled),
                        checked = systemUiState.optionsEnabled.text.toBoolean(),
                        enabled = editEnabled,
                    )
                }

                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRowSwitch(
                        onClick = { s ->
                            viewModel.saveSystemSetting(SystemSettingType.TipMethod, s)
                        },
                        text = stringResource(id = R.string.on_screen_tip_enabled),
                        checked = systemUiState.tipMethod.text.toBoolean(),
                        enabled = editEnabled,
                    )
                }
            }
        }
    }
}
