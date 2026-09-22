package one.globalconnect.paymentapp.profile

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.records.BackButton
import one.globalconnect.paymentapp.transaction.ROWHEIGHT
import one.globalconnect.paymentapp.ui.theme.color_grey50
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.utils.LanguageViewModel
import java.util.Locale

const val PREF_CAPTURE_SIGNATURE = "capture_signature"

@Composable
fun AppConfigScreen(
    context: Context,
    sharedPreferences: SharedPreferences,
    previousScreenName: String,
    onBack: () -> Unit,
) {
    val languageViewModel = remember { LanguageViewModel() }
    val savedLanguage = remember { languageViewModel.loadLanguagePreference(sharedPreferences) }
    val langItems = listOf(
        stringResource(R.string.android_lng),
        stringResource(R.string.english_lng),
        stringResource(R.string.spanish_lng),
    )
    var languageExpanded by remember { mutableStateOf(false) }
    var currentLangDisplay by remember { mutableStateOf(resolveLanguageLabel(savedLanguage, langItems)) }

    var captureSignature by remember {
        mutableStateOf(sharedPreferences.getBoolean(PREF_CAPTURE_SIGNATURE, false))
    }

    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        fontSize = 16.sp,
                        text = stringResource(R.string.app_config),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    BackButton(onBackPressed = onBack, text = previousScreenName)
                },
                actions = {},
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(color_secondaryThree)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                Modifier
                    .padding(8.dp, 16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxWidth()
            ) {
                one.globalconnect.paymentapp.ecr.EcrConfiguration()
                SettingHeader(
                    text = stringResource(R.string.setting_general),
                    modifier = Modifier.padding(top = 8.dp),
                )

                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .background(color = color_grey95, shape = RoundedCornerShape(10.dp))
                        .fillMaxWidth()
                ) {
                    // Language row
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROWHEIGHT.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.select_language),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 16.dp),
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { languageExpanded = !languageExpanded }) {
                            Text(
                                text = currentLangDisplay,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                            Icon(
                                imageVector = if (!languageExpanded) Icons.Filled.KeyboardArrowDown
                                              else Icons.Filled.KeyboardArrowUp,
                                contentDescription = null,
                                tint = color_grey50,
                            )
                            DropdownMenu(
                                expanded = languageExpanded,
                                onDismissRequest = { languageExpanded = false },
                            ) {
                                langItems.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item) },
                                        onClick = {
                                            languageExpanded = false
                                            currentLangDisplay = item
                                            val localeTag = resolveLocaleTag(item, langItems, context)
                                            languageViewModel.updateLanguage(
                                                context,
                                                sharedPreferences,
                                                Locale.forLanguageTag(localeTag),
                                            )
                                            if (context is ComponentActivity) {
                                                restartForLanguage(context)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    RowDivider(Modifier.fillMaxWidth().padding(start = 8.dp))

                    // Capture Signature row
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROWHEIGHT.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.capture_signature),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = captureSignature,
                            onCheckedChange = { enabled ->
                                captureSignature = enabled
                                sharedPreferences.edit()
                                    .putBoolean(PREF_CAPTURE_SIGNATURE, enabled)
                                    .apply()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun resolveLanguageLabel(savedLang: String, items: List<String>): String =
    when (savedLang) {
        "en" -> items[1]
        "es" -> items[2]
        else -> items[0]
    }

private fun resolveLocaleTag(item: String, items: List<String>, context: Context): String =
    when (item) {
        items[1] -> "en"
        items[2] -> "es"
        else -> context.resources.configuration.locales[0].language
    }

private fun restartForLanguage(activity: ComponentActivity) {
    val intent = activity.intent
    activity.finish()
    activity.startActivity(intent)
}
