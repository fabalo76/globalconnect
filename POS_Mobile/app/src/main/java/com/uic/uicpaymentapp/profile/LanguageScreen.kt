package com.uic.uicpaymentapp.profile

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.records.BackButton
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_grey50
import com.uic.uicpaymentapp.ui.theme.color_grey95
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.utils.LanguageViewModel
import java.util.Locale
import android.util.Log
import com.uic.uicpaymentapp.UICApplication

@Composable
fun LanguageScreen(
    context: Context,
    sharedPreferences: SharedPreferences,
    systemSettingType: SystemSettingType,
    previousScreenName: String,
    onPressBackButton: () -> Unit
){
    val languageViewModel = LanguageViewModel()
    val savedLanguage = languageViewModel.loadLanguagePreference(sharedPreferences)
    var expanded by remember { mutableStateOf(false) }
    val items = listOf(stringResource(id = R.string.android_lng), stringResource(id = R.string.english_lng), stringResource(id = R.string.spanish_lng))

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
                },
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize(1f)
                .padding(contentPadding)
                .background(color_secondaryThree)
        ){
            Column(
                Modifier
                    .padding(8.dp, 16.dp)
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
                        .fillMaxWidth(1f)
                ){
                    Row(){
                        Text(
                            text = stringResource(id = R.string.select_language),
                            modifier = Modifier.padding(16.dp),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { expanded = !expanded },
                            Modifier.padding(top = 4.dp, bottom = 4.dp)
                        ) {

                            Text(
                                text = getLanguage(savedLanguage, items),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                fontWeight = FontWeight.Bold,
                                color = color_black
                            )
                            Icon(
                                imageVector = if (!expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Language Selector",
                                tint = color_grey50
                            )

                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                items.forEach { item ->
                                    DropdownMenuItem(text = { Text(item) }, onClick = {
                                        languageViewModel.updateLanguage(
                                            context,
                                            sharedPreferences,
                                            Locale.forLanguageTag(getLocate(item, items))
                                        )
                                        if (context is ComponentActivity) {
                                            restartActivity(context)
                                        }
                                        expanded = false
                                    })
                                }
                            }
                        }

                    }
                }
            }
        }
    }
}


private fun getLanguage(savedLanguage: String, items: List<String>): String {
    val language = when (savedLanguage) {
        "en" -> items[1]
        "es" -> items[2]
        else -> UICApplication.instance.resources.configuration.locales[0]
    }

    return language as String
}

private fun getLocate(language: String, items: List<String>): String {
    return when (language) {
        items[1] -> "en"
        items[2] -> "es"
        else -> UICApplication.instance.resources.configuration.locales[0].language; // Use correct locale from resources

    }
}

private fun restartActivity(activity: ComponentActivity) {
    val intent = activity.intent
    activity.finish()
    activity.startActivity(intent)
}
