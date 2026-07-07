package com.uic.uicpaymentapp.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

data class ButtonConfig(
    val text: String,
    val icon: ImageVector,
    val action: () -> Unit
)