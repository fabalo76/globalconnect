package one.globalconnect.keyinjection.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import android.view.SoundEffectConstants
import one.globalconnect.keyinjection.ui.theme.GlobalConnectGradient

/**
 * Reusable button styled with the Global Connect teal-to-navy gradient.
 * Wraps the passed [content] inside a Material3 [Button].
 *
 * @param onClick action performed when the button is pressed
 * @param modifier optional modifiers for the button
 * @param content composable content to display inside the button
 *
 * Author: Fabian Ramirez
 */
@Composable
fun GlobalConnectButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    Button(
        onClick = {
            view.playSoundEffect(SoundEffectConstants.CLICK)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.Cyan
        ),
        contentPadding = PaddingValues()
    ) {
        Box(
            modifier = Modifier
                .clip(ButtonDefaults.shape)
                .background(GlobalConnectGradient)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}
