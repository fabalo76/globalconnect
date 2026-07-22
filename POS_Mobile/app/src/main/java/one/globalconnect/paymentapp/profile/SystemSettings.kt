package one.globalconnect.paymentapp.profile

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.transaction.ROWHEIGHT
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.utils.LanguageViewModel
import java.util.Locale

@Composable
fun SystemSettingsScreen(
    navigateToProfile: () -> Unit,
    navigateToAbout: () -> Unit,
    navigateToLanguage: () -> Unit,
    navigateToDeviceSettings: () -> Unit,
    navigateToPassword: () -> Unit,
    navigateToCommandTimeout: () -> Unit,
    sharedPreferences: SharedPreferences,
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: SystemSettingsViewModel = viewModel(factory = viewModelFactory)

    val systemUiState by viewModel.systemUiState.collectAsState(SystemUiState())
    val initializeState by viewModel.initializeState.collectAsState()
    val languageViewModel = LanguageViewModel()
    val savedLanguage = languageViewModel.loadLanguagePreference(sharedPreferences)
    val items = listOf(stringResource(id = R.string.english_lng), stringResource(id = R.string.spanish_lng))
    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        fontSize = 20.sp,
                        text = stringResource(id = R.string.setting),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = null,
                actions = null,
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .background(color_secondaryThree)
        ) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
            ){
                SettingHeader(text = stringResource(id = R.string.setting_general), modifier = Modifier.padding(top = 8.dp))
                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    SettingRow(label = stringResource(id = R.string.profile), onClick = navigateToProfile)
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 8.dp))
                    SettingRow(label = stringResource(id = R.string.credential_merchant_info), onClick = navigateToAbout)
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 8.dp))
                    SettingRow(
                        label = stringResource(id = R.string.language),
//                        value = systemUiState.language.text,
                        value = getLanguage(savedLanguage, items),
                        onClick = navigateToLanguage
                    )
                }


                SettingHeader(text = stringResource(id = R.string.system), modifier = Modifier.padding(top = 36.dp))
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
                        label = stringResource(id = R.string.setting_device_mode),
                        value = systemUiState.transactionMode.text,
                        onClick = navigateToDeviceSettings
                    )
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 8.dp))
                    SettingRow(
                        label = stringResource(id = R.string.setting_command_timeout),
                        value = systemUiState.cmdTout.text,
                        onClick = navigateToCommandTimeout
                    )
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 8.dp))
                    SettingRow(label = stringResource(id = R.string.system_setting_privacy), onClick = navigateToPassword)
                }

                SettingHeader(text = stringResource(id = R.string.setting_tms), modifier = Modifier.padding(top = 36.dp))
                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    val initValue: String?
                    val initColor: androidx.compose.ui.graphics.Color
                    when (val s = initializeState) {
                        is InitializeState.Idle      -> { initValue = null;                                           initColor = MaterialTheme.colorScheme.onSurfaceVariant }
                        is InitializeState.Loading   -> { initValue = stringResource(R.string.setting_initialize_checking);  initColor = MaterialTheme.colorScheme.onSurfaceVariant }
                        is InitializeState.Requested -> { initValue = stringResource(R.string.setting_initialize_requested); initColor = MaterialTheme.colorScheme.onSurfaceVariant }
                        is InitializeState.Error     -> { initValue = s.message;                                      initColor = Color.Red }
                    }
                    ActionRow(
                        label = stringResource(id = R.string.setting_initialize),
                        value = initValue,
                        valueColor = initColor,
                        onClick = { viewModel.requestInitialize() }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingHeader(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth(1f)
    ) {
        Column {
            Text(
                text = text,
                fontWeight = FontWeight.SemiBold,
                color = color_black,
                modifier = Modifier.padding(start = 20.dp, bottom = 2.dp),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun SettingRow(
    label: String,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth(1f)
            .height(ROWHEIGHT.dp)
            .clickable { onClick.invoke() }
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
        if (value != null) {
            Text(
                text = value,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 8.dp),
                fontSize = 14.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "",
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = 16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
fun ActionRow(
    label: String,
    value: String? = null,
    valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth(1f)
            .height(ROWHEIGHT.dp)
            .clickable { onClick.invoke() }
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
        if (value != null) {
            Text(
                text = value,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 8.dp),
                fontSize = 14.sp
            )
        }
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "",
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = 16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun getLanguage(savedLanguage: String, items: List<String>): String {
    val language = when (savedLanguage) {
        "en" -> items[0]
        "es" -> items[1]
        else -> Locale.getDefault()
    }

    return language as String
}