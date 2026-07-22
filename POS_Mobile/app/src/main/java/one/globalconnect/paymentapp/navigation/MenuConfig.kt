package one.globalconnect.paymentapp.navigation

import androidx.compose.ui.graphics.vector.ImageVector

data class MenuConfig(
    val id: String, // Unique menu identifier
    val title: String, // Title to display
    val buttons: List<ButtonConfig>
)
