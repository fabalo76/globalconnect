package com.uic.uicpaymentapp.utils


import androidx.compose.runtime.compositionLocalOf;


val LocalLanguageViewModel = compositionLocalOf<LanguageViewModel> {
    error("No LanguageViewModel provided")
}
