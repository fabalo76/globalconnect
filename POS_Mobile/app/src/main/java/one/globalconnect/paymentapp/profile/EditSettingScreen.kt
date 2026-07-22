package one.globalconnect.paymentapp.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.records.BackButton
import one.globalconnect.paymentapp.transaction.ROWHEIGHT
import one.globalconnect.paymentapp.ui.theme.color_grey90
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white

@Composable
fun EditTextSettingScreen(
    systemSettingType: SystemSettingType,
    previousScreenName: String,
    onPressBackButton: () -> Unit
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: SystemSettingsViewModel = viewModel(factory = viewModelFactory)

    val systemUiState by viewModel.systemUiState.collectAsState(initial = SystemUiState())

    EditSettingBody(
        systemUiState,
        systemSettingType = systemSettingType,
        previousScreenName = previousScreenName,
        onPressBackButton = onPressBackButton,
        onSaveSetting = { type, s -> viewModel.saveSystemSetting(type, s) },
        writeToDb = { viewModel.writeToDb() })
}

@Composable
fun EditSettingBody(
    systemUiState: SystemUiState,
    systemSettingType: SystemSettingType,
    previousScreenName: String,
    onPressBackButton: () -> Unit,
    onSaveSetting: (SystemSettingType, String) -> Unit,
    writeToDb: () -> Boolean,
) {
    val text = when (systemSettingType) {
        SystemSettingType.CmdTout -> systemUiState.cmdTout.text
        SystemSettingType.Password -> systemUiState.password.text
        SystemSettingType.TransactionMode -> systemUiState.transactionMode.text
        SystemSettingType.TipMethod -> systemUiState.tipMethod.text
        SystemSettingType.SignatureMode -> systemUiState.signatureMode.text
        SystemSettingType.Language -> systemUiState.language.text
        SystemSettingType.BatchPrint -> systemUiState.batchPrint.text
        SystemSettingType.OptionsEnabled -> systemUiState.optionsEnabled.text
        SystemSettingType.TipSetting1 -> systemUiState.tipOption1.text
        SystemSettingType.TipSetting2 -> systemUiState.tipOption2.text
        SystemSettingType.TipSetting3 -> systemUiState.tipOption3.text
        SystemSettingType.TipSetting4 -> systemUiState.tipOption4.text
        SystemSettingType.DetailedBatchReport -> systemUiState.detailedBatchReport.text
    }
    var editEnabled by rememberSaveable { mutableStateOf(false) }
    var errorOnInput by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        fontSize = 16.sp,
                        text = systemSettingType.toUserLabel(),
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
                            onClick = {
                                editEnabled = true
                            }) {
                            Text(text = stringResource(id = R.string.edit), fontSize = 16.sp)
                        }
                    } else {
                        TextButton(modifier = Modifier.padding(16.dp), onClick = {
                            if (systemSettingType == SystemSettingType.Language) {
                                errorOnInput = true
                            } else if (!writeToDb.invoke()) {
                                errorOnInput = true
                            } else {
                                errorOnInput = false
                                editEnabled = false
                            }
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
                .fillMaxSize(1f)
                .padding(contentPadding)
                .background(color_secondaryThree)
        ) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxSize()
            ){
                LaunchedEffect(editEnabled) {
                    if (editEnabled) {
                        focusRequester.requestFocus()
                    } else {
                        focusRequester.freeFocus()
                    }
                }
                SettingRowEditText(
                    text = text,
                    systemSettingType = systemSettingType,
                    onSaveSetting = onSaveSetting,
                    enabled = editEnabled,
                    error = errorOnInput,
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(16.dp)
                        .height(80.dp)
                        .focusRequester(focusRequester)
                )

            }

        }
    }
}

