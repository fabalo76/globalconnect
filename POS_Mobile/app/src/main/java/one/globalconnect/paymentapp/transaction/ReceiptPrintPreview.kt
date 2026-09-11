package one.globalconnect.paymentapp.transaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_white
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.min

@Composable
fun ReceiptPrintPreview(
    state: ReceiptPreviewState,
    onDismissed: () -> Unit,
) {
    val density = LocalDensity.current
    val receiptOffset = remember(state.triggerTimestamp) { Animatable(0f) }
    var ticketHeightPx by remember(state.triggerTimestamp) { mutableStateOf(0) }
    var manualExitRequested by remember(state.triggerTimestamp) { mutableStateOf(false) }
    var dismissalDelivered by remember(state.triggerTimestamp) { mutableStateOf(false) }
    val exitDistancePx = ticketHeightPx + with(density) { 96.dp.toPx() }
    val exitProgress = if (exitDistancePx > 0f) {
        (-receiptOffset.value / exitDistancePx).coerceIn(0f, 1f)
    } else {
        0f
    }
    val backdropFade = ((exitProgress - 0.75f) / 0.25f).coerceIn(0f, 1f)

    LaunchedEffect(state.triggerTimestamp, ticketHeightPx) {
        if (ticketHeightPx <= 0) return@LaunchedEffect
        delay(RECEIPT_START_DELAY_MILLIS)
        if (manualExitRequested) return@LaunchedEffect

        val duration = (exitDistancePx / RECEIPT_SCROLL_PIXELS_PER_MILLISECOND)
            .roundToInt()
            .coerceIn(MIN_RECEIPT_SCROLL_DURATION_MILLIS, MAX_RECEIPT_SCROLL_DURATION_MILLIS)
        receiptOffset.animateTo(
            targetValue = -exitDistancePx,
            animationSpec = tween(durationMillis = duration, easing = LinearEasing),
        )
        if (!dismissalDelivered) {
            dismissalDelivered = true
            onDismissed()
        }
    }

    LaunchedEffect(manualExitRequested, ticketHeightPx) {
        if (!manualExitRequested || ticketHeightPx <= 0) return@LaunchedEffect
        receiptOffset.animateTo(
            targetValue = -exitDistancePx,
            animationSpec = tween(
                durationMillis = MANUAL_RECEIPT_EXIT_DURATION_MILLIS,
                easing = FastOutSlowInEasing,
            ),
        )
        if (!dismissalDelivered) {
            dismissalDelivered = true
            onDismissed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f * (1f - backdropFade)))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) {
                manualExitRequested = true
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val ticketShape = TicketStubShape(
            cornerRadius = 18.dp,
            toothWidth = 14.dp,
            toothDepth = 8.dp,
        )
        ReceiptTicket(
            modifier = Modifier
                .graphicsLayer { translationY = receiptOffset.value }
                .padding(top = 64.dp, start = 12.dp, end = 12.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .shadow(12.dp, ticketShape, clip = false)
                .clip(ticketShape)
                .background(color_white)
                .border(
                    width = 1.dp,
                    color = color_secondaryFive.copy(alpha = 0.1f),
                    shape = ticketShape,
                )
                .padding(horizontal = 14.dp, vertical = 22.dp),
            state = state,
            onHeightChanged = { measuredHeight ->
                if (measuredHeight > 0 && measuredHeight != ticketHeightPx) {
                    ticketHeightPx = measuredHeight
                }
            },
        )
    }
}

