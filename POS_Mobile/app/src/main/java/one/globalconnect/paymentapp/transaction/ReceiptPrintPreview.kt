package one.globalconnect.paymentapp.transaction

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlin.math.roundToInt
import kotlin.math.min

@Composable
fun ReceiptPrintPreview(
    state: ReceiptPreviewState,
    onDismissed: () -> Unit,
) {
    var userDismissed by remember(state.triggerTimestamp) { mutableStateOf(false) }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (userDismissed) 0f else 1f,
        label = "receiptPreviewOverlayAlpha",
    )

    if (overlayAlpha == 0f && userDismissed) {
        LaunchedEffect(Unit) { onDismissed() }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f * overlayAlpha))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) {
                userDismissed = true
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
                .padding(top = 64.dp)
                .width(280.dp)
                .shadow(12.dp, ticketShape, clip = false)
                .clip(ticketShape)
                .background(color_white.copy(alpha = overlayAlpha))
                .border(
                    width = 1.dp,
                    color = color_secondaryFive.copy(alpha = 0.1f * overlayAlpha),
                    shape = ticketShape,
                )
                .padding(horizontal = 18.dp, vertical = 22.dp),
            state = state,
            onAnimationFinished = {
                if (!userDismissed) {
                    userDismissed = true
                }
            },
        )
    }
}

@Composable
private fun ReceiptTicket(
    modifier: Modifier = Modifier,
    state: ReceiptPreviewState,
    onAnimationFinished: () -> Unit,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(state.triggerTimestamp) {
        scrollState.scrollTo(0)
        val maxScroll = when (val value = scrollState.maxValue) {
            0 -> snapshotFlow { scrollState.maxValue }
                .filter { it > 0 }
                .firstOrNull()
            else -> value
        }

        if (maxScroll != null && maxScroll > 0) {
            val pixelsPerMillisecond = 0.45f
            val computedDuration = (maxScroll / pixelsPerMillisecond).roundToInt()
            val duration = computedDuration.coerceIn(900, 12000)

            scrollState.animateLinearScrollTo(
                targetValue = maxScroll,
                durationMillis = duration,
                easing = LinearEasing,
            )
        } else {
            delay(1200)
        }
        delay(600)
        onAnimationFinished()
    }

    Column(
        modifier = modifier
            .heightIn(max = 420.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(modifier = Modifier.height(6.dp))
        state.lines.forEach { line ->
            if (line.primary.isBlank() && line.secondary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
            } else if (line.secondary == null) {
                val uppercasePrimary = line.primary == line.primary.uppercase()
                val fontSize = when {
                    line.emphasis && uppercasePrimary -> 17.sp
                    line.emphasis -> 15.sp
                    else -> 13.sp
                }
                Text(
                    text = line.primary,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (line.emphasis) FontWeight.SemiBold else FontWeight.Normal,
                    color = color_black,
                    textAlign = line.alignment,
                )
            } else {
                val fontSize = if (line.emphasis) 15.sp else 13.sp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = line.primary,
                        fontSize = fontSize,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (line.emphasis) FontWeight.SemiBold else FontWeight.Normal,
                        color = color_black,
                    )
                    Text(
                        text = line.secondary,
                        fontSize = fontSize,
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

private suspend fun ScrollState.animateLinearScrollTo(
    targetValue: Int,
    durationMillis: Int,
    easing: Easing = LinearEasing,
) {
    val clampedDuration = durationMillis.coerceAtLeast(0)
    if (clampedDuration == 0) {
        scrollTo(targetValue)
        return
    }

    val start = value
    val delta = targetValue - start
    if (delta == 0) {
        return
    }

    val durationNanos = clampedDuration * 1_000_000L
    val startTime = withFrameNanos { it }

    var finished = false
    while (!finished) {
        val frameTime = withFrameNanos { it }
        val elapsed = frameTime - startTime
        val fraction = (elapsed / durationNanos.toFloat()).coerceIn(0f, 1f)
        val easedFraction = easing.transform(fraction)
        val currentValue = start + delta * easedFraction

        scrollTo(currentValue.roundToInt())
        finished = fraction >= 1f
    }

    if (value != targetValue) {
        scrollTo(targetValue)
    }
}

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
