package one.globalconnect.paymentapp.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class LanguageViewModel : ViewModel() {
    private val _selectedLanguage = MutableStateFlow("es")
    val selectedLanguage = _selectedLanguage.asStateFlow()

    companion object {
        private const val LANGUAGE_KEY = "selected_language"
    }


    fun updateLanguage(context: Context, sharedPreferences: SharedPreferences, locale: Locale) {

        saveLanguagePreference(sharedPreferences, locale.language)


        context.setLocale(locale)
        GlobalConnectPaymentApplication.instance.updateLocale(locale)


        _selectedLanguage.value = locale.language
    }


    fun loadLanguagePreference(sharedPreferences: SharedPreferences): String {
        return sharedPreferences.getString(LANGUAGE_KEY, "es") ?: "es"
    }


    private fun saveLanguagePreference(sharedPreferences: SharedPreferences, language: String) {
        sharedPreferences.edit().putString(LANGUAGE_KEY, language).apply()
    }
}