@Composable
private fun ReceiptTicket(
    modifier: Modifier = Modifier,
    state: ReceiptPreviewState,
    onHeightChanged: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .wrapContentHeight(unbounded = true, align = Alignment.Top)
            .then(modifier)
            .onSizeChanged { onHeightChanged(it.height) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(modifier = Modifier.height(6.dp))
        state.lines.forEach { line ->
            if (line.primary.isBlank() && line.secondary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
            } else if (line.secondary == null) {
                Text(
                    text = line.primary,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = line.fontSize.previewFontSize,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (line.emphasis) FontWeight.SemiBold else FontWeight.Normal,
                    color = color_black,
                    textAlign = line.alignment,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = line.primary,
                        fontSize = line.fontSize.previewFontSize,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (line.emphasis) FontWeight.SemiBold else FontWeight.Normal,
                        color = color_black,
                    )
                    Text(
                        text = line.secondary,
                        fontSize = line.fontSize.previewFontSize,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (line.emphasis) FontWeight.SemiBold else FontWeight.Normal,
                        color = color_black,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        if (state.signature != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Image(
                bitmap = state.signature,
                contentDescription = stringResource(id = R.string.receipt_signature),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 120.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color_white),
                contentScale = ContentScale.FillWidth,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

private val ReceiptPreviewFontSize.previewFontSize
    get() = when (this) {
        ReceiptPreviewFontSize.MIN -> 11.sp
        ReceiptPreviewFontSize.TINY -> 12.sp
        ReceiptPreviewFontSize.SMALL -> 13.sp
        ReceiptPreviewFontSize.MEDIUM -> 14.sp
        ReceiptPreviewFontSize.LARGE -> 15.sp
        ReceiptPreviewFontSize.MASSIVE -> 17.sp
    }

private const val RECEIPT_START_DELAY_MILLIS = 200L
private const val RECEIPT_SCROLL_PIXELS_PER_MILLISECOND = 0.75f
private const val MIN_RECEIPT_SCROLL_DURATION_MILLIS = 1400
private const val MAX_RECEIPT_SCROLL_DURATION_MILLIS = 8000
private const val MANUAL_RECEIPT_EXIT_DURATION_MILLIS = 900

private class TicketStubShape(
    private val cornerRadius: Dp,
    private val toothWidth: Dp,
    private val toothDepth: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val width = size.width
        val height = size.height

        if (width <= 0f || height <= 0f) {
            return Outline.Rectangle(Rect(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f)))
        }

        return with(density) {
            val resolvedRadius = min(cornerRadius.toPx(), min(width, height) / 2f)
            val resolvedDepth = toothDepth.toPx().coerceIn(0f, height / 2f)
            val rawStep = toothWidth.toPx().coerceAtLeast(1f)
            val toothCount = (width / rawStep).toInt().coerceAtLeast(1)
            val step = width / toothCount

            if (resolvedDepth == 0f) {
                Outline.Rounded(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = width,
                        bottom = height,
                        cornerRadius = CornerRadius(resolvedRadius, resolvedRadius),
                    ),
                )
            } else {
                val path = Path().apply {
                    moveTo(0f, resolvedRadius)
                    if (resolvedRadius > 0f) {
                        arcTo(
                            rect = Rect(0f, 0f, resolvedRadius * 2f, resolvedRadius * 2f),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false,
                        )
                        lineTo(width - resolvedRadius, 0f)
                        arcTo(
                            rect = Rect(
                                width - 2f * resolvedRadius,
                                0f,
                                width,
                                resolvedRadius * 2f,
                            ),
                            startAngleDegrees = 270f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false,
                        )
                    } else {
                        lineTo(width, 0f)
                    }

                    lineTo(width, height - resolvedDepth)

                    var currentX = width
                    repeat(toothCount) { index ->
                        val nextX = if (index == toothCount - 1) {
                            0f
                        } else {
                            width - (index + 1) * step
                        }.coerceAtLeast(0f)
                        val midX = (currentX + nextX) / 2f

                        lineTo(midX, height)
                        lineTo(nextX, height - resolvedDepth)
                        currentX = nextX
                    }

                    lineTo(0f, height - resolvedDepth)
                    if (resolvedRadius > 0f) {
                        lineTo(0f, resolvedRadius)
                    }
                    close()
                }

                Outline.Generic(path)
            }
        }
    }
}
