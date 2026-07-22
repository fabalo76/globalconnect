package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.ui.theme.color_grey90
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_white

@Composable
fun TransactionStatusContainer(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    title: String? = null,
    amountText: String?,
    subtitle: String? = null,
    fullBleedContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(color_secondaryThree)
            .padding(contentPadding),
    ) {
        val gapFraction = 0.08f
        val minimumGap = 24.dp
        val maximumGap = maxHeight * 0.12f
        val desiredGap = maxHeight * gapFraction
        val topGap = desiredGap
            .coerceAtLeast(minimumGap)
            .coerceAtMost(if (maximumGap > minimumGap) maximumGap else minimumGap)

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(top = topGap)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
            color = color_white,
            contentColor = color_secondaryFive,
        ) {
            if (fullBleedContent) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color_grey90.copy(alpha = 0.6f)),
                    )
                    title?.takeIf { it.isNotBlank() }?.let { value ->
                        Text(
                            text = value,
                            color = color_secondaryFive,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                    subtitle?.takeIf { it.isNotBlank() }?.let { value ->
                        Text(
                            text = value,
                            color = color_secondaryFive.copy(alpha = 0.85f),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                    amountText?.takeIf { it.isNotBlank() }?.let { value ->
                        Text(
                            text = value,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                    content()
                }
            }
        }
    }
}
