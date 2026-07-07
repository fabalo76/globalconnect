package com.uic.uicpaymentapp.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

fun Context.setLocale(locale: Locale): Context {
    val config = Configuration(resources.configuration)
    config.setLocale(locale)

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        val localeList = LocaleList(locale)
        LocaleList.setDefault(localeList)
        config.setLocales(localeList)
    }

    return createConfigurationContext(config)
}


fun getSavedLanguage(sharedPreferences: SharedPreferences): String {
    return sharedPreferences.getString("selected_language", "") ?: ""
}
