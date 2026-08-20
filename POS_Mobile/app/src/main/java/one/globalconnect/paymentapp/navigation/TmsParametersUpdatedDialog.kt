package one.globalconnect.paymentapp.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive

private const val AUTO_DISMISS_DELAY_MS = 60_000L

/**
 * Confirms that downloaded TMS parameters were persisted and applied to the payment app.
 * A new [updateId] restarts the auto-dismiss timer if another update arrives while visible.
 */
@Composable
fun TmsParametersUpdatedDialog(
    updateId: Long,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(updateId) {
        delay(AUTO_DISMISS_DELAY_MS)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(text = stringResource(R.string.setting_initialize_success_title))
        },
        text = {
            Text(text = stringResource(R.string.setting_initialize_success_body))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ok))
            }
        },
        shape = RectangleShape,
    )
}

/** Informs the operator that payment parameters cannot be activated until settlement. */
@Composable
fun TmsParametersPendingDialog(
    onSettlement: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.pending_param_update_title))
        },
        text = {
            Text(
                text = stringResource(R.string.pending_param_update_msg),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        confirmButton = {
            Button(
                onClick = onSettlement,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color_primaryBrand,
                    contentColor = color_secondaryFive,
                ),
            ) {
                Text(text = stringResource(R.string.pending_param_update_settlement))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, color_primaryBrand),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = color_secondaryFive,
                ),
            ) {
                Text(text = stringResource(R.string.pending_param_update_dismiss))
            }
        },
        shape = RectangleShape,
    )
}