@Composable
fun SettingRowEditText(
    text: String,
    systemSettingType: SystemSettingType,
    onSaveSetting: (SystemSettingType, String) -> Unit,
    enabled: Boolean,
    error: Boolean,
    modifier: Modifier = Modifier
) {
    val textFieldValue = TextFieldValue(text, TextRange(text.length))
    var errorOnInput by rememberSaveable { mutableStateOf(error) }

    var passwordVisibility by rememberSaveable { mutableStateOf(false) }
    val addPercentSuffix: Boolean =
        systemSettingType == SystemSettingType.TipSetting1
                || systemSettingType == SystemSettingType.TipSetting2
                || systemSettingType == SystemSettingType.TipSetting3
                || systemSettingType == SystemSettingType.TipSetting4

    TextField(
        modifier = modifier.onFocusEvent {
            errorOnInput = if (it.isFocused.not()) {
                !validateInput(systemSettingType, textFieldValue.text)
            } else {
                false
            }
        },
        value = textFieldValue,
        onValueChange = { newInput ->
            onSaveSetting(systemSettingType, newInput.text)
        },
        enabled = enabled,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = color_grey95,
            unfocusedContainerColor = color_grey95,
            focusedTextColor = MaterialTheme.colorScheme.onBackground,
            disabledContainerColor = color_grey90,
            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        textStyle = TextStyle(
            fontSize = 16.sp,
            textAlign = TextAlign.End
        ),
        shape = RoundedCornerShape(10.dp),
        leadingIcon = {
            Text(
                text = systemSettingType.toUserLabel(),
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .padding(start = 16.dp),
                fontSize = 16.sp
            )
        },
        trailingIcon = {
            if (systemSettingType == SystemSettingType.Password) {
                if (passwordVisibility) {
                    IconButton(
                        enabled = enabled,
                        onClick = { passwordVisibility = !passwordVisibility }) {
                        Icon(
                            imageVector = Icons.Filled.VisibilityOff,
                            contentDescription = "Visible"
                        )
                    }
                } else {
                    IconButton(
                        enabled = enabled,
                        onClick = { passwordVisibility = !passwordVisibility }) {
                        Icon(
                            imageVector = Icons.Filled.Visibility,
                            contentDescription = "Visible"
                        )
                    }
                }

            }
        },
        isError = errorOnInput,
        keyboardOptions = when (systemSettingType) {
            SystemSettingType.CmdTout -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
            SystemSettingType.Password -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.NumberPassword)
            SystemSettingType.TipSetting1 -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
            SystemSettingType.TipSetting2 -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
            SystemSettingType.TipSetting3 -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
            SystemSettingType.TipSetting4 -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
            else -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Text, autoCorrectEnabled = false)
        },
        keyboardActions = KeyboardActions(
            onDone = {
                errorOnInput = !validateInput(systemSettingType, textFieldValue.text)
                defaultKeyboardAction(ImeAction.Done)
            }
        ),
        supportingText = {
            if (errorOnInput) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = systemSettingType.getErrorString(),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        visualTransformation = if (systemSettingType == SystemSettingType.Password && !passwordVisibility) PasswordVisualTransformation() else VisualTransformation.None,
        suffix = {
            if (addPercentSuffix) {
                Text(text = "%", fontSize = 16.sp)
            }
        }
    )
}


@Composable
fun SettingRowBoolean(
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    enabled: Boolean
) {
    Row(modifier
        .fillMaxWidth(1f)
        .height(ROWHEIGHT.dp)
        .clickable(
            enabled = enabled,
            onClick = { onClick(text) }
        )) {
        Text(
            text = text,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 16.dp)
        )

        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "",
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun SettingRowSwitch(
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    text: String,
    checked: Boolean,
    enabled: Boolean
) {
    Row(
        modifier
            .fillMaxWidth(1f)
            .height(ROWHEIGHT.dp)
            .clickable { onClick(text) }) {
        Text(
            text = text,
//            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 16.dp)
        )

        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = {
                onClick(it.toString())
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = color_primaryBrand,
                uncheckedTrackColor = color_primaryBrand.copy(alpha = 0.2f)
            ),
            enabled = enabled,
            modifier = Modifier
                .padding(end = 16.dp)
                .align(Alignment.CenterVertically),
            thumbContent = if (text.toBoolean()) {
                {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                }
            } else {
                null
            }
        )
    }
}
