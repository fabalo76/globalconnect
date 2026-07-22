package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.transaction.ProcessingStatusStage
import one.globalconnect.paymentapp.ui.theme.color_alert
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand

@Composable
fun ProcessingStatusList(
    status: ProcessingStatusUi,
    modifier: Modifier = Modifier,
    textColor: Color,
    secondaryTextColor: Color,
) {
    val step = status.currentStep() ?: return
    val isWaitingStage = step.stage == ProcessingStatusStage.WAITING_FOR_RESPONSE
    val detail = when {
        step.detail.isNullOrBlank() -> null
        else -> step.detail
    }
    val titleText = if (isWaitingStage && detail != null) detail else step.title
    val secondaryText = if (isWaitingStage) null else detail
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step.status) {
            ProcessingStatusStepState.PENDING -> Icon(
                imageVector = Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = secondaryTextColor.copy(alpha = 0.4f),
                modifier = Modifier.size(36.dp),
            )
            ProcessingStatusStepState.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp,
                color = color_primaryBrand,
            )
            ProcessingStatusStepState.COMPLETED -> Icon(
                imageVector = Icons.Filled.CheckCircleOutline,
                contentDescription = null,
                tint = color_primaryBrand,
                modifier = Modifier.size(48.dp),
            )
            ProcessingStatusStepState.FAILED -> Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = color_alert,
                modifier = Modifier.size(48.dp),
            )
        }
        Column(
            modifier = Modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = titleText,
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            secondaryText?.let { value ->
                Text(
                    text = value,
                    color = secondaryTextColor,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
